// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.ui.screens

import org.tfv.deskflow.client.models.SERVER_DEFAULT_PORT

/**
 * Rules of the port text field in the settings screen (DSK-R10). Kept free of Compose so they can be unit tested.
 * The value [EMPTY] (0) stands for a cleared field.
 */
internal object PortField {
  const val EMPTY = 0
  private const val MAX_PORT = 65535
  private const val MAX_DIGITS = 5

  /**
   * The port for the text typed into the field, [EMPTY] for a cleared field, or null when the input is rejected
   * (the field then keeps its previous value). Only ASCII digits up to 65535 are accepted.
   */
  fun parse(input: String): Int? {
    if (input.isEmpty()) return EMPTY
    if (input.length > MAX_DIGITS || !input.all { it in '0'..'9' }) return null
    return input.toInt().takeIf { it <= MAX_PORT }
  }

  /** Text shown in the field: a cleared field stays empty instead of showing "0". */
  fun display(port: Int): String = if (port == EMPTY) "" else port.toString()

  /** The port that is saved: an empty or out-of-range value falls back to the Deskflow default. */
  fun toSave(port: Int): Int = if (port in 1..MAX_PORT) port else SERVER_DEFAULT_PORT
}

/** Test tags of the settings screen (instrumented tests). */
object SettingsTestTags {
  const val SCREEN_NAME = "settings_screen_name"
  const val COMPACT_TITLE = "settings_compact_title"
  const val ADDRESS = "settings_address"
  const val PORT = "settings_port"
  const val SAVE = "settings_save"
}

/** Layout decision for the settings dialog (U4 from the emulator test). */
object SettingsLayout {
  /** Below this available height (e.g. a phone in landscape) the big title block hid the fields. */
  const val COMPACT_HEIGHT_DP = 480

  fun isCompact(heightDp: Int, imeVisible: Boolean): Boolean =
    imeVisible || heightDp in 1 until COMPACT_HEIGHT_DP
}
