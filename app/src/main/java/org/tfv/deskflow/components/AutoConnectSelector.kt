// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.components

/** Picks the server auto discovery may switch to; kept free of Android types so it can be unit tested. */
object AutoConnectSelector {

  /**
   * Only servers whose certificate the user confirmed ([trustedFingerprints]) are chosen automatically.
   * Before any server is set up ([hasServer] false), a verified TLS Deskflow server may be picked as well –
   * the connection then stops at the fingerprint dialog. Plaintext servers and unverified open ports are
   * never chosen: they cannot prove who they are. The current server is preferred when it is a candidate.
   */
  fun select(
    servers: List<DiscoveredServer>,
    trustedFingerprints: Set<String>,
    hasServer: Boolean,
    currentAddress: String,
    currentPort: Int,
  ): DiscoveredServer? {
    val candidates =
      servers
        .filter { it.useTls && it.fingerprint != null && it.fingerprint in trustedFingerprints }
        .ifEmpty { if (!hasServer) servers.filter { it.verified && it.useTls } else emptyList() }
    return candidates.firstOrNull { it.address == currentAddress && it.port == currentPort }
      ?: candidates.firstOrNull()
  }
}
