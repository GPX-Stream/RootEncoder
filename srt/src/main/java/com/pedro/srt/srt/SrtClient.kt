/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.srt.srt

import android.media.MediaCodec
import android.util.Log
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.ConnectionFailed
import com.pedro.common.UrlParser
import com.pedro.common.VideoCodec
import com.pedro.common.base.ConnectGate
import com.pedro.common.frame.MediaFrame
import com.pedro.common.TimeUtils
import com.pedro.common.onMainThread
import com.pedro.common.socket.base.SocketType
import com.pedro.common.socket.base.StreamSocket
import com.pedro.common.toMediaFrameInfo
import com.pedro.common.validMessage
import com.pedro.srt.mpeg2ts.service.Mpeg2TsService
import com.pedro.srt.srt.packets.ControlPacket
import com.pedro.srt.srt.packets.DataPacket
import com.pedro.srt.srt.packets.SrtPacket
import com.pedro.srt.srt.packets.control.Ack
import com.pedro.srt.srt.packets.control.Ack2
import com.pedro.srt.srt.packets.control.CongestionWarning
import com.pedro.srt.srt.packets.control.DropReq
import com.pedro.srt.srt.packets.control.KeepAlive
import com.pedro.srt.srt.packets.control.Nak
import com.pedro.srt.srt.packets.control.PeerError
import com.pedro.srt.srt.packets.control.Shutdown
import com.pedro.srt.srt.packets.control.handshake.EncryptionType
import com.pedro.srt.srt.packets.control.handshake.ExtensionField
import com.pedro.srt.srt.packets.control.handshake.Handshake
import com.pedro.srt.srt.packets.control.handshake.HandshakeType
import com.pedro.srt.srt.packets.control.handshake.extension.ExtensionContentFlag
import com.pedro.srt.srt.packets.control.handshake.extension.HandshakeExtension
import com.pedro.srt.utils.SrtSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URISyntaxException
import java.nio.ByteBuffer
import kotlin.time.Duration.Companion.milliseconds

/**
 * Created by pedro on 20/8/23.
 */
class SrtClient(private val connectChecker: ConnectChecker) {

  private val TAG = "SrtClient"

  // GPX R8 — the handshake backoff constants live in HandshakeTimeout.kt, beside the budget they set.

  private val validSchemes = arrayOf("srt")

  private val commandsManager = CommandsManager()
  private val srtSender = SrtSender(connectChecker, commandsManager)
  private var socket: SrtSocket? = null
  private var scope = CoroutineScope(Dispatchers.IO)
  private var job: Job? = null
  private var scopeRetry = CoroutineScope(Dispatchers.IO)
  private var jobRetry: Job? = null

  // GPX R65 — the stop count, the generation and the deferred connect (fork plan, R65). Never held
  // across a suspension or a callback.
  private val gate = ConnectGate()
  // GPX R65 — one teardown at a time. Taken only by coroutines on IO, never by a caller of
  // connect(), so nothing on the main thread waits for it.
  private val teardownLock = Mutex()

  private var checkServerAlive = false

  // GPX R49 — the clock for the inbound-silence reading and R8's handshake deadline: elapsed realtime,
  // which only moves forward. They used System.currentTimeMillis, a wall clock the system can step (a
  // network time sync after the cell radio registers): a forward step faked a long silence in the
  // reading the consumer judges, a backward step hid a real one. A var only so tests can drive time.
  internal var nowMs: () -> Long = { TimeUtils.getCurrentTimeMillis() }

  // GPX R7 — time (on [nowMs]) the last packet was read, 0 from connect start until the handshake completes. A silent UDP
  // blackhole keeps sendto() succeeding, so the outbound byte counter keeps climbing while the
  // server stops responding and nothing reports a failure. This reads SRT's own control traffic
  // (ACK and KeepAlive arrive sub-second at any latency) rather than ICMP, so a firewall that
  // drops ICMP does not blind it. GPX R60 — only the reading: this client no longer judges it, the
  // consumer reads [getInboundSilenceMs] and decides.
  @Volatile
  private var lastInboundMs = 0L

  private var serverSilenceTimeoutMs = 0L
  @Volatile
  var isStreaming = false
    private set
  private var url: String? = null
  private var doingRetry = false
  private var numRetry = 0
  private var reTries = 0

  val droppedAudioFrames: Long
    get() = srtSender.getDroppedAudioFrames()
  val droppedVideoFrames: Long
    get() = srtSender.getDroppedVideoFrames()

