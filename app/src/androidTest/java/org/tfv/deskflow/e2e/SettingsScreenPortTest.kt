// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.tfv.deskflow.data.aidl.ScreenState
import org.tfv.deskflow.data.aidl.ServerState
import org.tfv.deskflow.ui.screens.ScrollSettings
import org.tfv.deskflow.ui.screens.SettingsScreen
import org.tfv.deskflow.ui.screens.SettingsTestTags
import org.tfv.deskflow.ui.screens.SettingsUiState
import org.tfv.deskflow.ui.theme.DeskflowTheme

/** E2E for the settings screen port field (DSK-R10): empty and invalid input must not crash or be saved. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenPortTest {

  @get:Rule val compose = createComposeRule()

  private var saved: ScreenState? = null

  @Before
  fun showSettings() {
    val screen =
      ScreenState().apply {
        name = "Emulator-Tablet"
        server =
          ServerState().apply {
            address = "10.0.2.2"
            port = 24800
            useTls = false
            autoDiscover = false
          }
      }
    compose.setContent {
      DeskflowTheme {
        SettingsScreen(
          uiState = SettingsUiState.Success(false, screen, ScrollSettings()),
          onChange = { state, _ -> saved = state },
          onDiscover = { emptyList() },
          onCancel = {},
        )
      }
    }
  }

  private fun portField() = compose.onNodeWithTag(SettingsTestTags.PORT)

  private fun assertPortText(expected: String) {
    portField().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(expected)))
  }

  private fun save(): ScreenState {
    compose.onNodeWithTag(SettingsTestTags.SAVE).performClick()
    compose.waitForIdle()
    val state = saved
    assertNotNull("settings were not saved", state)
    return state!!
  }

  @Test
  fun clearingThePortFieldDoesNotCrashAndShowsAnEmptyField() {
    portField().performTextClearance()
    assertPortText("")
  }

  @Test
  fun anEmptyPortIsSavedAsTheDeskflowDefault() {
    portField().performTextClearance()
    assertEquals(24800, save().server.port)
  }

  @Test
  fun tooLongOutOfRangeAndNonNumericInputIsRejected() {
    for (input in listOf("99999999999", "70000", "65536", "24a00", "-1")) {
      portField().performTextReplacement(input)
      assertPortText("24800")
    }
  }

  @Test
  fun aValidPortIsSaved() {
    portField().performTextReplacement("24801")
    assertPortText("24801")
    assertEquals(24801, save().server.port)
  }
}
