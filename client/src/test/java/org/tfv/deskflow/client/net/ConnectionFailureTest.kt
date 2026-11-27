// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.client.net

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ConnectionFailureTest {

  @Test
  fun `TLS errors are reported as handshake failures`() {
    assertEquals(ConnectionFailure.TLS_HANDSHAKE, ConnectionFailure.classify(SSLException("Unable to parse TLS packet header")))
    assertEquals(ConnectionFailure.TLS_HANDSHAKE, ConnectionFailure.classify(SSLHandshakeException("bad cert")))
    assertEquals(ConnectionFailure.TLS_HANDSHAKE, ConnectionFailure.classify(IOException("wrapped", SSLException("x"))))
  }

  @Test
  fun `refused, unroutable, unknown and silent servers are unreachable`() {
    for (err in listOf(
      ConnectException("Connection refused"),
      NoRouteToHostException("No route"),
      UnknownHostException("nas.local"),
      SocketTimeoutException("timeout"),
      UnresolvedAddressException(),
      IOException("connect timeout after 10 s"),
    )) {
      assertEquals(ConnectionFailure.UNREACHABLE, ConnectionFailure.classify(err), err.toString())
    }
  }

  @Test
  fun `anything else is other`() {
    assertEquals(ConnectionFailure.OTHER, ConnectionFailure.classify(IOException("Broken pipe")))
    assertEquals(ConnectionFailure.OTHER, ConnectionFailure.classify(IllegalStateException("x")))
  }

  @Test
  fun `a cause cycle does not loop forever`() {
    val a = IOException("a")
    val b = IOException("b", a)
    a.initCause(b)
    assertEquals(ConnectionFailure.OTHER, ConnectionFailure.classify(a))
  }

  @Test
  fun `codes round trip and 0 means no failure`() {
    for (f in ConnectionFailure.entries) assertEquals(f, ConnectionFailure.fromCode(f.code))
    assertNull(ConnectionFailure.fromCode(ConnectionFailure.NONE_CODE))
  }
}
