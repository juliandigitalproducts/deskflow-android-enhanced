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

package org.tfv.deskflow.client.manager

import arrow.core.raise.catch
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.tfv.deskflow.client.ClientEventBus
import org.tfv.deskflow.client.events.ClientEvent
import org.tfv.deskflow.client.events.ConnectionEvent
import org.tfv.deskflow.client.events.KeyboardEvent
import org.tfv.deskflow.client.events.MessagesEvent
import org.tfv.deskflow.client.events.MouseEvent
import org.tfv.deskflow.client.events.ScreenEvent
import org.tfv.deskflow.client.io.msgs.*
import org.tfv.deskflow.client.models.ServerTarget
import org.tfv.deskflow.client.net.FullDuplexSocket
import org.tfv.deskflow.client.util.AbstractDisposable
import org.tfv.deskflow.client.util.logging.KLoggingManager

class MessageHandler(
  private val socket: FullDuplexSocket,
  private val target: ServerTarget,
  /** DSK-R3: false while clipboard sharing is off – incoming clipboard data is then not collected at all. */
  private val clipboardReceiveEnabled: () -> Boolean = { true },
) : AbstractDisposable() {

  @Volatile
  var isScreenActive: Boolean = false
    private set

  // Written by the message thread (CALV), read by the keep-alive check on the scheduler thread.
  @Volatile private var keepAliveServerTimestamp: Long = 0L
  private var keepAliveFuture: ScheduledFuture<Void>? = null

  val screenName: String
    get() = target.screenName

  /** Set when the screen changed size after connecting (rotation); otherwise the size of [target]. */
  @Volatile private var screenSizeOverride: Pair<Int, Int>? = null

  val screenSize: Pair<Int, Int>
    get() = screenSizeOverride ?: Pair(target.width, target.height)

  /**
   * H2: the screen was rotated/resized while connected. The server learns the new shape through a DINF, so
   * absolute positions and the switching edge match again – without dropping the connection.
   */
  fun updateScreenSize(width: Int, height: Int) {
    if (screenSize == Pair(width, height)) return
    screenSizeOverride = Pair(width, height)
    log.info { "Screen size changed to ${width}x$height, sending DINF" }
    sendMessage(getInfoMessage())
  }

  private val clipboardReceiveManager = ClipboardReceiveManager()

  private val messageExecutor = Executors.newSingleThreadExecutor()
  private val scheduledExecutor = Executors.newScheduledThreadPool(1)

  init {
    ClientEventBus.on(this::onClientEvent)
  }

  /** Event handler, listens specifically for MessagesEvent */
  private fun onClientEvent(event: ClientEvent) {
    when (event) {
      is MessagesEvent -> {
        log.trace { "MessagesEvent(size=${event.messages.size})" }
        onMessages(event.messages)
      }
    }
  }

  /** Called on dispose to clean up resources. */
  override fun onDispose() {
    // M11: stop under the lock, but wait outside of it – the tasks being stopped need the same lock, so waiting
    // inside blocked the reconnect for up to 2 × 5 s.
    val executors = synchronized(this) {
      ClientEventBus.off(this::onClientEvent)
      cancelKeepAliveCheck(true)
      listOf(scheduledExecutor, messageExecutor)
        .filter { !it.isShutdown }
        .onEach { it.shutdownNow() }
    }
    executors.forEach { it.awaitTermination(2, TimeUnit.SECONDS) }
  }

  private val isConnected: Boolean
    get() = socket.isConnected

  fun sendMessage(message: Message) {
    val socket = this.socket
    if (!isConnected) {
      log.warn { "Socket is not connected, ignoring message" }
      return
    }

    val buf = message.toBytes()
    socket.send(buf)
  }

  /** Handles incoming messages from the server (Deskflow Server Daemon). */
  private fun onMessages(messages: List<Message>) {
    synchronized(this) {
      if (messageExecutor.isShutdown) {
        log.warn { "Message executor is shutdown, ignoring messages" }
        return
      }
      messageExecutor.submit { messages.forEach { message -> handle(message) } }
    }
  }

  private fun handle(message: Message) {
    log.trace { "Received message: ${message.type}" }
    when (message) {
      is HelloMessage -> {
        log.trace { "Hello message: $message" }
        answerHello(message.majorVersion, message.minorVersion)
      }

      // M4: servers that use the protocol name "Synergy" (instead of "Barrier") send their hello in this shape.
      // It was ignored before, so the connection hung until the retry without any error.
      is HelloBackMessage -> {
        if (message.name.isBlank()) answerHello(message.majorVersion, message.minorVersion)
        else log.warn { "Should not be received by the client ${message.type}" }
      }

      is KeepAliveMessage -> {
        log.trace {
          "CALV: Keep alive received, updating timestamp and responding"
        }
        keepAliveServerTimestamp = System.currentTimeMillis()
        sendKeepAlive()
      }

      is NoOpMessage -> {
        log.error { "Needs to be implemented: ${message.type}" }
      }

      is CloseMessage -> {
        socket.close()
      }

      is EnterMessage -> {
        log.info { "Entered client screen" }
        isScreenActive = true
        ClipboardSendManager.clearPendingClipboardDataMessages()
        ClientEventBus.emit(ScreenEvent.Enter)
      }

      is LeaveMessage -> {
        log.info { "Left client screen" }
        isScreenActive = false
        val pendingClipboardDataMessages =
          ClipboardSendManager.popPendingClipboardDataMessages()
        pendingClipboardDataMessages?.forEach { sendMessage(it) }
        ClientEventBus.emit(ScreenEvent.Leave)
      }

      is ClipboardMessage -> {
        log.info {
          "Grab Clipboard (id=${message.id},sequenceNumber=${message.sequenceNumber})"
        }
        ClipboardSendManager.receivedSequenceNumber(message.sequenceNumber)
      }

      is ClipboardDataMessage -> {
        log.info {
          "Clipboard Data (id=${message.id},sequenceNumber=${message.sequenceNumber},marker=${message.marker})"
        }

        ClipboardSendManager.receivedSequenceNumber(message.sequenceNumber)
        if (isScreenActive && clipboardReceiveEnabled()) clipboardReceiveManager.submitMessage(message)
        else clipboardReceiveManager.reset()
      }

      is ScreenSaverMessage -> {
        log.error { "Needs to be implemented: ${message.type.name}" }
      }

      is ResetOptionsMessage -> {
        resetOptions()
      }

      is InfoAckMessage -> {
        ClientEventBus.emit(ScreenEvent.AckReceived)
      }

      is KeyDownMessage -> {
        ClientEventBus.emit(
          KeyboardEvent.down(message.id, message.button, message.mask)
        )
      }
      is KeyRepeatMessage -> {
        ClientEventBus.emit(
          KeyboardEvent.repeat(
            message.id,
            message.button,
            message.mask,
            message.count,
          )
        )
      }

      is KeyUpMessage -> {
        ClientEventBus.emit(
          KeyboardEvent.up(message.id, message.button, message.mask)
        )
      }

      is MouseDownMessage -> {
        ClientEventBus.emit(MouseEvent.down(message.buttonId))
      }

      is MouseUpMessage -> {
        ClientEventBus.emit(MouseEvent.up(message.buttonId))
      }

      is MouseMoveMessage -> {
        ClientEventBus.emit(
          MouseEvent.move(message.x.toInt(), message.y.toInt())
        )
      }

      is MouseRelMoveMessage -> {
        ClientEventBus.emit(
          MouseEvent.moveRelative(message.x.toInt(), message.y.toInt())
        )
      }

      is MouseWheelMessage -> {
        ClientEventBus.emit(
          MouseEvent.wheel(message.xDelta.toInt(), message.yDelta.toInt())
        )
      }

      is InfoMessage -> {
        log.warn { "Should not receive: ${message.type.name}" }
      }

      is SetOptionsMessage -> {
        log.warn {
          "TODO: determine if set options handling is needed ${message.type.name}"
        }
      }

      is QueryInfoMessage -> {
        sendInfo()
      }

      is IncompatibleMessage -> {
        reject("The server uses an incompatible protocol version, update Deskflow on the server.")
      }

      is BusyMessage -> {
        reject(
          "The server refused the screen name \"$screenName\": a computer with this name is " +
            "already connected. Make sure the server computer itself (e.g. your Mac) has a " +
            "different name and no other device uses \"$screenName\", or change the screen " +
            "name in the app.",
        )
      }

      is UnknownMessage -> {
        reject(
          "The server does not know the screen name \"$screenName\". In the Deskflow app on " +
            "the server open \"Configure Server\", drag a new computer into the layout & " +
            "name it exactly \"$screenName\".",
        )
      }

      is BadMessage -> {
        log.error { "Needs to be implemented: ${message.type.name}" }
      }

      else -> {
        log.warn { "Unknown message type: ${message.type.name}" }
      }
    }
  }

  /** Answers the server's hello with protocol 1.6; a server with another major version cannot work with us. */
  private fun answerHello(major: Int, minor: Int) {
    if (major != 1) {
      reject("server speaks protocol $major.$minor, this client supports 1.x")
      return
    }
    sendMessage(HelloBackMessage(1, 6, screenName))
  }

  private fun reject(hint: String) {
    log.error { "Connection rejected by server: $hint" }
    ClientEventBus.emit(ConnectionEvent.Rejected)
    socket.close()
  }

  fun getInfoMessage(): InfoMessage {
    val screenSize = screenSize
    return InfoMessage(
      0, // X - Position on client system?
      0, // Y - Position on client system?
      screenSize.first.toShort(),
      screenSize.second.toShort(),
      0, // Legacy data, not used according to deskflow core project
      0, // Cursor X
      0, // Cursor Y
    )
  }

  private fun sendKeepAlive() {
    sendMessage(KeepAliveMessage())
  }

  private fun resetOptions() {
    sendKeepAlive()
    scheduleKeepAliveCheck()
  }

  private fun cancelKeepAliveCheck(force: Boolean = false): Boolean {
    synchronized(this) {
      val currentKeepAliveFuture = keepAliveFuture
      if (currentKeepAliveFuture != null) {
        if (
          !currentKeepAliveFuture.isDone &&
            !currentKeepAliveFuture.cancel(force)
        ) {
          log.warn {
            "Keep alive already running, it will reschedule itself when complete"
          }
          return false
        }

        keepAliveFuture = null
      }
      return true
    }
  }

  private val keepAliveRunnable =
    Callable<Void> {
      val milliSinceLastKeepAlive =
        System.currentTimeMillis() - keepAliveServerTimestamp
      if (
        keepAliveServerTimestamp > 0L &&
          milliSinceLastKeepAlive > KEEP_ALIVE_TIMEOUT
      ) {
        log.warn {
          "Server did not respond to keep alive in time (${milliSinceLastKeepAlive}ms), closing connection"
        }
        catch({ socket.close() }) { err: Throwable ->
          log.error(err) {
            "Unable to cleanly close socket after keep alive timeout"
          }
        }
        return@Callable null
      }

      scheduleKeepAliveCheck()
      return@Callable null
    }

  private fun scheduleKeepAliveCheck() {
    synchronized(this) {
      if (!cancelKeepAliveCheck() || scheduledExecutor.isShutdown) {
        return
      }

      if (!isConnected) {
        log.warn { "Not connected, keep alive will not be scheduled" }
        return
      }

      keepAliveFuture =
        scheduledExecutor.schedule(
          keepAliveRunnable,
          200,
          TimeUnit.MILLISECONDS,
        )
    }
  }

  private fun sendInfo() {
    sendMessage(getInfoMessage())
  }

  companion object {
    private const val KEEP_ALIVE_TIMEOUT: Long = 7000L
    private val log =
      KLoggingManager.forwardingLogger(MessageHandler::class.java.simpleName)
  }
}
