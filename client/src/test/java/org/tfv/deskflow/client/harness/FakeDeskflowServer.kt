// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.client.harness

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A minimal Deskflow/Synergy server (plain TCP) for driving the real protocol client end-to-end.
 *
 * Wire format: every message is a 4-byte big-endian length followed by a code (4 ASCII chars, or "Barrier"/"Synergy"
 * for the hellos) and the fields. The handshake follows Deskflow: Hello → HelloBack → QINF → DINF → CIAK, CROP,
 * DSOP, then CALV every [Options.keepAliveIntervalMs]. The server does not interpret anything else; tests send
 * and inspect raw messages through [Connection].
 */
class FakeDeskflowServer(private val options: Options = Options()) : AutoCloseable {

  data class Options(
    val port: Int = 0,
    val bindAddress: String = "127.0.0.1",
    /** Protocol name in the server hello: "Barrier" (Deskflow) or "Synergy" (Synergy-style servers). */
    val helloName: String = "Barrier",
    val majorVersion: Int = 1,
    val minorVersion: Int = 8,
    /** CALV interval after the handshake; null = the server never sends keep-alives. Deskflow uses 3 s. */
    val keepAliveIntervalMs: Long? = 3_000L,
    /** false = only accept; the test drives the handshake itself. */
    val autoHandshake: Boolean = true,
  )

  /** A message as it arrived from the client. */
  class Packet(val code: String, val payload: ByteArray, val receivedAtMs: Long) {
    fun input() = DataInputStream(payload.inputStream())

    override fun toString() = "Packet($code, ${payload.size} bytes)"
  }

  private val serverSocket = ServerSocket(options.port, 50, InetAddress.getByName(options.bindAddress))
  private val scheduler = Executors.newScheduledThreadPool(1) { r -> Thread(r, "FakeServerKeepAlive").apply { isDaemon = true } }
  private val acceptThread = Thread(::acceptLoop, "FakeServerAccept").apply { isDaemon = true }

  val port: Int
    get() = serverSocket.localPort

  /** Every accepted connection, in order. */
  val connections = CopyOnWriteArrayList<Connection>()
  private val newConnections = LinkedBlockingQueue<Connection>()
  val acceptedCount = AtomicInteger(0)

  fun start(): FakeDeskflowServer = apply { acceptThread.start() }

  /** Waits for the next accepted connection. */
  fun awaitConnection(timeoutMs: Long = 5_000L): Connection =
    newConnections.poll(timeoutMs, TimeUnit.MILLISECONDS)
      ?: throw AssertionError("No client connected within $timeoutMs ms")

  fun pollConnection(timeoutMs: Long): Connection? = newConnections.poll(timeoutMs, TimeUnit.MILLISECONDS)

  private fun acceptLoop() {
    while (!serverSocket.isClosed) {
      val socket =
        try {
          serverSocket.accept()
        } catch (_: IOException) {
          break
        }
      socket.tcpNoDelay = true
      val connection = Connection(socket, acceptedCount.incrementAndGet())
      connections += connection
      newConnections += connection
      connection.start()
    }
  }

  override fun close() {
    try {
      serverSocket.close()
    } catch (_: IOException) {}
    connections.forEach { it.close() }
    scheduler.shutdownNow()
  }

