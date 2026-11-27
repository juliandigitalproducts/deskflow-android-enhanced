// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Auto-connect filter (DSK-R8): which discovered server may be switched to without asking. */
class AutoConnectSelectorTest {

  private val trustedFp = "AA:BB"

  private fun server(
    address: String,
    port: Int = 24800,
    verified: Boolean = true,
    useTls: Boolean = true,
    fingerprint: String? = null,
  ) = DiscoveredServer(address, port, null, verified, useTls, fingerprint)

  private fun select(servers: List<DiscoveredServer>, hasServer: Boolean, current: String = "10.0.0.9") =
    AutoConnectSelector.select(servers, setOf(trustedFp), hasServer, current, 24800)

  @Test
  fun `trusted fingerprint is chosen over unknown servers`() {
    val trusted = server("10.0.0.2", fingerprint = trustedFp)
    val result = select(listOf(server("10.0.0.1", fingerprint = "CC:DD"), trusted), hasServer = true)
    assertEquals(trusted, result)
  }

  @Test
  fun `untrusted servers are never chosen once a server is set up`() {
    val servers = listOf(server("10.0.0.1", fingerprint = "CC:DD"), server("10.0.0.3"))
    assertNull(select(servers, hasServer = true))
  }

  @Test
  fun `plaintext servers are never chosen, even before setup`() {
    val servers = listOf(server("10.0.0.1", useTls = false), server("10.0.0.2", useTls = false, fingerprint = trustedFp))
    assertNull(select(servers, hasServer = false))
  }

  @Test
  fun `before setup a verified TLS server may be picked`() {
    val verified = server("10.0.0.4")
    val result = select(listOf(server("10.0.0.5", verified = false), verified), hasServer = false)
    assertEquals(verified, result)
  }

  @Test
  fun `unverified open ports are not picked before setup`() {
    assertNull(select(listOf(server("10.0.0.5", verified = false)), hasServer = false))
  }

  @Test
  fun `the current server is preferred among trusted candidates`() {
    val first = server("10.0.0.2", fingerprint = trustedFp)
    val current = server("10.0.0.9", fingerprint = trustedFp)
    assertEquals(current, select(listOf(first, current), hasServer = true))
  }
}
