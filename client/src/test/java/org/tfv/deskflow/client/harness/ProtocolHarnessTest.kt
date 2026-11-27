// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.client.harness

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.tfv.deskflow.client.events.ConnectionEvent
import org.tfv.deskflow.client.events.KeyboardEvent
import org.tfv.deskflow.client.events.MouseEvent
import org.tfv.deskflow.client.events.ScreenEvent
import org.tfv.deskflow.client.harness.FakeDeskflowServer.Options
import org.tfv.deskflow.client.harness.FakeDeskflowServer.Proto
import org.tfv.deskflow.client.models.ClipboardData

/**
 * Drives the real protocol client (Client → FullDuplexSocket → MessageParser → MessageHandler) against
 * [FakeDeskflowServer] over a local socket. Run with `./gradlew :client:harnessTest` (docs/testing.md).
 */
@Tag("harness")
class ProtocolHarnessTest {

  private fun session(options: Options = Options(), clipboard: () -> Boolean = { true }) =
    HarnessSession(options, clipboard)

  @Nested
  inner class Handshake {

    @Test
    fun `client answers the Deskflow hello with protocol 1_6, its screen name and its screen size`() {
      session().use { s ->
        // The server's handshake thread consumes the packets; inspect the recorded ones afterwards.
        val conn = s.connected()
        val helloBack = conn.allPackets.first { it.code == Proto.HELLO_BACK_CODE }
        helloBack.input().run {
          assertEquals(1, readShort().toInt())
          assertEquals(6, readShort().toInt())
        }
        assertEquals(HARNESS_SCREEN_NAME, Proto.readHelloBackName(helloBack))
        val info = conn.allPackets.first { it.code == "DINF" }.input()
        info.readShort() // x
        info.readShort() // y
        assertEquals(HARNESS_WIDTH, info.readShort().toInt())
        assertEquals(HARNESS_HEIGHT, info.readShort().toInt())
        s.events.await { it == ConnectionEvent.Connected }
        s.events.await { it == ScreenEvent.AckReceived }
      }
    }

    @Test
    fun `a Synergy-style server hello is answered as well`() {
      session(Options(helloName = "Synergy")).use { s -> s.connected() }
    }

    @Test
    fun `input after entering the screen reaches the app as events`() {
      session().use { s ->
        val conn = s.connected()
        conn.send(Proto.enter())
        conn.send(Proto.mouseMove(321, 654))
        conn.send(Proto.keyDown(id = 0x61, modifiers = 0, button = 38))
        s.events.await { it == ScreenEvent.Enter }
        s.events.await { it == MouseEvent.move(321, 654) }
        s.events.await { it is KeyboardEvent && it.type == KeyboardEvent.Type.Down && it.id == 0x61u }
      }
    }

    @Test
    fun `a server with another major version is rejected and not retried at once`() {
      session(Options(majorVersion = 2, autoHandshake = false)).use { s ->
        val conn = s.server.awaitConnection()
        conn.sendHello()
        s.events.await { it == ConnectionEvent.Rejected }
        assertTrue(conn.awaitClosedByClient(3_000L), "client keeps the connection to an incompatible server")
        // Rejected → 10 s pause before the next attempt.
        assertEquals(null, s.server.pollConnection(3_000L), "client reconnected right after a reject")
      }
    }
  }

