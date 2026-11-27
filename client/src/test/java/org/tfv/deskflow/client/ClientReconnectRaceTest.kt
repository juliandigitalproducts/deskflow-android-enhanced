// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tfv.deskflow.client.events.ConnectionEvent
import org.tfv.deskflow.client.harness.ClientEventRecorder
import org.tfv.deskflow.client.harness.FakeDeskflowServer
import org.tfv.deskflow.client.harness.harnessTarget
import org.tfv.deskflow.client.util.SingletonThreadExecutor

/**
 * DSK-R14: the end of an old socket is handled later on the connection executor. When the user switched to
 * another server in between, that late task closed the new connection (reconnect only after the retry delay).
 */
class ClientReconnectRaceTest {

  /** Holds the client's connection thread so tasks queue up in a known order. */
  private fun Client.blockExecutor(): CountDownLatch {
    val field = Client::class.java.getDeclaredField("connectionExecutor").apply { isAccessible = true }
    val executor = field.get(this) as SingletonThreadExecutor
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    executor.submit {
      started.countDown()
      release.await(10, TimeUnit.SECONDS)
    }
    assertTrue(started.await(5, TimeUnit.SECONDS))
    return release
  }

  @Test
  fun `a late end of the old socket does not close the connection to the new server`() {
    FakeDeskflowServer().start().use { serverA ->
      FakeDeskflowServer().start().use { serverB ->
        ClientEventRecorder().use { events ->
          val client = Client()
          try {
            client.setTarget(harnessTarget(serverA.port))
            val connA = serverA.awaitConnection().awaitHandshake()

            val release = client.blockExecutor()
            // Queued first: the switch to server B …
            client.setTarget(harnessTarget(serverB.port))
            // … then server A ends the old connection; its end is queued behind the switch.
            connA.close()
            Thread.sleep(500L)
            release.countDown()

            val connB = serverB.awaitConnection().awaitHandshake()
            // The old socket's late event must be ignored: no second connection to B, B stays open.
            assertNull(serverB.pollConnection(2_500L), "the connection to server B was dropped and re-opened")
            assertTrue(!connB.isClosed, "the connection to server B was closed")
            assertEquals(1, serverB.acceptedCount.get())
            // One Disconnected for the switch away from A, none for B.
            assertEquals(1, events.count { it == ConnectionEvent.Disconnected })
          } finally {
            client.dispose()
          }
        }
      }
    }
  }
}
