package com.pedro.common.base

import com.pedro.common.ConnectChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException

/**
 * GPX R61 — [BaseSender.reportSendError] is the one place a send error is reported. A stop clears
 * `running` before it closes anything, so an error that arrives after that is not a fault.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BaseSenderSendErrorTest {

  private val checker: ConnectChecker = mock()

  private inner class FakeSender : BaseSender(checker, "FakeSender") {
    override fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {}
    override fun setAudioInfo(sampleRate: Int, isStereo: Boolean) {}
    override suspend fun onRun() {}
    override suspend fun stopImp(clear: Boolean) {}

    fun markRunning(value: Boolean) { running = value }
    fun stillRunning() = running
    suspend fun report(error: Throwable) = reportSendError(error)
  }

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `a send error after the stop cleared running reports nothing`() = runBlocking {
    val sender = FakeSender()
    sender.markRunning(false)

    sender.report(ClosedChannelException())

    verify(checker, never()).onConnectionFailed(any())
  }

  @Test
  fun `a send error while running is reported once and ends the loop`() = runBlocking {
    val sender = FakeSender()
    sender.markRunning(true)

    sender.report(IOException("Network is unreachable"))

    verify(checker).onConnectionFailed("Error send packet, Network is unreachable")
    assertFalse("the send loop must end after a reported fault", sender.stillRunning())

    // a second error from the same dead loop is not a second report
    sender.report(IOException("Network is unreachable"))
    verify(checker).onConnectionFailed(any())
  }
}
