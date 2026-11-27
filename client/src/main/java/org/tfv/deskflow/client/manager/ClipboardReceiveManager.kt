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

import org.tfv.deskflow.client.ClientEventBus
import org.tfv.deskflow.client.events.ScreenEvent
import org.tfv.deskflow.client.io.msgs.ClipboardDataMessage
import org.tfv.deskflow.client.models.ClipboardData
import org.tfv.deskflow.client.models.ClipboardDataMarker
import org.tfv.deskflow.client.util.logging.KLoggingManager
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class ClipboardReceiveManager {

  val messages = mutableListOf<Pair<ClipboardDataMarker, ClipboardDataMessage>>()

  var phase: ClipboardDataMarker = ClipboardDataMarker.Unknown
    private set

  val isStarted: Boolean
    get() = phase.code >= ClipboardDataMarker.Start.code

  val isCollectingData: Boolean
    get() = arrayOf(ClipboardDataMarker.Start.code, ClipboardDataMarker.Data.code).contains(phase.code)

  val isFinished: Boolean
    get() = phase.code >= ClipboardDataMarker.End.code

  val startMessage: ClipboardDataMessage?
    get() = messages.firstOrNull().takeIf { it?.first == ClipboardDataMarker.Start }?.second

  val dataMessageCount: Int
    get() = messages.count { it.first == ClipboardDataMarker.Data }

  val dataMessages: List<ClipboardDataMessage>
    get() = messages.filter { it.first == ClipboardDataMarker.Data }.map { it.second }

  val endMessage : ClipboardDataMessage?
    get() = messages.lastOrNull().takeIf {
      it?.first == ClipboardDataMarker.End
    }?.second

  @Synchronized
  fun submitMessage(message: ClipboardDataMessage) {
    val marker = message.marker
    when (marker) {
      ClipboardDataMarker.Start -> {
        if (isStarted) {
          log.warn { "Clipboard data is already started, current phase is $phase. Resetting and restarting" }
          reset()
        }
      }
      ClipboardDataMarker.Data, ClipboardDataMarker.End -> {
        if (!isCollectingData) {
          log.warn { "Clipboard data (marker=${marker}) is not collecting data, " +
                  "current phase is $phase. " +
                  "Ignoring & resetting" }
          reset()
          return
        }

        if (marker == ClipboardDataMarker.End && dataMessages.isEmpty()) {
          log.warn { "Received end marker without any data messages, ignoring message & resetting" }
          reset()
          return
        }
      }
      else -> {
        log.error {"Unknown clipboard data marker $message($marker)"}
        return
      }
    }

    // M1 (DF): a server (or a man in the middle on a plain connection) could send data chunks forever. The
    // collected size is capped; a larger clipboard is dropped instead of exhausting memory.
    collectedBytes += message.data.size.toLong()
    if (collectedBytes > MAX_CLIPBOARD_BYTES) {
      log.warn { "Clipboard data exceeds ${MAX_CLIPBOARD_BYTES / (1024 * 1024)} MiB, dropping it" }
      reset()
      return
    }

    phase = marker
    messages.add(Pair(marker, message))
    if (isFinished) {
      generateClipboardData()
    }
  }

  private fun generateClipboardData() {
    try {
      val startMsg = startMessage
      val endMsg = endMessage
      val dataMsgs = dataMessages

      if (startMsg == null || endMsg == null || dataMsgs.isEmpty()) {
        log.error { "Cannot generate clipboard data, missing start or end message or no data messages" }
        reset()
        return
      }

      val startData =
        DataInputStream(ByteArrayInputStream(startMsg.data, 1, startMsg.data.size - 1))
      val startDataSize = startData.readInt()
      // DSK-R3: the length comes from the server. Allocating it unchecked let a single message request up to
      // 2 GiB and crash the app with an OutOfMemoryError (not an Exception, so not caught below). The field only
      // holds the decimal total size, so it must fit into the start message itself.
      if (!isValidStartLength(startDataSize, startMsg.data.size)) {
        log.warn { "Invalid clipboard start length $startDataSize, dropping transfer" }
        return
      }
      val startDataStrBytes = ByteArray(startDataSize)
      startData.readFully(startDataStrBytes)
      val startDataStr = String(startDataStrBytes, Charsets.UTF_8)

      val dataSize = startDataStr.toIntOrNull() ?: -1
      log.info { "Clipboard data start says data size is $dataSize bytes" }
      if (dataSize < 0 || dataSize.toLong() > MAX_CLIPBOARD_BYTES) {
        log.warn { "Announced clipboard size $dataSize is invalid or too large, dropping it" }
        reset()
        return
      }

      // Plain byte copies instead of List<Byte> (one boxed object per byte).
      val collected = java.io.ByteArrayOutputStream(dataSize.coerceAtMost(MAX_CLIPBOARD_BYTES.toInt()))
      for (msg in dataMsgs) {
        val dataStrBytes = DataInputStream(ByteArrayInputStream(msg.data, 1, msg.data.size - 1))
        val dataStrLen = dataStrBytes.readInt()
        require(dataStrLen >= 0 && 5 + dataStrLen <= msg.data.size) { "invalid clipboard chunk length $dataStrLen" }
        collected.write(msg.data, 5, dataStrLen)
      }
      val allDataBytes = collected.toByteArray()

      val allDataInput = DataInputStream(ByteArrayInputStream(allDataBytes))
      val formatCount = allDataInput.readInt()
      require(formatCount in 0..16) { "invalid clipboard format count $formatCount" }
      val variants = mutableMapOf<ClipboardData.Format, ClipboardData.Variant>()

      for (i in 0 until formatCount) {
        val formatCode = allDataInput.readInt()
        val format = ClipboardData.Format.entries.find { it.code == formatCode }
        if (format != null) {
          val variantDataSize = allDataInput.readInt()
          require(variantDataSize in 0..allDataInput.available()) { "invalid clipboard variant size $variantDataSize" }
          val variantDataBytes = ByteArray(variantDataSize)
          allDataInput.readFully(variantDataBytes)
          variants[format] = ClipboardData.Variant(format, variantDataBytes)
        } else {
          log.warn { "Unknown clipboard format code: $formatCode" }
        }
      }

      log.info { "Clipboard variants (size=${variants.size},expected=$formatCount)" }
      if (variants.isNotEmpty()) {
        val clipboardData = ClipboardData(
          id = startMsg.id.toInt(),
          sequenceNumber = startMsg.sequenceNumber,
          variants = variants
        )

        ClientEventBus.emit(ScreenEvent.SetClipboard(clipboardData))
      }
    } catch (err: Exception) {
      log.error(err) { "Error generating clipboard data: ${err.message}" }
    } finally{
      reset()
    }
  }

  private var collectedBytes = 0L

  fun reset() {
    messages.clear()
    collectedBytes = 0L
  }

  companion object {
      private val log = KLoggingManager.logger("ClipboardManager")
      /** Largest clipboard accepted from the server (text; images are not synced). */
      const val MAX_CLIPBOARD_BYTES = 4L * 1024 * 1024

      /** The start message carries the total size as a decimal string; ten digits cover any Int. */
      const val MAX_START_LENGTH = 10

      /**
       * DSK-R3: checks the length field of a start message ([messageSize] includes the marker byte and the
       * 4-byte length itself) before anything is allocated.
       */
      fun isValidStartLength(length: Int, messageSize: Int): Boolean =
        length in 0..MAX_START_LENGTH && 5L + length <= messageSize
  }
}