  @Nested
  inner class KeepAlive {

    @Test
    fun `client answers every CALV and the connection stays up`() {
      session(Options(keepAliveIntervalMs = 1_000L)).use { s ->
        val conn = s.connected()
        Thread.sleep(4_500L)
        assertFalse(conn.isClosed, "connection dropped although keep-alives arrived")
        val answers = conn.allPackets.count { it.code == "CALV" }
        assertTrue(answers >= 4, "client answered only $answers keep-alives")
      }
    }

    @Test
    fun `without CALV the client closes the connection about 7 s after the last one, then reconnects`() {
      session(Options(keepAliveIntervalMs = 1_000L)).use { s ->
        val conn = s.connected()
        Thread.sleep(2_500L)
        conn.stopKeepAlive()
        val lastCalv = conn.lastKeepAliveSentAtMs
        assertTrue(conn.awaitClosedByClient(12_000L), "client never gave up on the silent server")
        val timeoutMs = conn.closedByClientAtMs!! - lastCalv
        println("HARNESS keep-alive timeout measured: $timeoutMs ms (MessageHandler.KEEP_ALIVE_TIMEOUT = 7000)")
        assertTrue(timeoutMs in 7_000L..8_500L, "keep-alive timeout was $timeoutMs ms")
        s.events.await { it == ConnectionEvent.Disconnected }
        s.server.awaitConnection(5_000L).awaitHandshake()
        assertEquals(1, s.events.count { it == ConnectionEvent.Disconnected })
      }
    }

    @Test
    fun `a 6 s gap between keep-alives is tolerated`() {
      session(Options(keepAliveIntervalMs = 3_000L)).use { s ->
        val conn = s.connected()
        Thread.sleep(500L)
        conn.stopKeepAlive()
        Thread.sleep(6_000L)
        conn.startKeepAlive(3_000L)
        Thread.sleep(1_500L)
        assertFalse(conn.isClosed, "a 6 s gap dropped the connection")
      }
    }

    /**
     * Open question (report, Deskflow 7 s vs 9 s): Deskflow sends CALV every 3 s and only gives up after three
     * missed ones (9 s). This client gives up after 7 s, so a gap Deskflow itself tolerates drops the connection.
     * The test documents the current behaviour; change it together with KEEP_ALIVE_TIMEOUT.
     */
    @Test
    fun `open question - an 8 s gap that Deskflow tolerates drops the connection today`() {
      session(Options(keepAliveIntervalMs = 3_000L)).use { s ->
        val conn = s.connected()
        Thread.sleep(500L)
        conn.stopKeepAlive()
        Thread.sleep(8_000L)
        conn.startKeepAlive(3_000L)
        assertTrue(conn.awaitClosedByClient(1_500L), "an 8 s gap no longer drops the connection – update the report")
      }
    }
  }

  @Nested
  inner class DisconnectAndReconnect {

    @Test
    fun `server closing the socket gives exactly one Disconnected and a reconnect within the backoff`() {
      session().use { s ->
        val first = s.connected()
        s.events.await { it == ScreenEvent.AckReceived }
        first.close()
        val disconnected = s.events.await { it == ConnectionEvent.Disconnected }
        s.server.awaitConnection(3_000L).awaitHandshake()
        val reconnected = s.events.events.indexOf(disconnected)
        s.events.await(from = reconnected) { it == ScreenEvent.AckReceived }
        assertEquals(1, s.events.count { it == ConnectionEvent.Disconnected })
      }
    }

    @Test
    fun `CBYE makes the client close and reconnect`() {
      session().use { s ->
        val first = s.connected()
        first.send(Proto.CBYE)
        assertTrue(first.awaitClosedByClient(3_000L), "client ignored CBYE")
        s.events.await { it == ConnectionEvent.Disconnected }
        s.server.awaitConnection(3_000L).awaitHandshake()
      }
    }

    @Test
    fun `client keeps retrying while the server is down and connects when it is back`() {
      val port = FakeDeskflowServer().use { it.port }
      HarnessSession(Options(port = port)).use { s ->
        s.connected()
        s.server.close()
        s.events.await { it == ConnectionEvent.Disconnected }
        Thread.sleep(3_000L)
        FakeDeskflowServer(Options(port = port)).start().use { restarted ->
          // Backoff is at most 8 s after ~3 s of failed attempts (1 + 2 + 4 …).
          restarted.awaitConnection(10_000L).awaitHandshake()
        }
      }
    }
  }

