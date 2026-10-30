package com.pedro.library.util

import android.media.MediaFormat
import android.util.Log
import com.pedro.common.AudioCodec
import com.pedro.common.VideoCodec
import com.pedro.common.frame.MediaFrame
import com.pedro.encoder.video.VideoEncoderHelper
import com.pedro.library.base.recording.AsyncBaseRecordController
import com.pedro.library.base.recording.RecordController
import com.pedro.library.base.recording.RecordController.RecordTracks
import com.pedro.srt.mpeg2ts.MpegTsPacket
import com.pedro.srt.mpeg2ts.MpegTsPacketizer
import com.pedro.srt.mpeg2ts.MpegType
import com.pedro.srt.mpeg2ts.packets.AacPacket
import com.pedro.srt.mpeg2ts.packets.BasePacket
import com.pedro.srt.mpeg2ts.packets.H264Packet
import com.pedro.srt.mpeg2ts.packets.H265Packet
import com.pedro.srt.mpeg2ts.packets.OpusPacket
import com.pedro.srt.mpeg2ts.psi.Psi
import com.pedro.srt.mpeg2ts.psi.PsiManager
import com.pedro.srt.mpeg2ts.service.Mpeg2TsService
import com.pedro.srt.srt.packets.SrtPacket
import com.pedro.srt.srt.packets.data.PacketPosition
import com.pedro.srt.utils.Constants
import com.pedro.srt.utils.chunkPackets
import com.pedro.srt.utils.toCodec
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer

class Mpeg2TsMuxerRecordController : AsyncBaseRecordController() {

  private var outputStream: OutputStream? = null

  //metadata config
  private var service = Mpeg2TsService()
  private val psiManager = PsiManager(service).apply {
    upgradePatVersion()
    upgradeSdtVersion()
  }
  private val limitSize: Int
    get() {
      return Constants.MTU - SrtPacket.headerSize
    }

  private val mpegTsPacketizer = MpegTsPacketizer(psiManager)
  private var audioPacket: BasePacket = AacPacket(limitSize, psiManager)
  private var videoPacket: BasePacket = H264Packet(limitSize, psiManager)
  private val chunkSize = limitSize / MpegTsPacketizer.packetSize
  private var sampleRate = 0
  private var isStereo = true
  private var sendInfo = false
  // GPX R59 — true from the start of a file until its first keyframe is written.
  @Volatile
  private var waitingForFirstKeyFrame = true
  // GPX R12 — the first write failure of a recording is reported once.
  @Volatile
  private var writeErrorNotified = false

  @Throws(IOException::class)
  override fun startRecordImp(
    path: String,
    listener: RecordController.Listener?,
    tracks: RecordTracks
  ) {
    outputStream = FileOutputStream(path)
    start(listener, tracks)
  }

  @Throws(IOException::class)
  override fun startRecordImp(
    fd: FileDescriptor,
    listener: RecordController.Listener?,
    tracks: RecordTracks
  ) {
    outputStream = FileOutputStream(fd)
    start(listener, tracks)
  }

  @Throws(IOException::class)
  private fun start(listener: RecordController.Listener?, tracks: RecordTracks) {
    // GPX R12 — a new recording may report its own first write failure.
    writeErrorNotified = false
    audioPacket = when (getAudioCodec()) {
      AudioCodec.AAC, AudioCodec.HE_AAC -> AacPacket(limitSize, psiManager).apply { sendAudioInfo(sampleRate, isStereo, getAudioCodec()) }
      AudioCodec.OPUS -> OpusPacket(limitSize, psiManager)
      else -> {
        throw IOException("Unsupported AudioCodec: " + getAudioCodec().name)
      }
    }
    videoPacket = when (getVideoCodec()) {
      VideoCodec.H264 -> H264Packet(limitSize, psiManager)
      VideoCodec.H265 -> H265Packet(limitSize, psiManager)
      else -> {
        throw IOException("Unsupported VideoCodec: " + getVideoCodec().name)
      }
    }
    sendInfo = false
    waitingForFirstKeyFrame = true
    outputStream?.let {
      val videoEnabled = tracks == RecordTracks.VIDEO || tracks == RecordTracks.ALL
      val audioEnabled = tracks == RecordTracks.AUDIO || tracks == RecordTracks.ALL
      setTrackConfig(videoEnabled, audioEnabled)

      val psiList = mutableListOf<Psi>(psiManager.getPat())
      psiManager.getPmt()?.let { psiList.add(0, it) }
      psiList.add(psiManager.getSdt())
      val psiPacketsConfig = mpegTsPacketizer.write(psiList).chunkPackets(chunkSize).map { buffer ->
        MpegTsPacket(buffer, MpegType.PSI, PacketPosition.SINGLE, isKey = false)
      }
      writePackets(psiPacketsConfig)
    }
    if (tracks == RecordTracks.AUDIO) recordStatus = RecordController.Status.RECORDING
    listener?.onStatusChange(recordStatus)
  }

