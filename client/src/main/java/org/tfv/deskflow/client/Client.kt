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

package org.tfv.deskflow.client

import arrow.core.raise.catch
import org.tfv.deskflow.client.events.ClientEvent
import org.tfv.deskflow.client.events.ConnectionEvent
import org.tfv.deskflow.client.events.MessagesEvent
import org.tfv.deskflow.client.events.ScreenEvent
import org.tfv.deskflow.client.io.InvalidMessageSizeException
import org.tfv.deskflow.client.io.MessageParser
import org.tfv.deskflow.client.manager.ClipboardSendManager
import org.tfv.deskflow.client.manager.MessageHandler
import org.tfv.deskflow.client.models.ClipboardData
import org.tfv.deskflow.client.models.SERVER_DEFAULT_ADDRESS
import org.tfv.deskflow.client.models.ServerTarget
import org.tfv.deskflow.client.net.CertificateFingerprint
import org.tfv.deskflow.client.net.ConnectionFailure
import org.tfv.deskflow.client.net.FullDuplexSocket
import org.tfv.deskflow.client.net.PinningTrustManager
import org.tfv.deskflow.client.util.AbstractDisposable
import org.tfv.deskflow.client.util.SingletonThreadExecutor
import org.tfv.deskflow.client.util.disposeOf
import org.tfv.deskflow.client.util.logging.KLoggingManager
import javax.net.ssl.KeyManager

