// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.client.harness

import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.tfv.deskflow.client.Client
import org.tfv.deskflow.client.events.ConnectionEvent
import org.tfv.deskflow.client.net.ConnectionFailure

/**
 * U3 from the emulator test: a failing connection reports a reason (TLS handshake / not reachable) and how often it
 * failed in a row, instead of an endless "Connecting…".
 */
@Tag("harness")
class ConnectionFailureHarnessTest {

  private fun freePort(): Int = ServerSocket(0).use { it.localPort }

  private fun failures(events: ClientEventRecorder) =
    events.events.map { it.event }.filterIsInstance<ConnectionEvent.ConnectFailed>()

  @Test
  fun `a closed port is reported as unreachable and counted`() {
    ClientEventRecorder().use { events ->
      val client = Client()
      try {
        client.setTarget(harnessTarget(freePort()))
        events.await(8_000L) { it is ConnectionEvent.ConnectFailed && it.consecutiveFailures >= 2 }
        val seen = failures(events)
        assertTrue(seen.all { it.reason == ConnectionFailure.UNREACHABLE }, "reasons: $seen")
        assertEquals((1..seen.size).toList(), seen.map { it.consecutiveFailures })
      } finally {
        client.dispose()
      }
    }
  }

  @Test
  fun `TLS against a plaintext server is reported as a TLS handshake failure`() {
    FakeDeskflowServer().start().use { server ->
      ClientEventRecorder().use { events ->
        val client = Client()
        try {
          client.setTarget(harnessTarget(server.port).copy(useTls = true))
          val failed = events.await(8_000L) { it is ConnectionEvent.ConnectFailed }.event
          assertEquals(ConnectionFailure.TLS_HANDSHAKE, (failed as ConnectionEvent.ConnectFailed).reason)
          assertEquals(0, events.count { it == ConnectionEvent.Connected })
        } finally {
          client.dispose()
        }
      }
    }
  }

  @Test
  fun `a successful connection starts the count again`() {
    val port = freePort()
    ClientEventRecorder().use { events ->
      val client = Client()
      try {
        client.setTarget(harnessTarget(port))
        events.await(8_000L) { it is ConnectionEvent.ConnectFailed && it.consecutiveFailures >= 2 }
        FakeDeskflowServer(FakeDeskflowServer.Options(port = port)).start().use { server ->
          server.awaitConnection(10_000L).awaitHandshake()
          events.await { it == ConnectionEvent.Connected }
        }
        val afterServerStopped = events.events.size
        val next =
          events.await(10_000L, from = afterServerStopped) { it is ConnectionEvent.ConnectFailed }.event
        assertEquals(1, (next as ConnectionEvent.ConnectFailed).consecutiveFailures)
      } finally {
        client.dispose()
      }
    }
  }
}