  private fun setTrackConfig(videoEnabled: Boolean, audioEnabled: Boolean) {
    service.clear()
    if (audioEnabled) service.addTrack(getAudioCodec().toCodec())
    if (videoEnabled) service.addTrack(getVideoCodec().toCodec())
    service.generatePmt()
    psiManager.updateService(service)
  }

  override fun stopRecordImp() {
    psiManager.reset()
    mpegTsPacketizer.reset()
    audioPacket.reset(false)
    videoPacket.reset(false)
    try {
      outputStream?.close()
    } catch (_: Exception) {
    } finally {
      outputStream = null
    }
    sendInfo = false
  }

  private suspend fun getMpegTsPackets(
    mediaFrame: MediaFrame?,
    callback: suspend (List<MpegTsPacket>) -> Unit
  ) {
    if (mediaFrame == null) return
    when (mediaFrame.type) {
      MediaFrame.Type.VIDEO -> videoPacket.createAndSendPacket(mediaFrame) { callback(it) }
      MediaFrame.Type.AUDIO -> audioPacket.createAndSendPacket(mediaFrame) { callback(it) }
    }
  }

  private fun writePackets(mpegTsPackets: List<MpegTsPacket>) {
    try {
      outputStream?.let { outputStream ->
        mpegTsPackets.forEach { mpegTsPacket ->
          outputStream.write(mpegTsPacket.buffer)
          // GPX R12 — every byte that reaches the file passes through here (PSI, video and audio),
          // so getBytesWritten() is the exact size of the file on disk.
          countBytesWritten(mpegTsPacket.buffer.size)
        }
      }
    } catch (e: Exception) {
      // GPX R12 — a write failure has to be visible. Swallowing it meant a wedged or full disk
      // nothing for the rest of the session with no report. First failure only, because a dead
      // output fails once per frame.
      if (!writeErrorNotified) {
        writeErrorNotified = true
        listener?.onError(e)
      }
    }
  }

  override suspend fun onWriteFrame(frame: MediaFrame) {
    when (frame.type) {
      MediaFrame.Type.VIDEO -> {
        if (tracks != RecordTracks.AUDIO) {
          // GPX R59 — a file opens on a keyframe. Delta frames before the first keyframe cannot be
          // decoded on their own, and the keyframe that supplied the codec headers is written
          // instead of being consumed by getVideoInfo.
          val isKey = frame.info.isKeyFrame || isKeyFrame(frame.data)
          if (recordStatus == RecordController.Status.STARTED) {
            getVideoInfo(frame.data, frame.info.size, isKey)
          }
          if (recordStatus == RecordController.Status.RECORDING) {
            if (isKey) {
              // the packetizer reads the flag from the frame info, so a keyframe found only by its NAL type is flagged
              val keyFrame = if (frame.info.isKeyFrame) frame else frame.copy(info = frame.info.copy(isKeyFrame = true))
              waitingForFirstKeyFrame = false
              writeMpeg2TsPacket(keyFrame)
            } else if (!waitingForFirstKeyFrame) {
              writeMpeg2TsPacket(frame)
            }
          }
        }
      }
      MediaFrame.Type.AUDIO -> {
        // GPX R59 — a file that carries video starts with the video keyframe, so audio waits for it too
        // (setVideoFormat can move the status to RECORDING before any keyframe arrived).
        val waitingForVideo = tracks != RecordTracks.AUDIO && waitingForFirstKeyFrame
        if (recordStatus == RecordController.Status.RECORDING && tracks != RecordTracks.VIDEO && !waitingForVideo) {
          writeMpeg2TsPacket(frame)
        }
      }
    }
  }

  private suspend fun writeMpeg2TsPacket(frame: MediaFrame) {
    getMpegTsPackets(frame) { mpegTsPackets ->
      val isKey = mpegTsPackets[0].isKey
      val psiPackets = psiManager.checkSendInfo(isKey, mpegTsPacketizer, chunkSize)
      writePackets(psiPackets)
      writePackets(mpegTsPackets)
    }
  }

