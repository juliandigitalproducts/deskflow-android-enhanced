// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.tfv.deskflow.client.Client
import org.tfv.deskflow.client.ClientEventBus
import org.tfv.deskflow.client.events.ClientEvent
import org.tfv.deskflow.client.events.ConnectionEvent
import org.tfv.deskflow.client.events.MouseEvent
import org.tfv.deskflow.client.events.ScreenEvent
import org.tfv.deskflow.client.models.ClipboardData
import org.tfv.deskflow.client.models.ServerTarget

/**
 * Connect flow on a device/emulator against the fake Deskflow server on the host (`./gradlew :client:runFakeServer`),
 * reached from the emulator as 10.0.2.2. Runs only when the instrumentation argument `harnessPort` is set
 * (docs/testing.md); otherwise it is skipped.
 */
@RunWith(AndroidJUnit4::class)
class ConnectFlowTest {

  private val args = InstrumentationRegistry.getArguments()
  private val events = CopyOnWriteArrayList<ClientEvent>()
  private val lock = Object()
  private val listener: (ClientEvent) -> Unit = { event ->
    events += event
    synchronized(lock) { lock.notifyAll() }
  }
  private var client: Client? = null

  @Before
  fun setUp() {
    assumeTrue("instrumentation argument harnessPort not set – fake server test skipped", args.containsKey("harnessPort"))
    ClientEventBus.on(listener)
  }

  @After
  fun tearDown() {
    client?.dispose()
    ClientEventBus.off(listener)
  }

  private fun awaitEvent(timeoutMs: Long = 10_000L, predicate: (ClientEvent) -> Boolean): ClientEvent {
    val deadline = System.currentTimeMillis() + timeoutMs
    synchronized(lock) {
      while (true) {
        events.firstOrNull(predicate)?.let { return it }
        val left = deadline - System.currentTimeMillis()
        if (left <= 0) throw AssertionError("Event not seen within $timeoutMs ms; got: $events")
        lock.wait(left)
      }
    }
  }

  @Test
  fun connectsToTheHarnessServerAndReceivesInputAndClipboard() {
    val host = args.getString("harnessHost") ?: "10.0.2.2"
    val port = args.getString("harnessPort")!!.toInt()
    client =
      Client(clipboardReceiveEnabledProvider = { true }).apply {
        setTarget(ServerTarget("Emulator-E2E", host, port, useTls = false, width = 1080, height = 2220))
      }
    awaitEvent { it == ConnectionEvent.Connected }
    awaitEvent { it == ScreenEvent.AckReceived }
    awaitEvent { it == ScreenEvent.Enter }
    awaitEvent { it == MouseEvent.move(100, 200) }
    val clip = awaitEvent { it is ScreenEvent.SetClipboard } as ScreenEvent.SetClipboard
    assertEquals("deskflow-e2e", clip.data.variants[ClipboardData.Format.Text]!!.data.toString(Charsets.UTF_8))
    assertEquals(0, events.count { it == ConnectionEvent.Disconnected })
  }
}