  val cacheSize: Int
    get() = srtSender.getCacheSize()
  val sentAudioFrames: Long
    get() = srtSender.getSentAudioFrames()
  val sentVideoFrames: Long
    get() = srtSender.getSentVideoFrames()
  val bytesSend: Long
    get() = srtSender.getBytesSend()
  var rtt = 0 //in micro
    private set
  var packetsLost = 0
    private set
  /**
   * Unique lost sequence numbers reported by NAK. Each sequence is counted once.
   */
  var packetsLostUnique = 0
    private set
  var socketType = SocketType.JAVA
  var socketTimeout = StreamSocket.DEFAULT_TIMEOUT

  fun setVideoCodec(videoCodec: VideoCodec) {
    if (!isStreaming) {
      commandsManager.videoCodec = when (videoCodec) {
        VideoCodec.H264, VideoCodec.H265 -> videoCodec
        else -> throw IllegalArgumentException("Unsupported codec: ${videoCodec.name}")
      }
    }
  }

  fun setAudioCodec(audioCodec: AudioCodec) {
    if (!isStreaming) {
      commandsManager.audioCodec = when (audioCodec) {
        AudioCodec.OPUS, AudioCodec.AAC, AudioCodec.HE_AAC -> audioCodec
        else -> throw IllegalArgumentException("Unsupported codec: ${audioCodec.name}")
      }
    }
  }

  fun setLatency(latency: Int) {
    commandsManager.latency = latency
  }

  /**
   * Max retransmit bandwidth as a percentage of the estimated media rate (libsrt SRTO_OHEADBW).
   * Default 25. Values <= 0 disable the limit.
   */
  fun setRetransmitOverhead(percent: Int) {
    commandsManager.setRetransmitOverhead(percent)
  }

  fun setDelay(millis: Long) {
    srtSender.setDelay(millis)
  }

  /**
   * Set passphrase for encrypt. Use empty value to disable it.
   */
  fun setPassphrase(passphrase: String, type: EncryptionType) {
    if (!isStreaming) {
      if (passphrase.length !in 10..79) {
        throw IllegalArgumentException("passphrase must between 10 and 79 length")
      }
      commandsManager.setPassphrase(passphrase, type)
    }
  }

  /**
   * Must be called before connect
   */
  fun setOnlyAudio(onlyAudio: Boolean) {
    commandsManager.audioDisabled = false
    commandsManager.videoDisabled = onlyAudio
  }

  /**
   * Must be called before connect
   */
  fun setOnlyVideo(onlyVideo: Boolean) {
    commandsManager.videoDisabled = false
    commandsManager.audioDisabled = onlyVideo
  }

  /**
   * Check periodically if server is alive using Echo protocol.
   */
  fun setCheckServerAlive(enabled: Boolean) {
    checkServerAlive = enabled
  }

  /**
   * Report onConnectionFailed("No response from server") if no packet is received from the
   * server for [millis]. While data is being published, the server acknowledges it with ACK
   * packets (typically every 10 ms), so a long silence means the path is dead even if sending
   * does not fail (for example, packets silently dropped by the network).
   * Unlike [setCheckServerAlive] it needs no ICMP/Echo, which firewalls often block.
   * Servers only acknowledge received data, so use a value well above the longest pause in
   * which no media is sent. 0 (default) disables the check.
   */
  fun setServerSilenceTimeout(millis: Long) {
    serverSilenceTimeoutMs = millis
  }

  fun setReTries(reTries: Int) {
    numRetry = reTries
    this.reTries = reTries
  }

  fun shouldRetry(reason: String): Boolean {
    val validReason = doingRetry && !reason.contains("Endpoint malformed")
    return validReason && reTries > 0
  }

