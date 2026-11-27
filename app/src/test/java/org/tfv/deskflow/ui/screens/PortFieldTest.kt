// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** DSK-R10: clearing the port field or typing a long number crashed the settings screen. */
class PortFieldTest {

  @Test
  fun `cleared field is accepted as empty instead of crashing`() {
    assertEquals(PortField.EMPTY, PortField.parse(""))
    assertEquals("", PortField.display(PortField.EMPTY))
  }

  @Test
  fun `long numbers are rejected instead of overflowing`() {
    assertNull(PortField.parse("99999999999"))
    assertNull(PortField.parse("123456"))
  }

  @Test
  fun `values above 65535 and non-digits are rejected`() {
    assertNull(PortField.parse("65536"))
    assertNull(PortField.parse("70000"))
    assertNull(PortField.parse("24a00"))
    assertNull(PortField.parse("-1"))
    assertNull(PortField.parse(" 24800"))
    // Non-ASCII digits pass Char.isDigit() but are no port.
    assertNull(PortField.parse("٢٤٨٠٠"))
  }

  @Test
  fun `valid ports are taken over`() {
    assertEquals(24800, PortField.parse("24800"))
    assertEquals(65535, PortField.parse("65535"))
    assertEquals(1, PortField.parse("1"))
    assertEquals("24801", PortField.display(24801))
  }

  @Test
  fun `an empty port is saved as the Deskflow default`() {
    assertEquals(24800, PortField.toSave(PortField.EMPTY))
    assertEquals(24800, PortField.toSave(70000))
    assertEquals(24801, PortField.toSave(24801))
  }
}