  @Nested
  inner class MalformedPackets {

    private fun assertDropsAndReconnects(frame: ByteArray) {
      session().use { s ->
        val conn = s.connected()
        conn.send(frame)
        assertTrue(conn.awaitClosedByClient(3_000L), "client kept a connection with a malformed length field")
        s.server.awaitConnection(3_000L).awaitHandshake()
        assertEquals(1, s.events.count { it == ConnectionEvent.Disconnected })
      }
    }

    @Test
    fun `negative length field - disconnect and reconnect`() = assertDropsAndReconnects(Proto.rawFrame(-1, ByteArray(0)))

    @Test
    fun `length field above 16 MiB - disconnect and reconnect`() =
      assertDropsAndReconnects(Proto.rawFrame(16 * 1024 * 1024 + 1, "DMMV".toByteArray()))

    @Test
    fun `length field below the 4-byte code - disconnect and reconnect`() =
      assertDropsAndReconnects(Proto.rawFrame(2, "CA".toByteArray()))

    @Test
    fun `unknown code and truncated message are dropped, the connection stays`() {
      session().use { s ->
        val conn = s.connected()
        conn.send(Proto.enter())
        conn.send(Proto.message("ZZZZ") { writeInt(42) })
        conn.send(Proto.message("DMMV") { writeShort(1) }) // y is missing
        conn.send(Proto.mouseMove(10, 20))
        s.events.await { it == MouseEvent.move(10, 20) }
        assertFalse(conn.isClosed)
        assertEquals(0, s.events.count { it == ConnectionEvent.Disconnected })
      }
    }

    @Test
    fun `messages split into single bytes are reassembled`() {
      session().use { s ->
        val conn = s.connected()
        conn.send(Proto.enter())
        conn.sendFragmented(Proto.mouseMove(777, 888) + Proto.mouseMove(1, 2), chunkSize = 1, pauseMs = 2L)
        s.events.await { it == MouseEvent.move(777, 888) }
        s.events.await { it == MouseEvent.move(1, 2) }
      }
    }

    @Test
    fun `clipboard start with a huge length is dropped without crash`() {
      session().use { s ->
        val conn = s.connected()
        conn.send(Proto.enter())
        conn.send(Proto.message("CCLP") { writeByte(0); writeInt(1) })
        conn.send(Proto.message("DCLP") { writeByte(0); writeInt(1); writeByte(1); writeInt(Int.MAX_VALUE) })
        conn.send(Proto.message("DCLP") { writeByte(0); writeInt(1); writeByte(2); writeInt(3); write("abc".toByteArray()) })
        conn.send(Proto.message("DCLP") { writeByte(0); writeInt(1); writeByte(3); writeInt(0) })
        conn.send(Proto.mouseMove(5, 6))
        s.events.await { it == MouseEvent.move(5, 6) }
        assertTrue(s.events.noneWithin(500L) { it is ScreenEvent.SetClipboard })
        assertFalse(conn.isClosed)
      }
    }
  }

  @Nested
  inner class Clipboard {

    private fun FakeDeskflowServer.Connection.sendClipboard(text: String) =
      Proto.clipboardText(text).forEach { send(it) }

    @Test
    fun `sharing on - text from the server reaches the app`() {
      session(clipboard = { true }).use { s ->
        val conn = s.connected()
        conn.send(Proto.enter())
        conn.sendClipboard("Grüße vom Mac")
        val event = s.events.await { it is ScreenEvent.SetClipboard }.event as ScreenEvent.SetClipboard
        assertEquals("Grüße vom Mac", event.data.variants[ClipboardData.Format.Text]!!.data.toString(Charsets.UTF_8))
      }
    }

    @Test
    fun `sharing off - clipboard data from the server is not collected`() {
      session(clipboard = { false }).use { s ->
        val conn = s.connected()
        conn.send(Proto.enter())
        conn.sendClipboard("secret")
        conn.send(Proto.mouseMove(9, 9))
        s.events.await { it == MouseEvent.move(9, 9) }
        assertTrue(s.events.noneWithin(1_000L) { it is ScreenEvent.SetClipboard })
      }
    }

    @Test
    fun `clipboard data while the screen is not entered is ignored`() {
      session(clipboard = { true }).use { s ->
        val conn = s.connected()
        conn.sendClipboard("not for this screen")
        assertTrue(s.events.noneWithin(1_000L) { it is ScreenEvent.SetClipboard })
      }
    }

    @Test
    fun `text copied on the device is announced at once and sent when the cursor leaves the screen`() {
      session().use { s ->
        val conn = s.connected()
        conn.send(Proto.enter())
        s.events.await { it == ScreenEvent.Enter }
        val text = "vom Tablet ✓"
        val bytes = text.toByteArray(Charsets.UTF_8)
        s.client.sendClipboard(
          ClipboardData(0, 0, mapOf(ClipboardData.Format.Text to ClipboardData.Variant(ClipboardData.Format.Text, bytes)))
        )
        conn.awaitPacket("CCLP")
        conn.awaitPacket("CCLP")
        conn.send(Proto.COUT)
        // DCLP for clipboard 0: start, data, end. The data chunk holds: count, format, size, bytes.
        val chunks = List(3) { conn.awaitPacket("DCLP") }
        val data = chunks[1].input()
        assertEquals(0, data.readByte().toInt()) // clipboard id
        data.readInt() // sequence
        assertEquals(2, data.readByte().toInt()) // data marker
        data.readInt() // chunk length
        assertEquals(1, data.readInt()) // one format
        assertEquals(ClipboardData.Format.Text.code, data.readInt())
        val size = data.readInt()
        assertEquals(text, String(ByteArray(size).also { data.readFully(it) }, Charsets.UTF_8))
      }
    }
  }
}
