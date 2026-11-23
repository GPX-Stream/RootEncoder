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

package com.pedro.rtmp.rtmp

import android.media.MediaCodec
import android.util.Log
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.ConnectionFailed
import com.pedro.common.TimeUtils
import com.pedro.common.UrlParser
import com.pedro.common.VideoCodec
import com.pedro.common.base.ConnectGate
import com.pedro.common.frame.MediaFrame
import com.pedro.common.onMainThread
import com.pedro.common.socket.base.SocketType
import com.pedro.common.socket.base.StreamSocket
import com.pedro.common.toMediaFrameInfo
import com.pedro.common.validMessage
import com.pedro.rtmp.rtmp.message.Abort
import com.pedro.rtmp.rtmp.message.Acknowledgement
import com.pedro.rtmp.rtmp.message.Aggregate
import com.pedro.rtmp.rtmp.message.Command
import com.pedro.rtmp.rtmp.message.MessageType
import com.pedro.rtmp.rtmp.message.SetChunkSize
import com.pedro.rtmp.rtmp.message.SetPeerBandwidth
import com.pedro.rtmp.rtmp.message.WindowAcknowledgementSize
import com.pedro.rtmp.rtmp.message.control.Type
import com.pedro.rtmp.rtmp.message.control.UserControl
import com.pedro.rtmp.utils.AuthUtil
import com.pedro.rtmp.utils.socket.RtmpSocket
import com.pedro.rtmp.utils.socket.TcpSocket
import com.pedro.rtmp.utils.socket.TcpTunneledSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.URISyntaxException
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.TrustManager
import kotlin.time.Duration.Companion.milliseconds

/**
 * Created by pedro on 8/04/21.
 */
class RtmpClient(private val connectChecker: ConnectChecker) {

  private val TAG = "RtmpClient"

  private val validSchemes = arrayOf("rtmp", "rtmps", "rtmpt", "rtmpts")

  @Volatile
  private var socket: RtmpSocket? = null
  private var scope = CoroutineScope(Dispatchers.IO)
  private var scopeRetry = CoroutineScope(Dispatchers.IO)
  private var scopePing = CoroutineScope(Dispatchers.IO)
  private var job: Job? = null
  private var jobRetry: Job? = null

  // GPX R65 — the stop count, the generation and the deferred connect (fork plan, R65). Never held
  // across a suspension or a callback.
  private val gate = ConnectGate()
  // GPX R65 — one teardown at a time. Taken only by coroutines on IO, never by a caller of
  // connect(), so nothing on the main thread waits for it.
  private val teardownLock = Mutex()
  private val commandsManager = CommandsManagerImp()
  private val rtmpSender = RtmpSender(connectChecker, commandsManager)

  @Volatile
  var isStreaming = false
    private set

  private var url: String? = null
  private var tlsEnabled = false
  private var certificates: TrustManager? = null
  private var tunneled = false

  private var doingRetry = false
  private var numRetry = 0
  private var reTries = 0
  private var checkServerAlive = false
  private var publishPermitted = false
  private var ignoredCommandReceived: ((String) -> Unit)? = null

  val droppedAudioFrames: Long
    get() = rtmpSender.getDroppedAudioFrames()
  val droppedVideoFrames: Long
    get() = rtmpSender.getDroppedVideoFrames()

  val cacheSize: Int
    get() = rtmpSender.getCacheSize()
  val sentAudioFrames: Long
    get() = rtmpSender.getSentAudioFrames()
  val sentVideoFrames: Long
    get() = rtmpSender.getSentVideoFrames()
  val bytesSend: Long
    get() = rtmpSender.getBytesSend()
  var socketType = SocketType.JAVA
  var tlsHostVerification = false
  var socketTimeout = StreamSocket.DEFAULT_TIMEOUT
  var shouldFailOnRead = false
  var shouldSendPings = false
  var rtt = 0 //in micro
    private set
  private val pingTs = AtomicLong(0)

  /**
   * Add certificates for TLS connection
   */
  fun addCertificates(certificates: TrustManager?) {
    this.certificates = certificates
  }