  @JvmOverloads
  fun connect(url: String?, isRetry: Boolean = false) {
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
  private suspend fun closeQuietly(socket: SrtSocket) {
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
      // GPX R60 — a new connection starts with no reading, so a reused client reports -1 from
      // getInboundSilenceMs() until its own handshake completes, never the last connection's silence.
      lastInboundMs = 0L
      isStreaming = true
      val gen = gate.generation

      job = scope.launch {
        if (url == null) {
          connectFailed(gen)
          onMainThread {
            connectChecker.onConnectionFailed("Endpoint malformed, should be: srt://ip:port/streamid")
          }
          return@launch
        }
        this@SrtClient.url = url
        onMainThread {
          connectChecker.onConnectionStarted(url)
        }

        val urlParser = try {
          UrlParser.parse(url, validSchemes)
        } catch (_: URISyntaxException) {
          connectFailed(gen)
          onMainThread {
            connectChecker.onConnectionFailed("Endpoint malformed, should be: srt://ip:port/streamid")
          }
          return@launch
        }

        val host = urlParser.host
        val port = urlParser.port ?: 8888
        // GPX patch — getFullPath(), not path: it keeps the query string attached, which a Millicast
        // ingest expects as the streamid when the publishing token rides in the URL as "?t=".
        // A Millicast URL is srt://<host>:<port>/<name>?t=<token>&latency=<ms>, so its path is
        // "<name>" and never empty. A fallback to getFullPath() that applies only to an empty path
        // would send "<name>" with the token dropped, which the ingest rejects. Keep this line on an
        // upstream sync.
        val path = urlParser.getQuery("streamid") ?: urlParser.getFullPath()
        commandsManager.latency = urlParser.getQuery("latency")?.toIntOrNull() ?: commandsManager.latency
        // GPX R46 — re-derive the socket read timeout from the URL latency on every connect. A host
        // that sets socketTimeout once at configure time leaves it stale after a latency change plus
        // a reconnect. Latency and socketTimeout are both milliseconds, so no unit conversion applies.
        socketTimeout = socketTimeoutMsFor(commandsManager.latency)
        val passphrase = urlParser.getQuery("passphrase") ?: ""
        if (passphrase.isNotEmpty() && passphrase.length in 10..79) {
          val encryptionType = when (urlParser.getQuery("pbkeylen")?.toIntOrNull()) {
            192 -> EncryptionType.AES192
            256 -> EncryptionType.AES256
            else -> EncryptionType.AES128
          }
          commandsManager.setPassphrase(passphrase, encryptionType)
        }
        if (path.isEmpty()) {
          connectFailed(gen)
          onMainThread {
            connectChecker.onConnectionFailed("Endpoint malformed, should be: srt://ip:port/streamid")
          }
          return@launch
        }
        commandsManager.host = host

        // GPX R65 — the socket this attempt opened, so the failure handler can close it when the
        // attempt is cancelled or stale.
        var connectSocket: SrtSocket? = null
        val error = runCatching {
          // GPX R8 — short read timeout during the handshake so a missed reply re-knocks on the
          // cadence instead of blocking the whole latency window on one packet.
          val newSocket = SrtSocket(socketType, host, port, HANDSHAKE_RETRANSMIT_MS)
          connectSocket = newSocket
          // GPX R65 — only a current connect may publish its socket; a stale one closes its own, so
          // a cancelled attempt never leaves the media socket open.
          val held = gate.locked { gate.isCurrent(gen).also { if (it) socket = newSocket } }
          if (!held) {
            closeQuietly(newSocket)
            return@launch
          }
          newSocket.connect()
          commandsManager.loadStartTs()

          // GPX R8, floored by GPX R48 — total knock budget for both phases: the latency-derived
          // socketTimeout, but never so short that R8's backoff cannot reach its capped gap.
          val handshakeBudgetMs = handshakeBudgetMsFor(commandsManager.latency)
          val handshakeDeadlineMs = nowMs() + handshakeBudgetMs

          val response = pollHandshake(handshakeDeadlineMs, "induction") {
            commandsManager.writeHandshake(socket)
          } ?: throw SocketTimeoutException("Poll timed out (no induction response in ${handshakeBudgetMs}ms)")

          val conclusion = response.copy(
            encryption = commandsManager.getEncryptType(),
            extensionField = ExtensionField.calculateValue(response.extensionField, commandsManager.encryptionEnabled(), path.isNotEmpty()),
            handshakeType = HandshakeType.CONCLUSION,
            handshakeExtension = HandshakeExtension(
              flags = ExtensionContentFlag.TSBPDSND.value or ExtensionContentFlag.TSBPDRCV.value or
                  ExtensionContentFlag.CRYPT.value or ExtensionContentFlag.TLPKTDROP.value or
                  ExtensionContentFlag.PERIODICNAK.value or ExtensionContentFlag.REXMITFLG.value,
              receiverDelay = commandsManager.latency,
              senderDelay = commandsManager.latency,
              path = path,
              encryptInfo = commandsManager.getEncryptInfo()
            ))
          // GPX R8 — accept only CONCLUSION here, so an INDUCTION echo buffered from an earlier
          // skipped rather than mistaken for the reply.
          val responseConclusion = pollHandshake(handshakeDeadlineMs, "conclusion", HandshakeType.CONCLUSION) {
            commandsManager.writeHandshake(socket, conclusion)
          } ?: throw SocketTimeoutException("Poll timed out (no conclusion response in ${handshakeBudgetMs}ms)")
          if (responseConclusion.isErrorType()) {
            onMainThread {
              connectChecker.onConnectionFailed("Error configure stream, ${responseConclusion.handshakeType.name}")
            }
            return@launch
          } else {
            commandsManager.socketId = responseConclusion.srtSocketId
            commandsManager.MTU = responseConclusion.MTU
            commandsManager.sequenceNumber = responseConclusion.initialPacketSequence
            // GPX R8 — handshake done: restore the latency-derived read timeout for the read loop.
            socket?.setReadTimeout(socketTimeout)
            onMainThread {
              connectChecker.onConnectionSuccess()
            }
            // GPX R65 — the stop cancels this job first, so a cancelled job stops here; the
            // generation covers a job that outlived the stop's capped wait.
            ensureActive()
            if (!gate.isCurrent(gen)) return@launch
            srtSender.socket = socket
            srtSender.start()
            lastInboundMs = nowMs()
            handleServerPackets(this@launch, gen)
          }
        }.exceptionOrNull()
        if (error != null) {
          // GPX R65 — a cancelled or stale attempt ends quietly and closes the socket it opened.
          if (error is CancellationException || !gate.isCurrent(gen)) {
            connectSocket?.let { closeQuietly(it) }
            if (error is CancellationException) throw error
            return@launch
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

  /**
   * GPX R8 — send a handshake through [send] and poll for its reply, retransmitting on a growing
   * gap until a
   * usable reply arrives or [deadlineMs] passes.
   *
   * [acceptType] gates which reply ends the loop; handshakes of other types are skipped, so an
   * INDUCTION echo left in the socket buffer by an earlier retransmit does not end the conclusion
   * phase. An error-type handshake always ends the loop so a server rejection reaches the caller.
   * Pass null to accept the first handshake of any type.
   *
   * Re-sending is driven by elapsed time rather than by each read returning, so a stray
   * non-handshake datagram — which [CommandsManager.readHandshake] throws on — is drained and
   * skipped while the knock stays on its own schedule, instead of aborting the connect.
   *
   * @return the accepted handshake, or null if the deadline passed first
   */
  private suspend fun pollHandshake(
    deadlineMs: Long,
    phase: String,
    acceptType: HandshakeType? = null,
    send: suspend () -> Unit,
  ): Handshake? {
    var lastSendMs = 0L
    var gapMs = HANDSHAKE_RETRANSMIT_MS
    // GPX R65 — this connect's own job, not the `scope` field, which the stop replaces.
    while (currentCoroutineContext().isActive && nowMs() < deadlineMs) {
      val now = nowMs()
      if (lastSendMs == 0L || now - lastSendMs >= gapMs) {
        send()
        if (lastSendMs != 0L) gapMs = (gapMs * 2).coerceAtMost(HANDSHAKE_RETRANSMIT_CAP_MS)
        lastSendMs = now
      }
      val handshake = try {
        commandsManager.readHandshake(socket)
      } catch (_: SocketTimeoutException) {
        continue // read window elapsed with no reply; the loop re-knocks once the gap has passed
      } catch (e: IOException) {
        if (e.message?.contains("unexpected response type") != true) throw e
        Log.i(TAG, "skip non-handshake packet during $phase: ${e.message}")
        continue
      }
      if (acceptType == null || handshake.isErrorType() || handshake.handshakeType == acceptType) {
        return handshake
      }
      Log.i(TAG, "skip stale $phase handshake: ${handshake.handshakeType.name}")
    }
    return null
  }

  fun disconnect() {
    // GPX R65 — the stop starts here, synchronously, so a connect() that follows at once is seen as
    // arriving during a stop. The connect jobs and the retry jobs are cancelled now, the stop always
    // wins; the teardown below waits for them.
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
            if (clear) gate.endStop { connectLocked(it, false) }
          }
        }
      }
    }
  }

  private suspend fun teardown(clear: Boolean, connectScope: CoroutineScope) {
    if (isStreaming) srtSender.stop(clear)
    runCatching {
      withTimeoutOrNull(100.milliseconds) {
        commandsManager.writeShutdown(socket)
      }
    }
    socket?.close()
    if (clear) {
      reTries = numRetry
      doingRetry = false
      isStreaming = false
      onMainThread {
        connectChecker.onDisconnect()
      }
      // GPX R65 — every retry job, not the one in the field; they were cancelled when the stop began.
      val retryScope = scopeRetry
      retryScope.cancel()
      withTimeoutOrNull(ConnectGate.CONNECT_JOIN_TIMEOUT_MS.milliseconds) { retryScope.coroutineContext.job.join() }
      jobRetry = null
      scopeRetry = CoroutineScope(Dispatchers.IO)
    }
    commandsManager.reset()
    rtt = 0
    packetsLost = 0
    packetsLostUnique = 0
    // GPX R65 — wait for every connect job of the scope, not the one in the `job` field, with a
    // capped wait: a job stuck in a call that ignores cancellation cannot hang the stop, and its
    // generation is stale, so it writes nothing when it wakes.
    withTimeoutOrNull(ConnectGate.CONNECT_JOIN_TIMEOUT_MS.milliseconds) { connectScope.coroutineContext.job.join() }
    job = null
    // GPX R63 — stop the sender again, now that the connect job is gone, whether or not it was
    // running when the guard above read it. The connect job is cancelled only here, and it can run
    // on to srtSender.start() after that guard, which leaves a sender on a closed socket that no
    // later stop reaches (fork plan, R63). BaseSender.stop() is safe on a sender that never
    // started, and on one the guard already stopped. Not cleared: the guard already applied [clear] to
    // a sender it stopped, and a client that was never streaming keeps the stream info and the
    // Mpeg2TsService the caller set.
    srtSender.stop(false)
  }

  fun reConnect(delay: Long) {
    reConnect(delay, null)
  }

  fun reConnect(delay: Long, backupUrl: String?) {
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

  @Throws(IOException::class)
  // GPX R65 — [connectScope] is the connect job's own scope and [gen] its generation: the loop ends
  // when either is gone, and ends only that job. It used to read and cancel the `scope` field, which
  // after a stop is the next connect's scope.
  private suspend fun handleServerPackets(connectScope: CoroutineScope, gen: Long) {
    var lastServerPacketTs = TimeUtils.getCurrentTimeMillis()
    while (connectScope.isActive && gate.isCurrent(gen) && isStreaming) {
      val error = runCatching {
        if (isAlive()) {
          //ignore packet after connect if tunneled to avoid spam idle
          handleMessages()
          lastServerPacketTs = TimeUtils.getCurrentTimeMillis()
        } else {
          onMainThread {
            connectChecker.onConnectionFailed("No response from server")
          }
          connectScope.cancel()
        }
      }.exceptionOrNull()
      if (error != null && ConnectionFailed.parse(error.validMessage()) != ConnectionFailed.TIMEOUT) {
        connectScope.cancel()
      } else if (error != null && serverSilenceTimeoutMs > 0 &&
        TimeUtils.getCurrentTimeMillis() - lastServerPacketTs >= serverSilenceTimeoutMs) {
        onMainThread {
          connectChecker.onConnectionFailed("No response from server")
        }
        connectScope.cancel()
      }
    }
  }

  /*
  Send a heartbeat to know if server is alive using Echo Protocol.
  Your firewall could block it.
 */
  private fun isAlive(): Boolean {
    val connected = socket?.isConnected() ?: false
    if (!checkServerAlive) {
      return connected
    }
    val reachable = socket?.isReachable() ?: false
    return if (connected && !reachable) false else connected
  }

  @Throws(IOException::class)
  private suspend fun handleMessages() {
    val responseBufferConclusion = socket?.readBuffer() ?: throw IOException("read buffer failed, socket disconnected")
    // GPX R7 — a packet arrived, so the server is responding. Reset the silence timer.
    lastInboundMs = nowMs()
    when(val srtPacket = SrtPacket.getSrtPacket(responseBufferConclusion)) {
      is DataPacket -> {
        //ignore
      }
      is ControlPacket -> {
        when (srtPacket) {
          is KeepAlive -> {
            commandsManager.writeKeepAlive(socket)
          }
          is Ack -> {
            val ackSequence = srtPacket.typeSpecificInformation
            val lastPacketSequence = srtPacket.lastAcknowledgedPacketSequenceNumber
            commandsManager.updateHandlingQueue(lastPacketSequence)
            if (ackSequence != 0) {
              rtt = srtPacket.rtt
              commandsManager.updateRtt(srtPacket.rtt, srtPacket.rttVariance)
              commandsManager.writeAck2(ackSequence, socket)
            }
          }
          is Nak -> {
            //packet lost reported, we should resend it
            val lostRanges = srtPacket.getNakRanges()
            this.packetsLost += srtPacket.getLostCount()
            packetsLostUnique += commandsManager.reSendPackets(lostRanges, socket)
          }
          is Shutdown -> {
            onMainThread {
              connectChecker.onConnectionFailed("Shutdown received from server")
            }
          }
          is PeerError -> {
            val reason = srtPacket.errorCode
            onMainThread {
              connectChecker.onConnectionFailed("PeerError: $reason")
            }
          }
          is Handshake -> {
            //this never should happen, handshake is already done
          }
          is Ack2, is DropReq, is CongestionWarning -> {
            //this never should happen
          }
        }
      }
    }
  }

  fun setAudioInfo(sampleRate: Int, isStereo: Boolean) {
    srtSender.setAudioInfo(sampleRate, isStereo)
  }

  fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
    Log.i(TAG, "send sps and pps")
    srtSender.setVideoInfo(sps, pps, vps)
  }

  fun sendVideo(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    if (!commandsManager.videoDisabled) {
      srtSender.sendMediaFrame(videoBuffer, info.toMediaFrameInfo(), MediaFrame.Type.VIDEO)
    }
  }

  fun sendAudio(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    if (!commandsManager.audioDisabled) {
      srtSender.sendMediaFrame(audioBuffer, info.toMediaFrameInfo(), MediaFrame.Type.AUDIO)
    }
  }

  @Throws(IllegalArgumentException::class)
  fun hasCongestion(): Boolean {
    return hasCongestion(20f)
  }

  @Throws(IllegalArgumentException::class)
  fun hasCongestion(percentUsed: Float): Boolean {
    return srtSender.hasCongestion(percentUsed)
  }

  fun resetSentAudioFrames() {
    srtSender.resetSentAudioFrames()
  }

  fun resetSentVideoFrames() {
    srtSender.resetSentVideoFrames()
  }

  fun resetDroppedAudioFrames() {
    srtSender.resetDroppedAudioFrames()
  }

  fun resetDroppedVideoFrames() {
    srtSender.resetDroppedVideoFrames()
  }

  fun resetBytesSend() {
    srtSender.resetBytesSend()
  }

  @Throws(RuntimeException::class)
  fun resizeCache(newSize: Int) {
    srtSender.resizeCache(newSize)
  }

  fun setLogs(enable: Boolean) {
    srtSender.setLogs(enable)
  }

  fun clearCache() {
    srtSender.clearCache()
  }

  fun getItemsInCache(): Int = srtSender.getItemsInCache()

  fun getQueueBytesOut(): Long = srtSender.getQueueBytesOut()

  /**
   * GPX R7 — milliseconds since the last inbound packet was read, or -1 when not streaming or
   * not yet established (GPX R60: a new connect clears the previous reading, so -1 holds from
   * connect start until the handshake completes). This client only exposes the reading, as
   * the WHIP client does (R42); it fails no connection on silence, the consumer judges it.
   * On a responding link this stays near zero, because SRT sends ACK and
   * KeepAlive control packets sub-second at any latency, and it grows once the server stops
   * responding. An outbound bytes-sent counter cannot show this, since sendto() keeps succeeding
   * into a blackhole.
   */
  fun getInboundSilenceMs(): Long =
    if (!isStreaming || lastInboundMs == 0L) -1L else nowMs() - lastInboundMs

  /**
   * @param factor values from 0.1f to 1f
   * Set an exponential factor to the bitrate calculation to avoid bitrate spikes
   */
  fun setBitrateExponentialFactor(factor: Float) {
    srtSender.setBitrateExponentialFactor(factor)
  }

  /**
   * Get the exponential factor used to calculate the bitrate. Default 1f
   */
  fun getBitrateExponentialFactor() = srtSender.getBitrateExponentialFactor()

  /**
   * Set a custom Mpeg2TsService with specified parameters
   * Must be called before connect
   *
   * @param customService the custom Mpeg2TsService with desired parameters
   */
  fun setMpeg2TsService(customService: Mpeg2TsService) {
    if (!isStreaming) {
      srtSender.setMpeg2TsService(customService)
    } else {
      Log.w(TAG, "Can't set custom Mpeg2TsService while streaming")
    }
  }
}