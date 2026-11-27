// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLayoutTest {

  @Test
  fun `phone in landscape is compact`() {
    // Pixel 3a in landscape: 1080 px at 440 dpi = 392 dp.
    assertTrue(SettingsLayout.isCompact(heightDp = 392, imeVisible = false))
  }

  @Test
  fun `open keyboard makes it compact also in portrait`() {
    assertTrue(SettingsLayout.isCompact(heightDp = 807, imeVisible = true))
  }

  @Test
  fun `portrait without keyboard keeps the full header`() {
    assertFalse(SettingsLayout.isCompact(heightDp = 807, imeVisible = false))
    assertFalse(SettingsLayout.isCompact(heightDp = SettingsLayout.COMPACT_HEIGHT_DP, imeVisible = false))
  }

  @Test
  fun `unknown height is not treated as compact`() {
    assertFalse(SettingsLayout.isCompact(heightDp = 0, imeVisible = false))
  }
}