  fun setVideoCodec(videoCodec: VideoCodec) {
    if (!isStreaming) {
      commandsManager.videoCodec = videoCodec
    }
  }

  fun setAudioCodec(audioCodec: AudioCodec) {
    if (!isStreaming) {
      commandsManager.audioCodec = audioCodec
    }
  }

  fun setIgnoredCommandCallback(callback: ((String) -> Unit)?) {
    ignoredCommandReceived = callback
  }

  /**
   * Check periodically if server is alive using Echo protocol.
   */
  fun setCheckServerAlive(enabled: Boolean) {
    checkServerAlive = enabled
  }

  fun shouldSendPings(enabled: Boolean) {
    shouldSendPings = enabled
  }

  /**
   * Returns the active direct RTMP Java socket for read-only diagnostics.
   *
   * For RTMPS connections, the returned object is an SSLSocket. The socket can become stale
   * during reconnect. Callers must not read from, write to, close, or reconfigure it because the
   * RTMP client owns all socket I/O. Returns null for tunneled or non-Java sockets.
   */
  fun getJavaSocket(): Socket? = (socket as? TcpSocket)?.getJavaSocket()

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

  fun forceIncrementalTs(enabled: Boolean) {
    if (enabled) setDelay(300)
  }

  fun setDelay(millis: Long) {
    rtmpSender.setDelay(millis)
  }

  fun setWriteChunkSize(chunkSize: Int) {
    if (!isStreaming) {
      commandsManager.config.writeChunkSize = chunkSize
    }
  }

  fun setCustomAmfObject(amfObject: Map<String, Any>) {
    commandsManager.customAmfObject = amfObject
  }

  fun setCustomMetadata(metadata: Map<String, Any>) {
    commandsManager.customMetadata = metadata
  }

  fun setAuthorization(user: String?, password: String?) {
    commandsManager.setAuth(user, password)
  }

  fun setReTries(reTries: Int) {
    numRetry = reTries
    this.reTries = reTries
  }

  fun shouldRetry(reason: String): Boolean {
    val validReason = doingRetry && !reason.contains("Endpoint malformed")
    return validReason && reTries > 0
  }

  fun setAudioInfo(sampleRate: Int, isStereo: Boolean) {
    commandsManager.setAudioInfo(sampleRate, isStereo)
    rtmpSender.setAudioInfo(sampleRate, isStereo)
  }

  fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
    Log.i(TAG, "send sps and pps")
    rtmpSender.setVideoInfo(sps, pps, vps)
  }

  fun setVideoResolution(width: Int, height: Int) {
    commandsManager.setVideoResolution(width, height)
  }

  fun setFps(fps: Int) {
    commandsManager.fps = fps
  }

  fun setFlashVersion(flashVersion: String) {
    commandsManager.flashVersion = flashVersion
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
            connectChecker.onConnectionFailed(
              "Endpoint malformed, should be: rtmp://ip:port/appname/streamname"
            )
          }
          return@launch
        }
        this@RtmpClient.url = url
        onMainThread {
          connectChecker.onConnectionStarted(url)
        }

        val urlParser = try {
          UrlParser.parse(url, validSchemes)
        } catch (_: URISyntaxException) {
          connectFailed(gen)
          onMainThread {
            connectChecker.onConnectionFailed(
              "Endpoint malformed, should be: rtmp://ip:port/appname/streamname")
          }
          return@launch
        }

        tunneled = urlParser.scheme.startsWith("rtmpt")
        tlsEnabled = urlParser.scheme.endsWith("s")
        commandsManager.host = urlParser.host
        val defaultPort = if (tlsEnabled) 443 else if (tunneled) 80 else 1935
        commandsManager.port = urlParser.port ?: defaultPort
        commandsManager.appName = urlParser.getAppName()
        commandsManager.streamName = urlParser.getStreamName()
        commandsManager.tcUrl = urlParser.getTcUrl()
        if (commandsManager.appName.isEmpty()) {
          connectFailed(gen)
          onMainThread {
            connectChecker.onConnectionFailed(
              "Endpoint malformed, should be: rtmp://ip:port/appname/streamname")
          }
          return@launch
        }

        val user = urlParser.getAuthUser()
        val password = urlParser.getAuthPassword()
        if (user != null && password != null) setAuthorization(user, password)

        val error = runCatching {
          if (!establishConnection(gen)) {
            onMainThread {
              connectChecker.onConnectionFailed("Handshake failed")
            }
            return@launch
          }
          val socket = this@RtmpClient.socket ?: throw IOException("Invalid socket, Connection failed")
          commandsManager.sendChunkSize(socket)
          commandsManager.sendConnect("", socket)
          //read packets until you did success connection to server and you are ready to send packets
          while (isActive && gate.isCurrent(gen) && !publishPermitted) {
            //Handle all command received and send response for it.
            handleMessages(gen)
          }
          if (shouldSendPings) {
            pingTs.set(TimeUtils.getCurrentTimeMicro())
            commandsManager.sendPing(socket)
          }
          //read packet because maybe server want send you something while streaming
          handleServerPackets(this@launch, gen)
        }.exceptionOrNull()
        if (error != null) {
          // GPX R65 — a cancelled or stale attempt ends quietly.
          if (error is CancellationException) throw error
          if (!gate.isCurrent(gen)) return@launch
          Log.e(TAG, "connection error", error)
          onMainThread {
            connectChecker.onConnectionFailed("Error configure stream, ${error.validMessage()}")
          }
          return@launch
        }
      }
    }
  }

  // GPX R65 — [connectScope] is the connect job's own scope and [gen] its generation: the loop ends
  // when either is gone, and ends only that job. It used to read and cancel the `scope` field, which
  // after a stop is the next connect's scope. A job that was cancelled reports nothing: the error is
  // the stop closing the socket, not a fault.
  private suspend fun handleServerPackets(connectScope: CoroutineScope, gen: Long) {
    while (connectScope.isActive && gate.isCurrent(gen) && isStreaming) {
      val error = runCatching {
        if (isAlive()) {
          //ignore packet after connect if tunneled to avoid spam idle
          if (!tunneled) handleMessages(gen)
        } else {
          onMainThread {
            connectChecker.onConnectionFailed("No response from server")
          }
          connectScope.cancel()
        }
      }.exceptionOrNull()
      if (error != null && ConnectionFailed.parse(error.validMessage()) != ConnectionFailed.TIMEOUT) {
        val stopped = error is CancellationException || !connectScope.isActive || !gate.isCurrent(gen)
        connectScope.cancel()
        if (shouldFailOnRead && !stopped) connectChecker.onConnectionFailed(error.validMessage())
      }
    }
  }

  /*
    Send a heartbeat to know if server is alive using Echo Protocol.
    Your firewall could block it.
   */
  private fun isAlive(): Boolean {
    val connected = socket?.isConnected() ?: false
    if (!checkServerAlive) return connected
    val reachable = socket?.isReachable() ?: false
    return if (connected && !reachable) false else connected
  }

  @Throws(IOException::class)
  private suspend fun establishConnection(gen: Long): Boolean {
    val socket = if (tunneled) {
      TcpTunneledSocket(commandsManager.host, commandsManager.port, tlsEnabled)
    } else {
      TcpSocket(socketType, commandsManager.host, commandsManager.port, tlsEnabled, socketTimeout, tlsHostVerification, certificates)
    }
    // GPX R65 — only a current connect may publish its socket; a stale one closes its own and ends,
    // so a cancelled attempt never leaves the media socket open.
    val held = gate.locked { gate.isCurrent(gen).also { if (it) this.socket = socket } }
    if (!held) {
      withContext(NonCancellable) { runCatching { socket.close() } }
      throw CancellationException("connect superseded by a stop")
    }
    socket.connect()
    // GPX R65 — the stop may have closed this socket before the connect finished, which closes
    // nothing; a connect that completes after the stop's cancel or on a stale generation closes its
    // own socket here.
    if (!currentCoroutineContext().isActive || !gate.isCurrent(gen)) {
      withContext(NonCancellable) { runCatching { socket.close() } }
      throw CancellationException("connect superseded by a stop")
    }
    if (!socket.isConnected()) return false
    val timestamp = TimeUtils.getCurrentTimeMillis() / 1000
    val handshake = Handshake()
    if (!handshake.sendHandshake(socket)) return false
    commandsManager.timestamp = timestamp.toInt()
    commandsManager.startTs = TimeUtils.getCurrentTimeNano() / 1000
    return true
  }

  /**
   * Read all messages from server and response to it
   */
  @Throws(IOException::class)
  private suspend fun handleMessages(gen: Long) {
    // GPX R65 — a stale connect must not read the next connect's socket or answer on it.
    if (!gate.isCurrent(gen)) throw CancellationException("connect superseded by a stop")
    var socket = this.socket ?: throw IOException("Invalid socket, Connection failed")

    val message = commandsManager.readMessageResponse(socket)
    commandsManager.checkAndSendAcknowledgement(socket)
    when (message.getType()) {
      MessageType.SET_CHUNK_SIZE -> {
        val setChunkSize = message as SetChunkSize
        commandsManager.config.readChunkSize = setChunkSize.chunkSize
        Log.i(TAG, "chunk size configured to ${setChunkSize.chunkSize}")
      }
      MessageType.ACKNOWLEDGEMENT -> {
        val acknowledgement = message as Acknowledgement
      }
      MessageType.WINDOW_ACKNOWLEDGEMENT_SIZE -> {
        val windowAcknowledgementSize = message as WindowAcknowledgementSize
        commandsManager.config.acknowledgementWindowSize = windowAcknowledgementSize.acknowledgementWindowSize
      }
      MessageType.SET_PEER_BANDWIDTH -> {
        val setPeerBandwidth = message as SetPeerBandwidth
        commandsManager.sendWindowAcknowledgementSize(socket)
      }
      MessageType.ABORT -> {
        val abort = message as Abort
      }
      MessageType.AGGREGATE -> {
        val aggregate = message as Aggregate
      }
      MessageType.USER_CONTROL -> {
        val userControl = message as UserControl
        when (val type = userControl.type) {
          Type.PING_REQUEST -> {
            commandsManager.sendPong(userControl.event, socket)
          }
          Type.PONG_REPLY -> {
            Log.i(TAG, "pong received: ${userControl.event.data}")
            if (shouldSendPings) {
              rtt = (TimeUtils.getCurrentTimeMicro() - pingTs.get()).toInt()
              scopePing.launch {
                delay(1000.milliseconds)
                if (isStreaming) {
                  pingTs.set(TimeUtils.getCurrentTimeMicro())
                  commandsManager.sendPing(socket)
                }
              }
            }
          }
          else -> {
            Log.i(TAG, "user control command $type ignored")
          }
        }
      }
      MessageType.COMMAND_AMF0, MessageType.COMMAND_AMF3 -> {
        val command = message as Command
        val commandName = commandsManager.sessionHistory.getName(command.commandId)
        when (command.name) {
          "_result" -> {
            when (commandName) {
              "connect" -> {
                if (commandsManager.onAuth) {
                  onMainThread { connectChecker.onAuthSuccess() }
                  commandsManager.onAuth = false
                }
                commandsManager.createStream(socket)
              }
              "createStream" -> {
                try {
                  commandsManager.streamId = command.getStreamId()
                  commandsManager.sendPublish(socket)
                } catch (e: Exception) {
                  Log.e(TAG, "error parsing _result createStream", e)
                }
              }
            }
            Log.i(TAG, "success response received from ${commandName ?: "unknown command"}")
          }
          "_error" -> {
            try {
              val description = command.getDescription()
              when (commandName) {
                "connect" -> {
                  if (description.contains("reason=authfail") || description.contains("reason=nosuchuser")) {
                    onMainThread {
                      connectChecker.onAuthError()
                    }
                  } else if ((commandsManager.user != null && commandsManager.password != null) &&
                    ((description.contains("challenge=") && description.contains("salt=")) //adobe response
                      || description.contains("nonce="))) { //llnw response
                    closeConnection()
                    establishConnection(gen)
                    socket = this.socket ?: throw IOException("Invalid socket, Connection failed")
                    commandsManager.onAuth = true
                    if (description.contains("challenge=") && description.contains("salt=")) { //create adobe auth
                      val salt = AuthUtil.getSalt(description)
                      val challenge = AuthUtil.getChallenge(description)
                      val opaque = AuthUtil.getOpaque(description)
                      commandsManager.sendConnect(AuthUtil.getAdobeAuthUserResult(commandsManager.user
                          ?: "", commandsManager.password ?: "",
                          salt, challenge, opaque), socket)
                    } else if (description.contains("nonce=")) { //create llnw auth
                      val nonce = AuthUtil.getNonce(description)
                      commandsManager.sendConnect(AuthUtil.getLlnwAuthUserResult(commandsManager.user
                          ?: "", commandsManager.password ?: "",
                          nonce, commandsManager.appName), socket)
                    }
                  } else if (description.contains("code=403")) {
                    if (description.contains("authmod=adobe")) {
                      closeConnection()
                      establishConnection(gen)
                      socket = this.socket ?: throw IOException("Invalid socket, Connection failed")
                      Log.i(TAG, "sending auth mode adobe")
                      commandsManager.sendConnect("?authmod=adobe&user=${commandsManager.user}", socket)
                    } else if (description.contains("authmod=llnw")) {
                      Log.i(TAG, "sending auth mode llnw")
                      commandsManager.sendConnect("?authmod=llnw&user=${commandsManager.user}", socket)
                    }
                  } else {
                    onMainThread {
                      connectChecker.onAuthError()
                    }
                  }
                }
                //We can ignore this errors. Some servers fail if this stream is not in use or don't implement this methods.
                "releaseStream", "FCPublish" -> {
                  Log.e(TAG, "$commandName failed: $description")
                }
                else -> {
                  onMainThread {
                    connectChecker.onConnectionFailed(description)
                  }
                }
              }
            } catch (e: Exception) {
              Log.e(TAG, "error parsing _error command", e)
            }
          }
          "onStatus" -> {
            try {
              when (val code = command.getCode()) {
                "NetStream.Publish.Start" -> {
                  commandsManager.sendMetadata(socket)
                  onMainThread {
                    connectChecker.onConnectionSuccess()
                  }
                  // GPX R65 — the stop cancels this job first, so a cancelled job stops here; the
                  // generation covers a job that outlived the stop's capped wait.
                  if (!currentCoroutineContext().isActive || !gate.isCurrent(gen)) return
                  rtmpSender.socket = socket
                  rtmpSender.start()
                  publishPermitted = true
                }
                "NetConnection.Connect.Rejected", "NetStream.Publish.BadName", "NetConnection.Connect.Closed", "NetStream.Publish.Failed" -> {
                  onMainThread {
                    connectChecker.onConnectionFailed("onStatus: $code")
                  }
                }
                else -> {
                  val message = "onStatus $code response received from ${commandName ?: "unknown command"}"
                  Log.i(TAG, message)
                  ignoredCommandReceived?.let { onMainThread { it.invoke(message) } }
                }
              }
            } catch (e: Exception) {
              Log.e(TAG, "error parsing onStatus command", e)
            }
          }
          else -> {
            val message = "unknown ${command.name} response received from ${commandName ?: "unknown command"}"
            Log.i(TAG, message)
            ignoredCommandReceived?.let { onMainThread { it.invoke(message) } }
          }
        }
      }
      MessageType.VIDEO, MessageType.AUDIO, MessageType.DATA_AMF0, MessageType.DATA_AMF3,
      MessageType.SHARED_OBJECT_AMF0, MessageType.SHARED_OBJECT_AMF3 -> {
        Log.e(TAG, "unimplemented response for ${message.getType()}. Ignored")
      }
    }
  }

  private suspend fun closeConnection() {
    runCatching { socket?.close() } // GPX R65 — a throw here must not end the teardown
    commandsManager.reset()
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
            if (clear) {
              // a throw part-way through the teardown must not leave the client streaming or the
              // retry and ping scopes cancelled
              isStreaming = false
              if (!scopeRetry.isActive) scopeRetry = CoroutineScope(Dispatchers.IO)
              if (!scopePing.isActive) scopePing = CoroutineScope(Dispatchers.IO)
              gate.endStop { connectLocked(it, false) }
            }
          }
        }
      }
    }
  }

  private suspend fun teardown(clear: Boolean, connectScope: CoroutineScope) {
    if (isStreaming) rtmpSender.stop(clear, unlockNeeded = { socket?.close() })
    runCatching {
      withTimeoutOrNull(100.milliseconds) {
        socket?.let { commandsManager.sendClose(it) }
      }
    }
    closeConnection()
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
      scopePing.cancel()
      scopePing = CoroutineScope(Dispatchers.IO)
    }
    // GPX R65 — wait for every connect job of the scope, not the one in the `job` field, with a
    // capped wait: a job stuck in a call that ignores cancellation cannot hang the stop, and its
    // generation is stale, so it writes nothing when it wakes.
    withTimeoutOrNull(ConnectGate.CONNECT_JOIN_TIMEOUT_MS.milliseconds) { connectScope.coroutineContext.job.join() }
    job = null
    // GPX R64 — stop the sender again, now that the connect jobs are gone, whether or not it was
    // running when the guard above read it (fork plan, R64). Before R65 the connect job was
    // cancelled only here and could start the sender after that guard; now the stop cancels it
    // when it begins, so this stop is the safety net for a job that was already past its
    // cancellation checks. BaseSender.stop() is safe on a sender that never
    // started, and on one the guard already stopped. Not cleared: the guard already applied [clear] to
    // a sender it stopped, and a client that was never streaming keeps the stream info the caller set.
    rtmpSender.stop(false, unlockNeeded = { socket?.close() })
    publishPermitted = false
    commandsManager.reset()
    rtt = 0
  }

  fun sendVideo(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    if (!commandsManager.videoDisabled) {
      rtmpSender.sendMediaFrame(videoBuffer, info.toMediaFrameInfo(), MediaFrame.Type.VIDEO)
    }
  }

  fun sendAudio(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    if (!commandsManager.audioDisabled) {
      rtmpSender.sendMediaFrame(audioBuffer, info.toMediaFrameInfo(), MediaFrame.Type.AUDIO)
    }
  }

  @JvmOverloads
  @Throws(IllegalArgumentException::class)
  fun hasCongestion(percentUsed: Float = 20f): Boolean {
    return rtmpSender.hasCongestion(percentUsed)
  }

  fun resetSentAudioFrames() {
    rtmpSender.resetSentAudioFrames()
  }

  fun resetSentVideoFrames() {
    rtmpSender.resetSentVideoFrames()
  }

  fun resetDroppedAudioFrames() {
    rtmpSender.resetDroppedAudioFrames()
  }

  fun resetDroppedVideoFrames() {
    rtmpSender.resetDroppedVideoFrames()
  }

  fun resetBytesSend() {
    rtmpSender.resetBytesSend()
  }

  @Throws(RuntimeException::class)
  fun resizeCache(newSize: Int) {
    rtmpSender.resizeCache(newSize)
  }

  fun setLogs(enable: Boolean) {
    rtmpSender.setLogs(enable)
  }

  fun clearCache() {
    rtmpSender.clearCache()
  }

  fun getItemsInCache(): Int = rtmpSender.getItemsInCache()

  fun getQueueBytesOut(): Long = rtmpSender.getQueueBytesOut()

  /**
   * @param factor values from 0.1f to 1f
   * Set an exponential factor to the bitrate calculation to avoid bitrate spikes
   */
  fun setBitrateExponentialFactor(factor: Float) {
    rtmpSender.setBitrateExponentialFactor(factor)
  }

  /**
   * Get the exponential factor used to calculate the bitrate. Default 1f
   */
  fun getBitrateExponentialFactor() = rtmpSender.getBitrateExponentialFactor()
}
