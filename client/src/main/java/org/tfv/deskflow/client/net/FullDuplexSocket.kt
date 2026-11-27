/*
 * MIT License
 *
 * Copyright (c) 2025 Jonathan Glanz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

@file:OptIn(ExperimentalAtomicApi::class)

package org.tfv.deskflow.client.net


import javax.net.ssl.X509TrustManager
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.LinkedBlockingQueue
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSession
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import org.tfv.deskflow.client.io.DynamicByteBuffer
import org.tfv.deskflow.client.util.AbstractDisposable
import org.tfv.deskflow.client.util.ISimpleEventEmitter
import org.tfv.deskflow.client.util.SimpleEventEmitter
import org.tfv.deskflow.client.util.logging.KLoggingManager

class FullDuplexSocket(
  private val host: String,
  private val port: Int,
  private val useTls: Boolean,
  private val keyManagers: Array<KeyManager>? = null,
  /** Decides whether the server certificate is accepted (see [PinningTrustManager]). */
  private val trustManager: X509TrustManager? = null,
) :
  AbstractDisposable(),
  ISimpleEventEmitter<FullDuplexSocket.SocketEvent> by SimpleEventEmitter<
    SocketEvent
  >() {

  /** The socket channel used for communication */
  @Volatile private var channel: SocketChannel? = null

  /** Queue for outbound messages */
  private val outbound = LinkedBlockingQueue<ByteBuffer>()

  /** The thread that runs the socket connection */
  @Volatile private var thread: Thread? = null

  /** Lock to synchronize access to the socket thread */
  private val threadLock = Any()

  /** The selector used to manage the socket channel */
  @Volatile private var selector: Selector? = null

  // TLS support
  private var sslContext: SSLContext? = null
  private var sslEngine: SSLEngine? = null
  private var netOutBuffer: ByteBuffer? = null
  private var netInBuffer: ByteBuffer? = null
  private var appInBuffer: ByteBuffer? = null

  /** Partially written plaintext message; must go out before anything else in [outbound]. */
  private var pendingWrite: ByteBuffer? = null

  /** Check if the socket thread is running */
  val isRunning: Boolean
    get() = thread?.isAlive ?: false

  /** Check if the socket is connected. */
  val isConnected: Boolean
    get() = isRunning && channel?.isConnected ?: false

  /** Start the socket connection. */
  fun start() {
    log.trace { "Connecting to $host:$port" }
    synchronized(threadLock) {
      if (thread != null || isRunning) {
        return@start
      }
    }
    thread = Thread({ runLoop() }, FullDuplexSocket::class.java.simpleName)
    thread!!.start()
  }

  private val isStopped = AtomicBoolean(false)

  fun stop(skipEmit: Boolean = false) {
    synchronized(threadLock) {
      if (isStopped.exchange(true)) {
        log.warn { "Socket is already stopped" }
        return@stop
      }

      val thread = this.thread

      log.trace { "Interrupting socket" }
      try {
        thread?.interrupt()
      } catch (err: Exception) {}

      // stop() is also called from the socket thread itself (EOF, error) –
      // joining there would wait for itself forever.
      if (thread != null && thread !== Thread.currentThread()) {
        log.trace { "Joining socket" }
        try {
          thread.join(JOIN_TIMEOUT_MS)
        } catch (err: InterruptedException) {
          Thread.currentThread().interrupt()
        }
      }
      this.thread = null

      val channel = channel
      if (channel != null) {
        // H5: also a channel that is still connecting (OP_CONNECT) must be closed, otherwise every retry leaks a
        // file descriptor. Closing the selector does not close its channels.
        try {
          channel.close()
        } catch (err: Exception) {}
        this.channel = null
      }

      val selector = selector
      if (selector != null) {
        try {
          selector.close()
        } catch (err: Exception) {}
        this.selector = null
      }

      log.trace { "Stopped socket" }
      if (!skipEmit) {
        emit(SocketEvent.DisconnectEvent(this))
      }
      this.clear()
    }
  }

  override fun onDispose() {
    clear()
    stop(true)
  }

  fun waitFor() {
    val thread = this.thread
    if (thread == null || !thread.isAlive) {
      return
    }
    if (thread === Thread.currentThread()) return
    thread.join()
  }

  fun send(data: ByteArray) {
    synchronized(threadLock) {
      if (!isRunning) {
        log.warn { "Socket is not running" }
        return
      }

      // Enqueue the next message
      outbound.put(ByteBuffer.wrap(data))
      // Enable OP_WRITE so selector triggers write without spinning
      channel?.let { sc ->
        selector?.let { sel ->
          val key = sc.keyFor(sel)
          if (key != null && key.isValid) {
            try {
              key.interestOps(key.interestOps() or SelectionKey.OP_WRITE)
            } catch (_: Exception) {}
          }
        }
      }
      // Wake up selector in case it’s blocked
      selector?.wakeup()
    }

    log.trace { "Send message queued ${data.size}" }
  }

  @Throws(SSLException::class)
  private fun doHandshake(sc: SocketChannel, sel: Selector) {
    val sslEngine = sslEngine ?: throw SSLException("SSLEngine is null")
    val netInBuffer = netInBuffer ?: throw SSLException("netInBuffer is null")
    val netOutBuffer =
      netOutBuffer ?: throw SSLException("netOutBuffer is null")
    var hsStatus = sslEngine.handshakeStatus
    val deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
    while (
      hsStatus != SSLEngineResult.HandshakeStatus.FINISHED &&
        hsStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
    ) {
      if (System.currentTimeMillis() > deadline) {
        throw SSLException("TLS handshake timed out")
      }
      when (hsStatus) {
        SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
          netInBuffer.flip()
          val res = sslEngine.unwrap(netInBuffer, appInBuffer)
          netInBuffer.compact()
          hsStatus = res.handshakeStatus
          if (res.status == SSLEngineResult.Status.CLOSED) {
            throw SSLException("Connection closed during handshake")
          }
          if (res.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
            val read = sc.read(netInBuffer)
            if (read < 0) throw SSLException("Channel closed during handshake")
            if (read == 0) Thread.sleep(5)
          }
        }
        SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
          netOutBuffer.clear()
          val res = sslEngine.wrap(ByteBuffer.allocate(0), netOutBuffer)
          netOutBuffer.flip()
          writeFully(sc, netOutBuffer)
          hsStatus = res.handshakeStatus
        }
        SSLEngineResult.HandshakeStatus.NEED_TASK -> {
          var task: Runnable? = sslEngine.delegatedTask
          while (task != null) {
            task.run()
            task = sslEngine.delegatedTask
          }
          hsStatus = sslEngine.handshakeStatus
        }
        else -> throw SSLException("Unexpected handshake status: $hsStatus")
      }
    }
  }

  @Throws(SSLException::class)
  private fun unwrapIncoming(sc: SocketChannel, readBuffer: DynamicByteBuffer) {
    val netInBuffer = netInBuffer ?: throw SSLException("netInBuffer is null")
    val sslEngine = sslEngine ?: throw SSLException("sslEngine is null")
    var appInBuffer = appInBuffer ?: throw SSLException("appInBuffer is null")

    var produced = false
    if (appInBuffer.position() > 0) {
      appInBuffer.flip()
      readBuffer.append(appInBuffer, appInBuffer.remaining())
      appInBuffer.compact()
      produced = true
    }

    netInBuffer.flip()
    try {
      while (netInBuffer.hasRemaining()) {
        val res = sslEngine.unwrap(netInBuffer, appInBuffer)
        if (res.bytesProduced() > 0) {
          appInBuffer.flip()
          readBuffer.append(appInBuffer, appInBuffer.remaining())
          appInBuffer.compact()
          produced = true
        }

        when (res.status) {
          SSLEngineResult.Status.BUFFER_UNDERFLOW -> break
          SSLEngineResult.Status.BUFFER_OVERFLOW -> {
            val bigger =
              ByteBuffer.allocate(
                maxOf(
                  appInBuffer.capacity() * 2,
                  sslEngine.session.applicationBufferSize,
                )
              )
            appInBuffer.flip()
            bigger.put(appInBuffer)
            appInBuffer = bigger
            this.appInBuffer = bigger
          }
          SSLEngineResult.Status.CLOSED -> throw SSLException("SSLEngine closed")
          else -> {}
        }

        runHandshakeTasks(sc)
        if (res.bytesConsumed() == 0 && res.bytesProduced() == 0 &&
          res.status == SSLEngineResult.Status.OK) {
          break
        }
      }
    } finally {
      netInBuffer.compact()
    }

    if (produced) {
      emit(SocketEvent.ReceiveEvent(readBuffer, this))
    }
  }

  /**
   * Writes the whole buffer on the non-blocking channel. When the kernel buffer
   * is full, waits briefly instead of spinning, with an upper time limit.
   */
  private fun writeFully(sc: SocketChannel, buf: ByteBuffer) {
    val deadline = System.currentTimeMillis() + WRITE_TIMEOUT_MS
    while (buf.hasRemaining()) {
      if (sc.write(buf) == 0) {
        if (System.currentTimeMillis() > deadline) throw SSLException("TLS write timed out")
        Thread.sleep(WRITE_RETRY_SLEEP_MS)
      }
    }
  }

  @Throws(SSLException::class)
  private fun runHandshakeTasks(sc: SocketChannel) {
    val sslEngine = sslEngine ?: throw SSLException("sslEngine is null")
    val netOutBuffer = netOutBuffer ?: throw SSLException("netOutBuffer is null")
    var hsStatus = sslEngine.handshakeStatus
    var guard = 0
    while (guard++ < 32) {
      when (hsStatus) {
        SSLEngineResult.HandshakeStatus.NEED_TASK -> {
          var task: Runnable? = sslEngine.delegatedTask
          while (task != null) {
            task.run()
            task = sslEngine.delegatedTask
          }
          hsStatus = sslEngine.handshakeStatus
        }
        SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
          netOutBuffer.clear()
          val res = sslEngine.wrap(ByteBuffer.allocate(0), netOutBuffer)
          netOutBuffer.flip()
          writeFully(sc, netOutBuffer)
          hsStatus = res.handshakeStatus
        }
        else -> return
      }
    }
  }

  private fun runLoop() {

    log.trace { "Socket thread started" }

    try {
      selector = Selector.open()
      val readBuffer = DynamicByteBuffer()
      val sel = selector!!

      channel =
        when {
          useTls -> {
            // Initialize SSL context and engine for client mode
            sslContext = SSLContext.getInstance("TLS")
            val sslContext = sslContext!!
            val trustManager =
              trustManager ?: throw SSLException("No trust manager for TLS connection")
            sslContext.init(keyManagers, arrayOf(trustManager), null)
            sslEngine = sslContext.createSSLEngine(host, port)
            val sslEngine = sslEngine!!
            sslEngine.useClientMode = true

            sslEngine.beginHandshake()
            val session: SSLSession = sslEngine.session

            // Allocate buffers based on session sizes
            netOutBuffer = ByteBuffer.allocate(session.packetBufferSize)
            netInBuffer = ByteBuffer.allocate(session.packetBufferSize)
            appInBuffer = ByteBuffer.allocate(session.applicationBufferSize)

            // Open non-blocking channel for TLS handshake
            SocketChannel.open().apply {
              configureBlocking(false)
              connect(InetSocketAddress(host, port))
              register(sel, SelectionKey.OP_CONNECT)
            }
          }

          else ->
            SocketChannel.open().apply {
              configureBlocking(false)
              connect(InetSocketAddress(host, port))
              register(sel, SelectionKey.OP_CONNECT)
            }
        }
      val connectDeadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
      while (!Thread.currentThread().isInterrupted) {
        // blocks until an event, wakeup() or the timeout (to notice a connect that never completes)
        sel.select(SELECT_TIMEOUT_MS)
        val pending = channel
        if (pending != null && !pending.isConnected && System.currentTimeMillis() > connectDeadline) {
          // H5: servers that drop SYNs (stealth firewalls) never answer; give up instead of waiting forever.
          throw java.io.IOException("connect timeout after ${CONNECT_TIMEOUT_MS / 1000} s")
        }

        // Get selected keys
        val iter = sel.selectedKeys().iterator()

        // Process selected keys
        while (iter.hasNext()) {
          val key = iter.next().also { iter.remove() }

          when {
            key.isConnectable -> {
              val sc = key.channel() as SocketChannel
              if (sc.finishConnect()) {
                if (useTls) {
                  doHandshake(sc, sel)
                }

                // Now interested in read; write will be enabled on demand
                sc.register(sel, SelectionKey.OP_READ)
                emit(SocketEvent.ConnectEvent(this))

                if (
                  useTls &&
                    ((netInBuffer?.position() ?: 0) > 0 ||
                      (appInBuffer?.position() ?: 0) > 0)
                ) {
                  unwrapIncoming(sc, readBuffer)
                }
              }
            }

            key.isReadable -> {
              val sc = key.channel() as SocketChannel
              if (useTls) {
                val bytesRead = sc.read(netInBuffer)
                if (bytesRead > 0) {
                  unwrapIncoming(sc, readBuffer)
                } else if (bytesRead < 0) {
                  sc.close()
                  stop()
                }
              } else {
                // existing plaintext read logic
                val buf = ByteBuffer.allocate(4096)
                val bytes = sc.read(buf)
                if (bytes > 0) {
                  buf.flip()
                  readBuffer.append(buf, bytes)
                  emit(SocketEvent.ReceiveEvent(readBuffer, this))
                } else if (bytes < 0) {
                  sc.close()
                  stop()
                }
              }
            }

            key.isWritable -> {
              val sc = key.channel() as SocketChannel
              if (useTls) {
                val msg = outbound.poll()
                if (msg != null) {
                  val netOutBuffer =
                    netOutBuffer ?: throw SSLException("netOutBuffer is null")
                  val sslEngine =
                    sslEngine ?: throw SSLException("sslEngine is null")
                  while (msg.hasRemaining()) {
                    netOutBuffer.clear()
                    val res = sslEngine.wrap(msg, netOutBuffer)
                    netOutBuffer.flip()
                    writeFully(sc, netOutBuffer)
                    if (res.status == SSLEngineResult.Status.CLOSED) {
                      throw SSLException("SSLEngine closed")
                    }
                    runHandshakeTasks(sc)
                  }
                }
              } else { // Drain one message at a time, keeping the order
                val msg = pendingWrite ?: outbound.poll()

                if (msg != null) {
                  log.trace { "Writing ${msg.remaining()} bytes" }
                  sc.write(msg)
                  // A partial write stays at the head; the next OP_WRITE continues it.
                  pendingWrite = if (msg.hasRemaining()) msg else null
                }
              }
              if (outbound.isEmpty() && pendingWrite == null) {
                try {
                  key.interestOps(key.interestOps() and SelectionKey.OP_WRITE.inv())
                } catch (_: Exception) {}
              }
            }
          }
        }
      }
    } catch (err: Exception) {
      log.warn(err) { "Error in socket thread" }
      emit(SocketEvent.ErrorEvent(err, this))
      try {
        dispose()
      } catch (_: Exception) {}
    }
  }

  sealed class SocketEvent() {
    data class ConnectEvent(val socket: FullDuplexSocket) : SocketEvent()

    data class DisconnectEvent(val socket: FullDuplexSocket) : SocketEvent()

    data class ReceiveEvent(
      val buf: DynamicByteBuffer,
      val socket: FullDuplexSocket,
    ) : SocketEvent()

    data class ErrorEvent(val err: Exception, val socket: FullDuplexSocket) :
      SocketEvent()
  }

  companion object {

    private val log =
      KLoggingManager.logger(FullDuplexSocket::class.java.simpleName)

    private const val HANDSHAKE_TIMEOUT_MS = 15_000L
    private const val WRITE_TIMEOUT_MS = 10_000L
    private const val WRITE_RETRY_SLEEP_MS = 2L
    private const val JOIN_TIMEOUT_MS = 5_000L
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val SELECT_TIMEOUT_MS = 1_000L


  }


}
