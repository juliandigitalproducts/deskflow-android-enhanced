// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.client.net

import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Real TLS handshakes against a local server with a self-signed certificate. */
class PinningTlsTest {

  private lateinit var server: SSLServerSocket
  private lateinit var serverFingerprint: String

  @BeforeEach
  fun startServer() {
    val keyStore = KeyStore.getInstance("PKCS12")
    javaClass.getResourceAsStream("/test-server.p12").use {
      keyStore.load(it, "testpass".toCharArray())
    }
    serverFingerprint =
      CertificateFingerprint.sha256(keyStore.getCertificate("server") as X509Certificate)
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(keyStore, "testpass".toCharArray())
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(kmf.keyManagers, null, null)
    server = ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
    thread(isDaemon = true) {
      while (!server.isClosed) {
        try {
          val s = server.accept()
          thread(isDaemon = true) {
            try {
              s.soTimeout = 5000
              s.outputStream.write(byteArrayOf(0, 0, 0, 11) + "Barrier".toByteArray() + byteArrayOf(0, 1, 0, 6))
              s.outputStream.flush()
              Thread.sleep(300)
            } catch (_: Exception) {
            } finally {
              s.close() // server hangs up -> client must stop without deadlock
            }
          }
        } catch (_: Exception) {}
      }
    }
  }

  @AfterEach
  fun stopServer() {
    server.close()
  }

  private class Result {
    val connected = CountDownLatch(1)
    val received = CountDownLatch(1)
    val ended = CountDownLatch(1)
    @Volatile var untrusted: String? = null
  }

  private fun connect(trusted: Set<String>): Pair<FullDuplexSocket, Result> {
    val result = Result()
    val tm =
      PinningTrustManager(
        isTrusted = { it in trusted },
        onUntrusted = { result.untrusted = it },
      )
    val socket = FullDuplexSocket("127.0.0.1", server.localPort, true, null, tm)
    socket.on { event ->
      when (event) {
        is FullDuplexSocket.SocketEvent.ConnectEvent -> result.connected.countDown()
        is FullDuplexSocket.SocketEvent.ReceiveEvent -> result.received.countDown()
        is FullDuplexSocket.SocketEvent.DisconnectEvent,
        is FullDuplexSocket.SocketEvent.ErrorEvent -> result.ended.countDown()
      }
    }
    socket.start()
    return socket to result
  }

  @Test
  fun `unknown certificate is refused and its fingerprint reported`() {
    val (socket, result) = connect(trusted = emptySet())
    assertTrue(result.ended.await(10, TimeUnit.SECONDS), "socket should end")
    assertFalse(result.connected.await(0, TimeUnit.MILLISECONDS) && result.received.count == 0L)
    assertEquals(1L, result.received.count, "no data may be delivered from an untrusted server")
    assertEquals(serverFingerprint, result.untrusted)
    socket.dispose()
  }

  @Test
  fun `pinned certificate connects, receives data and ends cleanly when server hangs up`() {
    val (socket, result) = connect(trusted = setOf(serverFingerprint))
    assertTrue(result.connected.await(10, TimeUnit.SECONDS), "should connect")
    assertTrue(result.received.await(10, TimeUnit.SECONDS), "should receive the hello")
    assertEquals(null, result.untrusted)
    // Server closes after 300 ms; stop() runs on the socket thread itself.
    assertTrue(result.ended.await(10, TimeUnit.SECONDS), "socket thread must not deadlock")
    socket.dispose()
  }

  @Test
  fun `fingerprint formatting is normalised`() {
    assertEquals("AB:CD:EF:01", CertificateFingerprint.normalize("abcdef01"))
    assertEquals("AB:CD:EF:01", CertificateFingerprint.normalize("ab:cd:ef:01"))
    assertEquals("AB:CD:EF:01", CertificateFingerprint.normalize(" AB CD EF 01 "))
  }
}
