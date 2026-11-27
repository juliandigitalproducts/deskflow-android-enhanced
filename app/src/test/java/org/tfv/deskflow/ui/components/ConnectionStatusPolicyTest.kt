// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tfv.deskflow.client.net.ConnectionFailure

class ConnectionStatusPolicyTest {

  private fun of(
    enabled: Boolean = true,
    connected: Boolean = false,
    awaitingTrust: Boolean = false,
    failure: ConnectionFailure? = null,
    attempts: Int = 0,
  ) =
    ConnectionStatusPolicy.of(
      isEnabled = enabled,
      isConnected = connected,
      awaitingTrust = awaitingTrust,
      failureKind = failure?.code ?: ConnectionFailure.NONE_CODE,
      failedAttempts = attempts,
    )

  @Test
  fun `stopped and connected win over any failure`() {
    assertEquals(ConnectionStatus.STOPPED, of(enabled = false, failure = ConnectionFailure.TLS_HANDSHAKE, attempts = 9))
    assertEquals(ConnectionStatus.CONNECTED, of(connected = true, failure = ConnectionFailure.TLS_HANDSHAKE, attempts = 9))
  }

  @Test
  fun `the first failed attempts still show connecting`() {
    assertEquals(ConnectionStatus.CONNECTING, of())
    assertEquals(ConnectionStatus.CONNECTING, of(failure = ConnectionFailure.UNREACHABLE, attempts = 2))
  }

  @Test
  fun `from the third attempt on the reason is shown`() {
    assertEquals(ConnectionStatus.TLS_FAILED, of(failure = ConnectionFailure.TLS_HANDSHAKE, attempts = 3))
    assertEquals(ConnectionStatus.UNREACHABLE, of(failure = ConnectionFailure.UNREACHABLE, attempts = 4))
    assertEquals(ConnectionStatus.FAILED, of(failure = ConnectionFailure.OTHER, attempts = 10))
  }

  @Test
  fun `a server waiting for the fingerprint check is not an error`() {
    assertEquals(
      ConnectionStatus.CONNECTING,
      of(awaitingTrust = true, failure = ConnectionFailure.TLS_HANDSHAKE, attempts = 5),
    )
  }

  @Test
  fun `only failure states carry a detail text`() {
    for (status in ConnectionStatus.entries) {
      val expected = status in setOf(ConnectionStatus.TLS_FAILED, ConnectionStatus.UNREACHABLE, ConnectionStatus.FAILED)
      if (expected) assertTrue(status.name, status.isError) else assertFalse(status.name, status.isError)
    }
  }
}
