package com.pedro.whip

import android.media.MediaCodec
import android.util.Log
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.UrlParser
import com.pedro.common.VideoCodec
import com.pedro.common.base.ConnectGate
import com.pedro.common.frame.MediaFrame
import com.pedro.common.onMainThread
import com.pedro.common.socket.base.SocketType
import com.pedro.common.socket.base.StreamSocket
import com.pedro.common.socket.base.UdpStreamSocket
import com.pedro.common.toMediaFrameInfo
import com.pedro.common.toUInt32
import com.pedro.common.validMessage
import com.pedro.rtsp.utils.RtpConstants
import com.pedro.whip.dtls.DtlsClientConnection
import com.pedro.whip.dtls.DtlsConnection
import com.pedro.whip.dtls.DtlsServerConnection
import com.pedro.whip.dtls.DtlsTransport
import com.pedro.whip.dtls.SrtpKeys
import com.pedro.whip.utils.InboundSilenceTracker
import com.pedro.whip.utils.Network
import com.pedro.whip.utils.readFrom
import com.pedro.whip.utils.ReceiveLoop
import com.pedro.whip.webrtc.CandidateType
import com.pedro.whip.webrtc.CommandsManager
import com.pedro.whip.webrtc.stun.AttributeType
import com.pedro.whip.webrtc.stun.GatheringMode
import com.pedro.whip.webrtc.stun.HeaderType
import com.pedro.whip.webrtc.stun.StunAttribute
import com.pedro.whip.webrtc.stun.StunAttributeValueParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import java.net.Inet6Address
import java.net.URISyntaxException
import java.nio.ByteBuffer
import javax.net.ssl.TrustManager
import kotlin.time.Duration.Companion.milliseconds

class WhipClient(private val connectChecker: ConnectChecker) {

    private val TAG = "WhipClient"

    private val validSchemes = arrayOf("http", "https")

    private var scope = CoroutineScope(Dispatchers.IO)
    private var scopeRetry = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private var jobRetry: Job? = null
    private var mutex = Mutex(locked = true)

    // GPX R65 — the stop count, the generation and the deferred connect (fork plan, R65). Never held
    // across a suspension or a callback.
    private val gate = ConnectGate()
    // GPX R65 — one teardown at a time. Taken only by coroutines on IO, never by a caller of
    // connect(), so nothing on the main thread waits for it.
    private val teardownLock = Mutex()

    @Volatile
    var isStreaming = false
        private set

    //for secure transport
    private var tlsEnabled = false
    private var dtlsConnection: DtlsConnection? = null
    // GPX R14 — the ICE and media UDP socket, held so disconnect() can close it. As a local inside
    // the connect job, the blocking STUN read loop kept the socket alive after a stop and STUN kept
    // flowing.
    @Volatile
    private var iceSocket: UdpStreamSocket? = null
    // GPX R14 — counts media-plane packets received from the server after DTLS. For a send-only
    // publisher these are the server's RTCP feedback, so their presence shows the ingest is
    // receiving the stream rather than only holding the ICE session.
    private val mediaPlaneIn = AtomicLong(0)
    // GPX R42 — when that media-plane feedback last arrived, read by getInboundSilenceMs(). A count
    // alone cannot show that the feedback has stopped.
    internal val inboundSilence = InboundSilenceTracker()
    private val commandsManager = CommandsManager()
    private val whipSender: WhipSender = WhipSender(connectChecker, commandsManager)
    private var url: String? = null
    private var doingRetry = false
    private var numRetry = 0
    private var reTries = 0
    private var checkServerAlive = false
    var socketType = SocketType.KTOR

    val droppedAudioFrames: Long
        get() = whipSender.getDroppedAudioFrames()
    val droppedVideoFrames: Long
        get() = whipSender.getDroppedVideoFrames()