  inner class Connection(private val socket: Socket, val index: Int) {
    private val out = DataOutputStream(socket.getOutputStream())
    private val packets = LinkedBlockingQueue<Packet>()
    val allPackets = CopyOnWriteArrayList<Packet>()
    private val handshakeLatch = CountDownLatch(1)
    private val closedLatch = CountDownLatch(1)
    @Volatile private var keepAliveFuture: ScheduledFuture<*>? = null

    /** Time the client closed the connection (EOF/reset seen by the server), or null. */
    @Volatile var closedByClientAtMs: Long? = null
      private set

    /** Time the server sent its last CALV. */
    @Volatile var lastKeepAliveSentAtMs: Long = 0L
      private set

    /** Screen name from the client's HelloBack. */
    @Volatile var screenName: String? = null
      private set

    val isClosed: Boolean
      get() = closedLatch.count == 0L

    internal fun start() {
      Thread(::readLoop, "FakeServerConn$index").apply { isDaemon = true }.start()
      if (options.autoHandshake) Thread(::handshake, "FakeServerHandshake$index").apply { isDaemon = true }.start()
    }

    private fun handshake() {
      try {
        sendHello()
        val helloBack = awaitPacket(Proto.HELLO_BACK_CODE)
        screenName = Proto.readHelloBackName(helloBack)
        send(Proto.QINF)
        awaitPacket("DINF")
        send(Proto.CIAK)
        send(Proto.CROP)
        send(Proto.message("DSOP") { writeInt(0) })
        options.keepAliveIntervalMs?.let { startKeepAlive(it) }
        handshakeLatch.countDown()
      } catch (_: Throwable) {
        // Connection closed during the handshake – tests see it through awaitHandshake().
      }
    }

    fun sendHello(name: String = options.helloName, major: Int = options.majorVersion, minor: Int = options.minorVersion) =
      send(Proto.hello(name, major, minor))

    fun awaitHandshake(timeoutMs: Long = 5_000L): Connection = apply {
      if (!handshakeLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
        throw AssertionError("Handshake with connection $index not finished within $timeoutMs ms")
      }
    }

    fun startKeepAlive(intervalMs: Long) {
      stopKeepAlive()
      keepAliveFuture =
        scheduler.scheduleAtFixedRate(
          {
            try {
              send(Proto.CALV)
              lastKeepAliveSentAtMs = System.currentTimeMillis()
            } catch (_: IOException) {
              stopKeepAlive()
            }
          },
          0,
          intervalMs,
          TimeUnit.MILLISECONDS,
        )
    }

    fun stopKeepAlive() {
      keepAliveFuture?.cancel(false)
      keepAliveFuture = null
    }

    @Synchronized
    fun send(bytes: ByteArray) {
      out.write(bytes)
      out.flush()
    }

    /** Sends [bytes] in pieces of [chunkSize] with a pause in between (fragmented TCP reads on the client). */
    fun sendFragmented(bytes: ByteArray, chunkSize: Int, pauseMs: Long = 5L) {
      bytes.toList().chunked(chunkSize).forEach {
        send(it.toByteArray())
        Thread.sleep(pauseMs)
      }
    }

    /** Next packet with [code]; earlier packets with other codes are skipped (they stay in [allPackets]). */
    fun awaitPacket(code: String, timeoutMs: Long = 5_000L): Packet {
      val deadline = System.currentTimeMillis() + timeoutMs
      while (true) {
        val left = deadline - System.currentTimeMillis()
        val packet = (if (left > 0) packets.poll(left, TimeUnit.MILLISECONDS) else null)
          ?: throw AssertionError("No $code from the client within $timeoutMs ms (received: $allPackets)")
        if (packet.code == code) return packet
      }
    }

    fun awaitClosedByClient(timeoutMs: Long): Boolean = closedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun close() {
      stopKeepAlive()
      try {
        socket.close()
      } catch (_: IOException) {}
    }

    private fun readLoop() {
      val input = DataInputStream(socket.getInputStream())
      try {
        while (true) {
          val size = input.readInt()
          require(size in 4..(16 * 1024 * 1024)) { "client sent an invalid message size $size" }
          val data = ByteArray(size)
          input.readFully(data)
          val code = if (String(data, 0, 4, Charsets.US_ASCII) == "Syne") Proto.HELLO_BACK_CODE else String(data, 0, 4, Charsets.US_ASCII)
          val codeLength = if (code == Proto.HELLO_BACK_CODE) Proto.HELLO_BACK_CODE.length else 4
          val packet = Packet(code, data.copyOfRange(codeLength, data.size), System.currentTimeMillis())
          allPackets += packet
          packets += packet
        }
      } catch (_: EOFException) {
        closedByClientAtMs = System.currentTimeMillis()
      } catch (_: IOException) {
        if (!socket.isClosed) closedByClientAtMs = System.currentTimeMillis()
      } finally {
        stopKeepAlive()
        closedLatch.countDown()
      }
    }
  }

  /** Encoders for server → client messages. */
  object Proto {
    const val HELLO_BACK_CODE = "Synergy"

    fun message(code: String, body: DataOutputStream.() -> Unit = {}): ByteArray {
      val bodyBytes = ByteArrayOutputStream().also { DataOutputStream(it).body() }.toByteArray()
      val codeBytes = code.toByteArray(Charsets.US_ASCII)
      return ByteArrayOutputStream().also {
        DataOutputStream(it).apply {
          writeInt(codeBytes.size + bodyBytes.size)
          write(codeBytes)
          write(bodyBytes)
        }
      }.toByteArray()
    }

    /** A frame with an arbitrary length field (malformed-packet tests). */
    fun rawFrame(lengthField: Int, content: ByteArray): ByteArray =
      ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(lengthField); write(content) } }.toByteArray()

    fun hello(name: String, major: Int, minor: Int) = message(name) { writeShort(major); writeShort(minor) }

    val QINF = message("QINF")
    val CIAK = message("CIAK")
    val CROP = message("CROP")
    val CALV = message("CALV")
    val CBYE = message("CBYE")
    val COUT = message("COUT")

    fun enter(x: Int = 0, y: Int = 0, sequence: Int = 1, modifiers: Int = 0) =
      message("CINN") { writeShort(x); writeShort(y); writeInt(sequence); writeShort(modifiers) }

    fun mouseMove(x: Int, y: Int) = message("DMMV") { writeShort(x); writeShort(y) }

    fun keyDown(id: Int, modifiers: Int, button: Int) =
      message("DKDN") { writeShort(id); writeShort(modifiers); writeShort(button) }

    /** CCLP + DCLP start/data/end, encoded like Deskflow (format count, format, size, bytes). */
    fun clipboardText(text: String, id: Int = 0, sequence: Int = 1): List<ByteArray> {
      val textBytes = text.toByteArray(Charsets.UTF_8)
      val raw = ByteArrayOutputStream().also {
        DataOutputStream(it).apply { writeInt(1); writeInt(0); writeInt(textBytes.size); write(textBytes) }
      }.toByteArray()
      val sizeString = raw.size.toString().toByteArray(Charsets.UTF_8)
      fun chunk(marker: Int, data: ByteArray) =
        message("DCLP") { writeByte(id); writeInt(sequence); writeByte(marker); writeInt(data.size); write(data) }
      return listOf(
        message("CCLP") { writeByte(id); writeInt(sequence) },
        chunk(1, sizeString),
        chunk(2, raw),
        chunk(3, ByteArray(0)),
      )
    }

    fun readHelloBackName(packet: Packet): String =
      packet.input().run {
        readShort()
        readShort()
        val length = readInt()
        String(ByteArray(length).also { readFully(it) }, Charsets.UTF_8)
      }
  }
}
