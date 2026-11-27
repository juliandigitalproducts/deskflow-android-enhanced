// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.client.manager

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.tfv.deskflow.client.ClientEventBus
import org.tfv.deskflow.client.events.ClientEvent
import org.tfv.deskflow.client.events.ScreenEvent
import org.tfv.deskflow.client.io.msgs.ClipboardDataMessage
import org.tfv.deskflow.client.models.ClipboardData
import org.tfv.deskflow.client.models.ClipboardDataMarker

/** Clipboard assembly from DCLP messages, including the DSK-R3 size checks. */
class ClipboardReceiveManagerTest {

  private val received = mutableListOf<ScreenEvent.SetClipboard>()
  private val listener: (ClientEvent) -> Unit = { if (it is ScreenEvent.SetClipboard) received += it }

  @BeforeEach
  fun setUp() = ClientEventBus.on(listener)

  @AfterEach
  fun tearDown() = ClientEventBus.off(listener)

  private fun message(build: DataOutputStream.() -> Unit): ClipboardDataMessage {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).build()
    return ClipboardDataMessage(id = 0, sequenceNumber = 7, data = out.toByteArray())
  }

  private fun startMessage(totalSize: Int) = message {
    val digits = totalSize.toString().toByteArray(Charsets.UTF_8)
    writeByte(ClipboardDataMarker.Start.code)
    writeInt(digits.size)
    write(digits)
  }

  private fun dataMessage(chunk: ByteArray) = message {
    writeByte(ClipboardDataMarker.Data.code)
    writeInt(chunk.size)
    write(chunk)
  }

  private fun endMessage() = message {
    writeByte(ClipboardDataMarker.End.code)
    writeInt(0)
  }

  private fun textPayload(text: String): ByteArray {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val out = ByteArrayOutputStream()
    DataOutputStream(out).apply {
      writeInt(1)
      writeInt(ClipboardData.Format.Text.code)
      writeInt(bytes.size)
      write(bytes)
    }
    return out.toByteArray()
  }

  private fun ClipboardReceiveManager.submitText(text: String, chunkSize: Int = 5) {
    val payload = textPayload(text)
    submitMessage(startMessage(payload.size))
    payload.toList().chunked(chunkSize).forEach { submitMessage(dataMessage(it.toByteArray())) }
    submitMessage(endMessage())
  }

  private fun lastText(): String =
    String(received.last().data.variants.getValue(ClipboardData.Format.Text).data, Charsets.UTF_8)

  @Test
  fun `text split over several chunks is assembled`() {
    val manager = ClipboardReceiveManager()
    manager.submitText("Hallo Tablet, äöü")
    assertEquals(1, received.size)
    assertEquals("Hallo Tablet, äöü", lastText())
    assertTrue(manager.messages.isEmpty())
  }

  @Test
  fun `a huge start length is dropped instead of allocated`() {
    val manager = ClipboardReceiveManager()
    manager.submitMessage(message {
      writeByte(ClipboardDataMarker.Start.code)
      writeInt(Int.MAX_VALUE)
      write("12".toByteArray())
    })
    manager.submitMessage(dataMessage(textPayload("x")))
    manager.submitMessage(endMessage())
    assertTrue(received.isEmpty())
    assertTrue(manager.messages.isEmpty())
  }

  @Test
  fun `a negative start length is dropped`() {
    val manager = ClipboardReceiveManager()
    manager.submitMessage(message {
      writeByte(ClipboardDataMarker.Start.code)
      writeInt(-1)
    })
    manager.submitMessage(dataMessage(textPayload("x")))
    manager.submitMessage(endMessage())
    assertTrue(received.isEmpty())
    assertTrue(manager.messages.isEmpty())
  }

  @Test
  fun `an announced total above the limit is dropped`() {
    val manager = ClipboardReceiveManager()
    manager.submitMessage(startMessage((ClipboardReceiveManager.MAX_CLIPBOARD_BYTES + 1).toInt()))
    manager.submitMessage(dataMessage(textPayload("x")))
    manager.submitMessage(endMessage())
    assertTrue(received.isEmpty())
  }

  @Test
  fun `a rejected transfer does not block the next one`() {
    val manager = ClipboardReceiveManager()
    manager.submitMessage(message {
      writeByte(ClipboardDataMarker.Start.code)
      writeInt(Int.MAX_VALUE)
    })
    manager.submitMessage(dataMessage(textPayload("x")))
    manager.submitMessage(endMessage())
    manager.submitText("second")
    assertEquals(1, received.size)
    assertEquals("second", lastText())
  }

  @Test
  fun `start length must be small and fit into the message`() {
    assertTrue(ClipboardReceiveManager.isValidStartLength(2, 7))
    assertTrue(ClipboardReceiveManager.isValidStartLength(0, 5))
    assertFalse(ClipboardReceiveManager.isValidStartLength(-1, 100))
    assertFalse(ClipboardReceiveManager.isValidStartLength(3, 7))
    assertFalse(ClipboardReceiveManager.isValidStartLength(Int.MAX_VALUE, Int.MAX_VALUE))
    assertFalse(ClipboardReceiveManager.isValidStartLength(ClipboardReceiveManager.MAX_START_LENGTH + 1, 100))
  }
}