  // GPX R59 — the keyframe verdict and size come from the caller, which also writes the keyframe.
  private fun getVideoInfo(buffer: ByteBuffer, size: Int, isKey: Boolean) {
    if (isKey) {
      if (!sendInfo) {
        when (videoPacket) {
          is H264Packet -> {
            val buffers = extractParameterSets(buffer.duplicate(), size, intArrayOf(7, 8)) { (it.toInt() and 0x1F) }
            if (buffers != null) {
              Log.i(TAG, "manual sps/pps extraction success")
              val oldSps = buffers[0]
              val oldPps = buffers[1]
              (videoPacket as H264Packet).sendVideoInfo(oldSps, oldPps)
              sendInfo = true
            } else {
              Log.e(TAG, "manual sps/pps extraction failed")
            }
          }

          is H265Packet -> {
            // GPX R59 — by NAL type, so the sets end where the next one starts
            val byteBufferList = extractParameterSets(buffer.duplicate(), size, intArrayOf(32, 33, 34)) { (it.toInt() shr 1) and 0x3F }
            if (byteBufferList != null) {
              Log.i(TAG, "manual vps/sps/pps extraction success")
              val oldSps = byteBufferList[1]
              val oldPps = byteBufferList[2]
              val oldVps = byteBufferList[0]
              (videoPacket as H265Packet).sendVideoInfo(oldSps, oldPps, oldVps)
              sendInfo = true
            } else {
              Log.e(TAG, "manual vps/sps/pps extraction failed")
            }
          }

          else -> {
            Log.e(TAG, "Unsupported codec: ${videoPacket.javaClass.name}")
          }
        }
      }
      if (sendInfo && recordStatus == RecordController.Status.STARTED) {
        myRequestKeyFrame = null
        recordStatus = RecordController.Status.RECORDING
        listener?.onStatusChange(recordStatus)
      }
    } else if (myRequestKeyFrame != null) {
      myRequestKeyFrame?.onRequestKeyFrame()
      myRequestKeyFrame = null
    }
  }

  /**
   * GPX R59 — the parameter sets of a keyframe buffer, picked by NAL unit type. Each one keeps its
   * start code and ends at the next start code. [typeOf] reads the NAL unit type from the first
   * header byte. VideoEncoderHelper's two extractors end the last set at the end of the buffer, which
   * swallows the picture that follows it (H.264: the PPS holds the IDR slice; H.265: the SPS holds the
   * PPS and the PPS holds the IDR slice), and they return types a plain JVM test cannot read.
   * Null when one of [types] is missing.
   */
  private fun extractParameterSets(buffer: ByteBuffer, size: Int, types: IntArray, typeOf: (Byte) -> Int): List<ByteBuffer>? {
    val csd = ByteArray(minOf(size, buffer.remaining()))
    buffer.get(csd)
    // [start of the start code, first byte after it] of every NAL unit
    val nals = mutableListOf<IntArray>()
    var i = 0
    while (i < csd.size - 3) {
      if (csd[i] == 0.toByte() && csd[i + 1] == 0.toByte() && csd[i + 2] == 1.toByte()) {
        val startCode = if (i > 0 && csd[i - 1] == 0.toByte()) i - 1 else i
        nals.add(intArrayOf(startCode, i + 3))
        i += 3
      } else {
        i++
      }
    }
    return types.map { type ->
      val index = nals.indexOfFirst { it[1] < csd.size && typeOf(csd[it[1]]) == type }
      if (index < 0) return null
      val end = if (index + 1 < nals.size) nals[index + 1][0] else csd.size
      ByteBuffer.wrap(csd.copyOfRange(nals[index][0], end))
    }
  }

  override fun setVideoFormat(videoFormat: MediaFormat) {
    when (videoPacket) {
      is H264Packet -> {
        val sps = videoFormat.getByteBuffer("csd-0")
        val pps = videoFormat.getByteBuffer("csd-1")
        if (sps != null && pps != null) {
          (videoPacket as H264Packet).sendVideoInfo(sps.duplicate(), pps.duplicate())
          sendInfo = true
        }
      }

      is H265Packet -> {
        val bufferInfo = videoFormat.getByteBuffer("csd-0")
        if (bufferInfo != null) {
          val byteBufferList = VideoEncoderHelper.extractVpsSpsPpsFromH265(bufferInfo.duplicate())
          if (byteBufferList.size == 3) {
            val sps = byteBufferList[1]
            val pps = byteBufferList[2]
            val vps = byteBufferList[0]
            (videoPacket as H265Packet).sendVideoInfo(sps, pps, vps)
            sendInfo = true
          }
        }
      }

      else -> {
        Log.e(TAG, "Unsupported codec: ${videoPacket.javaClass.name}")
      }
    }
    if (sendInfo && recordStatus == RecordController.Status.STARTED) {
      myRequestKeyFrame = null
      recordStatus = RecordController.Status.RECORDING
      listener?.onStatusChange(recordStatus)
    }
  }

  override fun setAudioFormat(audioFormat: MediaFormat) {
    val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    val channels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    this.sampleRate = sampleRate
    this.isStereo = channels > 1
    (audioPacket as? AacPacket)?.sendAudioInfo(sampleRate, isStereo, getAudioCodec())
  }

  override fun resetFormats() {
  }
}