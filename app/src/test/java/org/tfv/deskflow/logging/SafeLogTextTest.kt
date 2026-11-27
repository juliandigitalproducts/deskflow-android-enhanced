// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** DSK-R12: IME and message logs must not contain field contents or typed characters. */
class SafeLogTextTest {

  private val password = "Geheim!2026"

  @Test
  fun `extracted field text is logged as length only`() {
    val line = SafeLogText.extractedText("onUpdateSelection", password, 3, 3)
    assertFalse(line, line.contains(password))
    assertEquals("onUpdateSelection >> Extracted text (11 chars) — selection: [3, 3]", line)
  }

  @Test
  fun `selected text is logged as length only`() {
    val line = SafeLogText.selectedText("KeyboardEvent", password)
    assertFalse(line, line.contains(password))
    assertEquals("KeyboardEvent >> Selected text (11 chars)", line)
  }

  @Test
  fun `missing input connection is logged as zero chars`() {
    assertEquals("x >> Extracted text (0 chars) — selection: [null, null]", SafeLogText.extractedText("x", null, null, null))
  }

  @Test
  fun `incoming messages are logged by type only`() {
    val line = SafeLogText.incomingMessage("org.tfv.deskflow.client.events.KeyboardEvent")
    assertEquals("onMessage(org.tfv.deskflow.client.events.KeyboardEvent) -> ClientEventBus", line)
  }
}