class Client(
  private val keyManagersProvider: () -> Array<KeyManager>? = { null },
  /** SHA-256 fingerprints (`AB:CD:…`) of server certificates the user confirmed. */
  private val trustedFingerprintsProvider: () -> Set<String> = { emptySet() },
  /** DSK-R3: whether clipboard data from the server should be collected (clipboard sharing switched on). */
  private val clipboardReceiveEnabledProvider: () -> Boolean = { true },
) : AbstractDisposable() {

  private var socket: FullDuplexSocket? = null

  var isEnabled: Boolean = true
    private set

  var target: ServerTarget? = null
    private set

  var isScreenActive: Boolean = false
    private set

  var ackReceived: Boolean = false
    private set

  private val connectionExecutor =
    SingletonThreadExecutor("ConnectionEventLoopThread")

  // Declared before init{} so it is not null when registered.
  private val clientEventListener: (ClientEvent) -> Unit = { onClientEvent(it) }

  init {
    ClientEventBus.on(clientEventListener)
    scheduleConnectionCheck()
  }

  /**
   * Enables or disables the client asynchronously.
   *
   * Submits the change to [connectionExecutor] to ensure thread safety. No
   * action is taken if the client is already in the desired state.
   *
   * @param enabled true to enable the client; false to disable it.
   */
  fun setEnabled(enabled: Boolean) {
    connectionExecutor.submit {
      if (isEnabled == enabled) return@submit

      isEnabled = enabled
    }
  }

  private fun scheduleConnectionCheck() {
    connectionExecutor.scheduleWithFixedDelay(
      delay = 1000L,
      runnable = {
        catch({
          when {
            !isEnabled -> {
              log.info { "Connection is disabled" }
              if (socket != null) {
                log.info { "Disconnecting due to disabled state" }
                disconnect()
              }
            }
            isConnected -> {
              log.debug { "Socket is connected already" }
            }
            // The socket ended without an event reaching us (error, keep-alive timeout, CBYE, TLS close):
            // report it now, so the app does not stay "connected".
            connectedReported.get() -> disconnect()
            System.currentTimeMillis() < retryNotBefore -> {}
            else -> {
              log.debug { "Attempting to connect..." }
              // Exponential backoff (1 s … 30 s) until a connection succeeds.
              retryNotBefore = System.currentTimeMillis() + retryDelayMs
              retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
              connect()
            }
          }
        }) { err ->
          log.error(err) { "Error during connection check: ${err.message}" }
        }
      },
    )
  }

  @Volatile private var retryNotBefore = 0L

  /** Failed attempts since the last connection or server change (U3: a reason instead of endless "Connecting…"). */
  private val consecutiveFailures = java.util.concurrent.atomic.AtomicInteger(0)

  /** Set when the current attempt stopped at an unconfirmed certificate; the app shows its own dialog for that. */
  @Volatile private var attemptAwaitsTrust = false

  private fun reportConnectFailure(err: Throwable) {
    if (attemptAwaitsTrust) return
    val reason = ConnectionFailure.classify(err)
    val count = consecutiveFailures.incrementAndGet()
    log.info { "Connection attempt failed ($reason, $count in a row)" }
    ClientEventBus.emit(ConnectionEvent.ConnectFailed(reason, count))
  }
  @Volatile private var retryDelayMs = MIN_RETRY_DELAY_MS

  /** Retry immediately, e.g. after the user trusted the server certificate. */
  fun reconnectNow() {
    connectionExecutor.submit {
      retryNotBefore = 0L
      retryDelayMs = MIN_RETRY_DELAY_MS
      if (!isConnected) connect()
    }
  }

  /** True between ConnectionEvent.Connected and the matching ConnectionEvent.Disconnected (emitted once). */
  private val connectedReported = java.util.concurrent.atomic.AtomicBoolean(false)

  private val messageParser = MessageParser()
  private var messageHandler: MessageHandler? = null

  val isConnected: Boolean
    get() = socket?.isConnected ?: false

  fun setTarget(target: ServerTarget) {
    connectionExecutor.submit {
      log.info { "Setting target to ${target.address}:${target.port}" }
      val currentTarget = this.target
      if (target == currentTarget) {
        log.warn { "Targets are equal, no change" }
        return@submit
      }
      // Only the screen size changed (rotation): tell the server, keep the connection.
      if (currentTarget != null && target.copy(width = 0, height = 0) == currentTarget.copy(width = 0, height = 0)) {
        this.target = target
        if (isConnected) messageHandler?.updateScreenSize(target.width, target.height)
        return@submit
      }

      val address = target.address
      val port = target.port
      if (
        address == SERVER_DEFAULT_ADDRESS ||
          address.isBlank() ||
          port < 1 ||
          port > 65535
      ) {
        log.warn { "Target is invalid, not updating" }
        return@submit
      }

      this.target = target
      retryNotBefore = 0L
      retryDelayMs = MIN_RETRY_DELAY_MS
      consecutiveFailures.set(0)

      connect()
    }
  }

  private fun connect() {
    if (!connectionExecutor.isExecutorThread) {
      log.warn { "Connect called from non-executor thread, scheduling" }
      connectionExecutor.submit { connect() }
      return
    }

    disconnect()

    if (!isEnabled) {
      log.warn { "Client is not enabled, cannot connect" }
      return
    }
    val target = this.target
    if (target == null) {
      log.warn { "No valid target" }
      return
    }

    attemptAwaitsTrust = false
    try {
      val keyManagers = if (target.useTls) keyManagersProvider() else null
      val trustManager =
        if (target.useTls)
          PinningTrustManager(
            isTrusted = { fingerprint ->
              trustedFingerprintsProvider().any {
                CertificateFingerprint.normalize(it) == fingerprint
              }
            },
            onUntrusted = { fingerprint ->
              log.warn { "Server ${target.address}:${target.port} has an unconfirmed certificate $fingerprint" }
              attemptAwaitsTrust = true
              // Don't hammer the server while the user decides.
              retryNotBefore = System.currentTimeMillis() + UNTRUSTED_RETRY_DELAY_MS
              ClientEventBus.emit(
                ConnectionEvent.UntrustedServer(target.address, target.port, fingerprint)
              )
            },
          )
        else null
      val socket =
        FullDuplexSocket(target.address, target.port, target.useTls, keyManagers, trustManager)
      messageHandler = MessageHandler(socket, target, clipboardReceiveEnabledProvider)
      messageParser.reset()

      this.socket = socket
      socket.on { event ->
        when (event) {
          is FullDuplexSocket.SocketEvent.ConnectEvent -> {
            log.info { "ConnectEvent -> ConnectionEvent.Connected" }
            retryDelayMs = MIN_RETRY_DELAY_MS
            consecutiveFailures.set(0)
            connectedReported.set(true)
            ClientEventBus.emit(ConnectionEvent.Connected)
          }

          is FullDuplexSocket.SocketEvent.DisconnectEvent -> {
            log.info { "DisconnectEvent -> ConnectionEvent.Disconnected" }
            // disconnect() emits ConnectionEvent.Disconnected exactly once.
            connectionExecutor.submit { disconnectIfCurrent(socket) }
          }

          is FullDuplexSocket.SocketEvent.ReceiveEvent -> {
            onReceiveEvent(event)
          }

          is FullDuplexSocket.SocketEvent.ErrorEvent -> {
            // ConnectEvent runs on the same socket thread, so false here means the attempt never connected.
            val neverConnected = !connectedReported.get()
            connectionExecutor.submit {
              if (neverConnected && this.socket === socket) reportConnectFailure(event.err)
              disconnectIfCurrent(socket)
            }
          }
        }
      }

      socket.start()

      log.debug { "Started socket" }
    } catch (err: Exception) {
      log.error(err) { "Unable to cleanly connect: ${err.message}" }
      reportConnectFailure(err)
      disconnect()
    }
  }

  private fun onClientEvent(event: ClientEvent) {
    when (event) {
      is ScreenEvent.AckReceived -> {
        ackReceived = true
      }
      is ScreenEvent.Enter -> {
        isScreenActive = true
      }
      is ScreenEvent.Leave -> {
        isScreenActive = false
      }
      is ConnectionEvent.Disconnected -> {
        ackReceived = false
        isScreenActive = false
      }
      ConnectionEvent.Rejected -> {
        ackReceived = false
        isScreenActive = false
        retryNotBefore = System.currentTimeMillis() + REJECTED_RETRY_DELAY_MS
      }
    }
  }

  /** Socket receive handler */
  private fun onReceiveEvent(event: FullDuplexSocket.SocketEvent.ReceiveEvent) {
    val buf = event.buf
    log.trace { "ReceiveEvent(size=${buf.size()})" }
    val messages =
      try {
        messageParser.parseBuffer(buf)
      } catch (err: InvalidMessageSizeException) {
        log.error { "${err.message} – disconnecting" }
        // Like the other socket events: a late task must not drop a connection that was started in between.
        connectionExecutor.submit { disconnectIfCurrent(event.socket) }
        return
      }
    log.trace { "Parsed ${messages.size} messages" }
    if (messages.isNotEmpty()) {
      ClientEventBus.emit(MessagesEvent(messages))
    }
  }

  /**
   * Send clipboard data to server
   */
  fun sendClipboard(clipboardData: ClipboardData) {
    val messageHandler = messageHandler
    if (messageHandler == null) {
      log.info { "messageHandler == null, can not send clipboard" }
      return
    }

    ClipboardSendManager.sendClipboard(messageHandler, clipboardData)
  }

  /** Cleanup */
  override fun onDispose() {
    ClientEventBus.off(clientEventListener)
    isEnabled = false
    // Close the socket on the executor, then stop the executor itself so no
    // reconnect loop survives the service (zombie connections).
    connectionExecutor.submit { disconnect() }?.let { future ->
      try {
        future.get(DISPOSE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
      } catch (_: Exception) {}
    }
    connectionExecutor.dispose()
  }

  /** Disconnect the client & resources */
  private fun disconnect() {
    log.info { "Disconnecting" }
    if (!connectionExecutor.isExecutorThread) {
      log.warn { "Disconnect called from non-executor thread, scheduling" }
      connectionExecutor.submit { disconnect() }
      return
    }

    socket = disposeOf(socket)
    messageHandler = disposeOf(messageHandler)
    // H1: every end of a connection (error, dispose, CBYE, keep-alive timeout, TLS close_notify) is reported once,
    // independent of which path closed the socket.
    if (connectedReported.compareAndSet(true, false)) {
      ClientEventBus.emit(ConnectionEvent.Disconnected)
    }
  }

  /**
   * Handles the end of [ended]. The task runs later on the executor; when a new connection was started in between
   * (setTarget, reconnectNow), the late event of the old socket must not tear down the new one.
   */
  private fun disconnectIfCurrent(ended: FullDuplexSocket) {
    if (socket === ended || socket == null) {
      disconnect()
    } else {
      log.debug { "Ignoring the end of a previous socket" }
    }
  }

  /** Wait for socket to close if connected */
  fun waitForSocket() {
    log.debug { "Waiting for socket to stop/close" }
    socket?.waitFor()
  }

  companion object {
    private val log = KLoggingManager.logger(Client::class.java.simpleName)

    private const val REJECTED_RETRY_DELAY_MS = 10_000L
    private const val UNTRUSTED_RETRY_DELAY_MS = 30_000L
    private const val MIN_RETRY_DELAY_MS = 1_000L
    private const val MAX_RETRY_DELAY_MS = 30_000L
    private const val DISPOSE_TIMEOUT_MS = 3_000L
  }
}