    val cacheSize: Int
        get() = whipSender.getCacheSize()
    val sentAudioFrames: Long
        get() = whipSender.getSentAudioFrames()
    val sentVideoFrames: Long
        get() = whipSender.getSentVideoFrames()
    val bytesSend: Long
        get() = whipSender.getBytesSend()
    var socketTimeout = StreamSocket.DEFAULT_TIMEOUT

    fun addCertificates(certificates: TrustManager?) {
        commandsManager.addCertificates(certificates)
    }

    fun setDelay(millis: Long) {
        whipSender.setDelay(millis)
    }

    fun setAuthorization(token: String?) {
        commandsManager.setAuth(token)
    }

    /**
     * Check periodically if server is alive using Echo protocol.
     */
    fun setCheckServerAlive(enabled: Boolean) {
        checkServerAlive = enabled
    }

    /**
     * Must be called before connect
     */
    fun setOnlyAudio(onlyAudio: Boolean) {
        if (onlyAudio) {
            commandsManager.rtpTracks.trackAudio = 0
            commandsManager.rtpTracks.trackVideo = 1
        } else {
            commandsManager.rtpTracks.trackVideo = 0
            commandsManager.rtpTracks.trackAudio = 1
        }
        commandsManager.audioDisabled = false
        commandsManager.videoDisabled = onlyAudio
    }

    /**
     * Must be called before connect
     */
    fun setOnlyVideo(onlyVideo: Boolean) {
        commandsManager.rtpTracks.trackVideo = 0
        commandsManager.rtpTracks.trackAudio = 1
        commandsManager.videoDisabled = false
        commandsManager.audioDisabled = onlyVideo
    }

    fun setReTries(reTries: Int) {
        numRetry = reTries
        this.reTries = reTries
    }

    fun shouldRetry(reason: String): Boolean {
        val validReason = doingRetry && !reason.contains("Endpoint malformed")
        return validReason && reTries > 0
    }

    fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
        Log.i(TAG, "send sps and pps")
        commandsManager.setVideoInfo(sps, pps, vps)
        if (mutex.isLocked) runCatching { mutex.unlock() }
    }

    fun setAudioInfo(sampleRate: Int, isStereo: Boolean) {
        commandsManager.setAudioInfo(sampleRate, isStereo)
    }

    fun setVideoCodec(videoCodec: VideoCodec) {
        if (!isStreaming) {
            commandsManager.videoCodec = videoCodec
        }
    }

    fun setAudioCodec(audioCodec: AudioCodec) {
        if (!isStreaming) {
            commandsManager.audioCodec = when (audioCodec) {
                AudioCodec.G711, AudioCodec.OPUS -> audioCodec
                else -> throw IllegalArgumentException("Unsupported codec: ${audioCodec.name}")
            }
        }
    }

    fun connect(url: String?) {
        connect(url, false)
    }

    fun connect(url: String?, isRetry: Boolean) {
        gate.locked {
            // GPX R65 — a connect that arrives while a stop runs is remembered and started after the
            // teardown (the latest url wins). A retry's connect is dropped: the stop wins.
            if (gate.stopping()) {
                if (!isRetry) gate.defer(url)
                return
            }
            connectLocked(url, isRetry)
        }
    }

    /** GPX R65 — closes a socket even when the calling job is already cancelled. */
    private suspend fun closeQuietly(socket: UdpStreamSocket) {
        withContext(NonCancellable) { runCatching { socket.close() } }
    }

    /** GPX R65 — a connect job of an older generation must not clear the flag of a newer one. */
    private fun connectFailed(gen: Long) {
        gate.locked { if (gate.isCurrent(gen)) isStreaming = false }
    }

    // Called with the gate's monitor held, so the stopping check, `isStreaming` and the launch are one step.
    private fun connectLocked(url: String?, isRetry: Boolean) {
        if (!isRetry) doingRetry = true
        if (!isStreaming || isRetry) {
            isStreaming = true
            val gen = gate.generation

            job = scope.launch {
                if (url == null) {
                    connectFailed(gen)
                    onMainThread {
                        connectChecker.onConnectionFailed("Endpoint malformed, should be: http://ip:port/path")
                    }
                    return@launch
                }
                this@WhipClient.url = url
                onMainThread { connectChecker.onConnectionStarted(url) }

                val urlParser = try {
                    UrlParser.parse(url, validSchemes)
                } catch (_: URISyntaxException) {
                    connectFailed(gen)
                    onMainThread {
                        connectChecker.onConnectionFailed("Endpoint malformed, should be: http://ip:port/path")
                    }
                    return@launch
                }

                tlsEnabled = urlParser.scheme.endsWith("s")
                val host = urlParser.host
                val port = urlParser.port ?: if (tlsEnabled) 443 else 8889
                val path = urlParser.getFullPath().removePrefix("/")
                if (path.isEmpty()) {
                    connectFailed(gen)
                    onMainThread {
                        connectChecker.onConnectionFailed("Endpoint malformed, should be: http://ip:port/path")
                    }
                    return@launch
                }

                // GPX R57 — the media socket this attempt opened, so the failure handler below can tell
                // a disconnect that closed it on purpose from a fault, and can close it on a fault.
                var connectSocket: UdpStreamSocket? = null
                val error = runCatching {
                    commandsManager.updateTimestamp()
                    commandsManager.setUrl(host, port, path, tlsEnabled)
                    if (!commandsManager.audioDisabled) {
                        whipSender.setAudioInfo(commandsManager.sampleRate, commandsManager.isStereo)
                    }
                    if (!commandsManager.videoDisabled) {
                        if (!commandsManager.videoInfoReady()) {
                            Log.i(TAG, "waiting for sps and pps")
                            withTimeoutOrNull(5_000.milliseconds) { mutex.lock() }
                            if (!commandsManager.videoInfoReady()) {
                                onMainThread {
                                    connectChecker.onConnectionFailed("sps or pps is null")
                                }
                                return@launch
                            }
                        }
                        whipSender.setVideoInfo(commandsManager.sps!!, commandsManager.pps, commandsManager.vps)
                    }

                    val localCandidates = commandsManager.gatheringCandidates(socketType, socketTimeout, GatheringMode.LOCAL)
                    Log.i(TAG, "found ${localCandidates.size} candidates")
                    val offerResponse = commandsManager.writeOffer()
                    Log.i(TAG, offerResponse.toString())
                    if (offerResponse.statusCode !in 200..299) {
                        val needAuth = offerResponse.statusCode in 400..499
                        if (needAuth && commandsManager.token != null) {
                            val offerResponseAuth = commandsManager.writeOffer(sendAuth = true)
                            Log.i(TAG, offerResponseAuth.toString())
                            if (offerResponseAuth.statusCode !in 200..299) {
                                onMainThread {
                                    connectChecker.onConnectionFailed("Error configure stream, offer failed: ${offerResponseAuth.statusCode}")
                                }
                                return@launch
                            } else {
                                onMainThread { connectChecker.onAuthSuccess() }
                            }
                        } else if (needAuth) {
                            onMainThread { connectChecker.onAuthError() }
                            return@launch
                        } else {
                            onMainThread {
                                connectChecker.onConnectionFailed("Error configure stream, offer failed: ${offerResponse.statusCode}")
                            }
                            return@launch
                        }
                    }

                    val localFrag = commandsManager.localSdpInfo?.uFrag ?: return@launch
                    val remoteFrag = commandsManager.remoteSdpInfo?.uFrag ?: return@launch
                    val priority = commandsManager.calculatePriority(CandidateType.LOCAL, 65535L, 1)

                    val availableFamilies = Network.getNetworks(onlyV4 = false).map { it is Inet6Address }.toSet()
                    val remoteCandidates = commandsManager.remoteSdpInfo?.candidates?.filter {
                        val candidateHost = it.getRealHost()
                        candidateHost != "127.0.0.1" && candidateHost != "::1"
                    }?.sortedByDescending {
                        availableFamilies.contains(it.getRealHost().contains(":"))
                    } ?: return@launch

                    val host = remoteCandidates[0].getRealHost()
                    val port = remoteCandidates[0].getRealPort()
                    //bind socket instead of connect to avoid ignore packets received from other port
                    val bindHost = if (host.contains(":")) "::" else "0.0.0.0"
                    val socket = StreamSocket.createUdpSocket(socketType, bindHost, 0, socketTimeout)
                    socket.bind()
                    socket.setRemoteAddress(host, port)
                    connectSocket = socket
                    // GPX R14 — held so disconnect() can close it.
                    // GPX R65 — only a current connect may hold it; a stale one closes its own socket,
                    // so a cancelled attempt never leaves the media socket open.
                    val held = gate.locked {
                        (gate.isCurrent(gen)).also { if (it) iceSocket = socket }
                    }
                    if (!held) {
                        closeQuietly(socket)
                        return@launch
                    }

                    val dtlsTransport = DtlsTransport(socket)
                    val requestId = commandsManager.generateTransactionId()
                    val userName = StunAttributeValueParser.createUserName(localFrag, remoteFrag)
                    val attributes = listOf(
                        StunAttribute(AttributeType.USERNAME, userName),
                        StunAttribute(AttributeType.ICE_CONTROLLING, commandsManager.tieBreak),
                        StunAttribute(AttributeType.PRIORITY, priority.toUInt32()),
                    )
                    commandsManager.writeStun(HeaderType.REQUEST, requestId, attributes, socket)

                    // GPX R14 — ICE binding check with retransmit. Sending one request and blocking on
                    // the read means an ice-lite server that does not answer that single request
                    // leaves the loop spinning on the server's own checks: it never nominates, never
                    // reaches DTLS, and reports a connection with no media. Retransmit on a short
                    // timeout, bounded, then fail with a message that says which step failed.
                    val iceRtoMs = 500L
                    val iceMaxAttempts = 14 // about 7 seconds
                    var requestSuccessReceived = false
                    var iceAttempts = 0
                    while (!requestSuccessReceived) {
                        val bytes = withTimeoutOrNull(iceRtoMs.milliseconds) { socket.readFrom(host) }
                        if (bytes == null) {
                            if (++iceAttempts >= iceMaxAttempts) break
                            commandsManager.writeStun(HeaderType.REQUEST, requestId, attributes, socket)
                            continue
                        }
                        if (bytes.isNotEmpty() && (bytes[0].toInt() and 0xFF) in 20..63) {
                            dtlsTransport.enqueue(bytes)
                            continue
                        }
                        val command = commandsManager.readStun(bytes)
                        if (command.header.id.contentEquals(requestId) && command.header.type == HeaderType.SUCCESS) {
                            requestSuccessReceived = true
                        } else if (command.header.type == HeaderType.REQUEST) {
                            val xorAddress = StunAttributeValueParser.createXorMappedAddress(command.header.id, host, port)
                            val responseAttributes = listOf(
                                StunAttribute(AttributeType.XOR_MAPPED_ADDRESS, xorAddress)
                            )
                            commandsManager.writeStun(HeaderType.SUCCESS, command.header.id, responseAttributes, socket)
                        }
                    }
                    if (!requestSuccessReceived) {
                        runCatching { socket.close() }
                        if (iceSocket === socket) iceSocket = null
                        onMainThread {
                            connectChecker.onConnectionFailed("ICE connectivity check failed (no STUN binding success from server)")
                        }
                        return@launch
                    }

                    val nominateId = commandsManager.generateTransactionId()
                    val nominateAttributes = listOf(
                        StunAttribute(AttributeType.PRIORITY, priority.toUInt32()),
                        StunAttribute(AttributeType.USERNAME, userName),
                        StunAttribute(AttributeType.ICE_CONTROLLING, commandsManager.tieBreak),
                        StunAttribute(AttributeType.USE_CANDIDATE, byteArrayOf()),
                    )
                    commandsManager.writeStun(HeaderType.REQUEST, nominateId, nominateAttributes, socket)

                    // GPX R14 — same retransmit treatment for the USE-CANDIDATE nomination.
                    var nominateSuccessReceived = false
                    var nominateAttempts = 0
                    while (!nominateSuccessReceived) {
                        val bytes = withTimeoutOrNull(iceRtoMs.milliseconds) { socket.readFrom(host) }
                        if (bytes == null) {
                            if (++nominateAttempts >= iceMaxAttempts) break
                            commandsManager.writeStun(HeaderType.REQUEST, nominateId, nominateAttributes, socket)
                            continue
                        }
                        if (bytes.isNotEmpty() && (bytes[0].toInt() and 0xFF) in 20..63) {
                            dtlsTransport.enqueue(bytes)
                            continue
                        }
                        val command = commandsManager.readStun(bytes)
                        if (command.header.id.contentEquals(nominateId) && command.header.type == HeaderType.SUCCESS) {
                            nominateSuccessReceived = true
                        } else if (command.header.type == HeaderType.REQUEST) {
                            val xorAddress = StunAttributeValueParser.createXorMappedAddress(command.header.id, host, port)
                            val responseAttributes = listOf(
                                StunAttribute(AttributeType.XOR_MAPPED_ADDRESS, xorAddress)
                            )
                            commandsManager.writeStun(HeaderType.SUCCESS, command.header.id, responseAttributes, socket)
                        }
                    }
                    if (!nominateSuccessReceived) {
                        runCatching { socket.close() }
                        if (iceSocket === socket) iceSocket = null
                        onMainThread {
                            connectChecker.onConnectionFailed("ICE nomination failed (no STUN success for USE-CANDIDATE)")
                        }
                        return@launch
                    }

                    val certificate = commandsManager.certificate
                    val fingerprint = commandsManager.remoteSdpInfo?.fingerprint
                    if (certificate == null || fingerprint == null) {
                        // GPX R57 — release the socket and report, rather than return with the media
                        // socket open and the connection silent.
                        runCatching { socket.close() }
                        if (iceSocket === socket) iceSocket = null
                        onMainThread {
                            connectChecker.onConnectionFailed("Error configure stream, DTLS certificate or fingerprint missing")
                        }
                        return@launch
                    }

                    val dtlsResult = CompletableDeferred<Result<SrtpKeys>>()
                    val isDtlsServer = commandsManager.remoteSdpInfo?.isActiveMode ?: false
                    Log.i(TAG, "Using dtls ${if (isDtlsServer) "server" else "client"} role")
                    val dtls = if (isDtlsServer) {
                        DtlsServerConnection(certificate, fingerprint)
                    } else {
                        DtlsClientConnection(certificate, fingerprint)
                    }
                    // GPX R65 — a stale connect does not publish its DTLS connection, so it can never
                    // overwrite the field of a newer one. It ends here; the stop closed its socket.
                    val dtlsHeld = gate.locked {
                        gate.isCurrent(gen).also { if (it) dtlsConnection = dtls }
                    }
                    if (!dtlsHeld) {
                        closeQuietly(socket)
                        return@launch
                    }

                    // GPX R47 — a fault in the receive loop is reported, not swallowed. The loop used to
                    // end quietly on any exception, which left the client claiming to stream with no
                    // reader: nothing counted the server's RTCP (R42 then read a healthy ingest as
                    // silent) and the server's STUN checks went unanswered. iceSocket no longer
                    // pointing at this socket means disconnect() closed it on purpose.
                    val dispatchJob = launch {
                        ReceiveLoop(
                            readOnce = { handleMessages(socket, host, port, dtlsTransport) },
                            isClosing = { iceSocket !== socket },
                            onFault = { e ->
                                Log.e(TAG, "receive loop failed", e)
                                if (isStreaming) {
                                    onMainThread {
                                        connectChecker.onConnectionFailed("Receive loop failed, ${e.validMessage()}")
                                    }
                                }
                            },
                        ).run()
                    }

                    dtls.start(dtlsTransport, object : DtlsConnection.Callback {
                        override fun onHandshakeComplete(keys: SrtpKeys) {
                            dtlsResult.complete(Result.success(keys))
                        }
                        override fun onHandshakeFailed(reason: String?) {
                            dtlsResult.complete(Result.failure(Exception(reason ?: "DTLS handshake failed")))
                        }
                    })

                    val result = withTimeoutOrNull(10_000.milliseconds) { dtlsResult.await() }
                        ?: Result.failure(Exception("timeout"))
                    val srtpKeys = result.getOrNull()
                    if (srtpKeys == null) {
                        dispatchJob.cancel()
                        dtls.close()
                        if (dtlsConnection === dtls) dtlsConnection = null
                        runCatching { socket.close() }
                        if (iceSocket === socket) iceSocket = null
                        onMainThread {
                            connectChecker.onConnectionFailed("DTLS handshake failed: ${result.exceptionOrNull()?.message}")
                        }
                        return@launch
                    }
                    Log.i(TAG, "dtls connected!!")
                    onMainThread { connectChecker.onConnectionSuccess() }
                    // GPX R65 — the stop cancels this job first, so a cancelled job stops here; the
                    // generation covers a job that outlived the stop's capped wait.
                    ensureActive()
                    if (!gate.isCurrent(gen)) return@launch
                    whipSender.setSocketsInfo(socket)
                    whipSender.setCrypto(srtpKeys.rtp, srtpKeys.rtcp)
                    whipSender.start()
                    // GPX R42 — media is flowing from here, so start the inbound-silence clock.
                    inboundSilence.start()
                }.exceptionOrNull()
                if (error != null) {
                    // GPX R57 — a cancelled attempt ends quietly. A read that failed because
                    // disconnect() closed the socket (iceSocket no longer points at it) is a stop, not a
                    // fault, so nothing is reported. Any other fault releases the socket it opened.
                    // GPX R65 — a cancelled or stale attempt also closes the media socket it opened: the
                    // stop closes the one in the field, and this one may have been published after it.
                    if (error is CancellationException || !gate.isCurrent(gen)) {
                        connectSocket?.let { closeQuietly(it) }
                        if (error is CancellationException) throw error
                        return@launch
                    }
                    val failedSocket = connectSocket
                    if (failedSocket != null && iceSocket !== failedSocket) {
                        Log.i(TAG, "connect interrupted by disconnect: ${error.validMessage()}")
                        return@launch
                    }
                    if (failedSocket != null) {
                        runCatching { failedSocket.close() }
                        if (iceSocket === failedSocket) iceSocket = null
                    }
                    Log.e(TAG, "connection error", error)
                    onMainThread {
                        connectChecker.onConnectionFailed("Error configure stream, ${error.validMessage()}")
                    }
                    return@launch
                }
            }
        }
    }

    suspend fun handleMessages(
      socket: UdpStreamSocket,
      host: String, port: Int,
      dtlsTransport: DtlsTransport
    ) {
        // GPX R53 — only datagrams from the selected remote candidate's host are handled.
        val bytes = socket.readFrom(host)
        if (bytes.isEmpty()) return
        val first = bytes[0].toInt() and 0xFF
        when (first) {
            in 20..63 -> dtlsTransport.enqueue(bytes)
            in 128..191 -> {
                // GPX R14 — RTP and RTCP are not consumed by a send-only publisher, but counted:
                // feedback arriving here means the server is receiving the stream. Log throttled.
                val n = mediaPlaneIn.incrementAndGet()
                inboundSilence.onInbound() // GPX R42
                if (n <= 5L || n % 50L == 0L) Log.i(TAG, "media-plane in from server: $n")
            }
            else -> {
                try {
                    val command = commandsManager.readStun(bytes)
                    if (command.header.type == HeaderType.REQUEST) {
                        val xorAddress = StunAttributeValueParser.createXorMappedAddress(
                            command.header.id, host, port
                        )
                        commandsManager.writeStun(
                            HeaderType.SUCCESS, command.header.id,
                            listOf(StunAttribute(AttributeType.XOR_MAPPED_ADDRESS, xorAddress)),
                            socket
                        )
                    }
                } catch (_: Exception) { }
            }
        }
    }

    fun disconnect() {
        // GPX R65 — the stop starts here, synchronously, so a connect() that follows at once is seen
        // as arriving during a stop. The connect jobs and the retry jobs are cancelled now, the stop
        // always wins; the teardown below waits for them.
        gate.locked {
            gate.beginStop()
            scope.cancel()
            scopeRetry.cancel()
        }
        CoroutineScope(Dispatchers.IO).launch {
            disconnect(true)
        }
    }

    // GPX R65 — one teardown at a time, and it runs to its end even when its caller is cancelled (a
    // retry's teardown is cancelled by a user stop). The scope is replaced and the stop ends, with a
    // deferred connect started, in the `finally`, so a throw cannot leave the client stopping forever.
    private suspend fun disconnect(clear: Boolean) {
        teardownLock.withLock {
            withContext(NonCancellable) {
                val connectScope = gate.locked {
                    gate.supersede()
                    scope.also { it.cancel() }
                }
                try {
                    teardown(clear, connectScope)
                } finally {
                    gate.locked {
                        scope = CoroutineScope(Dispatchers.IO)
                        if (clear) {
                            // a throw part-way through the teardown must not leave the client
                            // streaming or the retry scope cancelled
                            isStreaming = false
                            if (!scopeRetry.isActive) scopeRetry = CoroutineScope(Dispatchers.IO)
                            gate.endStop { connectLocked(it, false) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun teardown(clear: Boolean, connectScope: CoroutineScope) {
        // GPX R58 — clear the field before anything is torn down. R47's receive loop reads "iceSocket
        // no longer this socket" as "closed on purpose", and closing the DTLS connection below already
        // fails the loop's read, so the field must say so before that close.
        val closing = iceSocket
        iceSocket = null
        runCatching { dtlsConnection?.close() } // GPX R65 — a throw here must not end the teardown
        dtlsConnection = null
        if (isStreaming) whipSender.stop()
        // GPX R14 — close the ICE and media UDP socket so an in-flight STUN read unblocks and the OS
        // stops answering the server's binding checks. Without this the connect job's read loop kept
        // STUN alive after a stop and the server-side session lingered.
        runCatching { closing?.close() }
        val error = runCatching {
            withTimeoutOrNull(100.milliseconds) {
                commandsManager.writeDelete()
            }
            Log.i(TAG, "write delete success")
        }.exceptionOrNull()
        if (error != null) {
            Log.e(TAG, "disconnect error", error)
        }
        if (clear) {
            commandsManager.clear()
            reTries = numRetry
            doingRetry = false
            isStreaming = false
            onMainThread {
                connectChecker.onDisconnect()
            }
            mutex = Mutex(true)
            // GPX R65 — every retry job, not the one in the field; they were cancelled when the stop began.
            val retryScope = scopeRetry
            retryScope.cancel()
            withTimeoutOrNull(ConnectGate.CONNECT_JOIN_TIMEOUT_MS.milliseconds) { retryScope.coroutineContext.job.join() }
            jobRetry = null
            scopeRetry = CoroutineScope(Dispatchers.IO)
        } else {
            commandsManager.clear()
        }
        // GPX R65 — wait for every connect job of the scope, not the one in the `job` field, with a
        // capped wait: a job stuck in a call that ignores cancellation cannot hang the stop, and its
        // generation is stale, so it writes nothing when it wakes.
        withTimeoutOrNull(ConnectGate.CONNECT_JOIN_TIMEOUT_MS.milliseconds) { connectScope.coroutineContext.job.join() }
        job = null
        // GPX R62 — stop the sender again, now that the connect jobs are gone, whether or not it was
        // running when the guard above read it (fork plan, R62). Before R65 the connect job was
        // cancelled only here and could start the sender after that guard; now the stop cancels it
        // when it begins, so this stop is the safety net for a job that was already past its
        // cancellation checks. BaseSender.stop() is safe on a sender that never started, and on one
        // the guard already stopped.
        whipSender.stop()
        // GPX R42 — cleared on both a stop and a retry's teardown, after the connect job has joined
        // so it cannot restart the clock behind this. isStreaming stays true through a retry, so
        // without this the retry window would report the old session's silence.
        inboundSilence.reset()
    }

    fun sendVideo(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!commandsManager.videoDisabled) {
            whipSender.sendMediaFrame(videoBuffer, info.toMediaFrameInfo(), MediaFrame.Type.VIDEO)
        }
    }

    fun sendAudio(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!commandsManager.audioDisabled) {
            whipSender.sendMediaFrame(audioBuffer, info.toMediaFrameInfo(), MediaFrame.Type.AUDIO)
        }
    }

    @JvmOverloads
    @Throws(IllegalArgumentException::class)
    fun hasCongestion(percentUsed: Float = 20f): Boolean {
        return whipSender.hasCongestion(percentUsed)
    }

    @JvmOverloads
    fun reConnect(delay: Long, backupUrl: String? = null) {
        gate.locked {
            // GPX R65 — a retry that arrives during a user stop does not start.
            if (gate.stopping()) return
            jobRetry = scopeRetry.launch {
                reTries--
                disconnect(false)
                delay(delay.milliseconds)
                val reconnectUrl = backupUrl ?: url
                connect(reconnectUrl, true)
            }
        }
    }

    fun resetSentAudioFrames() {
        whipSender.resetSentAudioFrames()
    }

    fun resetSentVideoFrames() {
        whipSender.resetSentVideoFrames()
    }

    fun resetDroppedAudioFrames() {
        whipSender.resetDroppedAudioFrames()
    }

    fun resetDroppedVideoFrames() {
        whipSender.resetDroppedVideoFrames()
    }

    @Throws(RuntimeException::class)
    fun resizeCache(newSize: Int) {
        whipSender.resizeCache(newSize)
    }

    fun setLogs(enable: Boolean) {
        whipSender.setLogs(enable)
    }

    fun clearCache() {
        whipSender.clearCache()
    }

    fun getItemsInCache(): Int = whipSender.getItemsInCache()

    fun getQueueBytesOut(): Long = whipSender.getQueueBytesOut()

    /**
     * GPX R42 — milliseconds since the server last sent a media-plane packet (its RTCP feedback on
     * the stream), or -1 when not streaming or not yet established (including a retry's backoff).
     * Counted from session establishment until the first packet arrives. Grows once the ingest
     * stops receiving the stream, which an outbound bytes-sent counter cannot show.
     */
    fun getInboundSilenceMs(): Long = if (!isStreaming) -1L else inboundSilence.silenceMs()

    /**
     * @param factor values from 0.1f to 1f
     * Set an exponential factor to the bitrate calculation to avoid bitrate spikes
     */
    fun setBitrateExponentialFactor(factor: Float) {
        whipSender.setBitrateExponentialFactor(factor)
    }

    /**
     * Get the exponential factor used to calculate the bitrate. Default 1f
     */
    fun getBitrateExponentialFactor() = whipSender.getBitrateExponentialFactor()

    fun resetBytesSend() {
        whipSender.resetBytesSend()
    }

}