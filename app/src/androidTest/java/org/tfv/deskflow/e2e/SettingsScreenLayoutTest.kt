// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.e2e

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
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

/** U4 from the emulator test: no automatic focus, and in landscape the fields are visible without scrolling. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenLayoutTest {

  @get:Rule val compose = createComposeRule()

  private val screen =
    ScreenState().apply {
      name = "Emulator"
      server =
        ServerState().apply {
          address = "10.0.2.2"
          port = 24800
          useTls = false
          autoDiscover = false
        }
    }

  @Composable
  private fun Settings() {
    DeskflowTheme {
      SettingsScreen(
        uiState = SettingsUiState.Success(false, screen, ScrollSettings()),
        onChange = { _, _ -> },
        onDiscover = { emptyList() },
        onCancel = {},
      )
    }
  }

  @Test
  fun noFieldIsFocusedWhenTheSettingsOpen() {
    compose.setContent { Settings() }
    compose.waitForIdle()
    compose.onNodeWithTag(SettingsTestTags.SCREEN_NAME).assertIsNotFocused()
    compose.onNodeWithTag(SettingsTestTags.ADDRESS).assertIsNotFocused()
  }

  @Test
  fun landscapeUsesTheCompactHeaderAndShowsTheFields() {
    // Height of a phone in landscape (392 dp) minus status bar and app toolbar.
    compose.setContent { Box(Modifier.fillMaxWidth().height(300.dp)) { Settings() } }
    compose.onNodeWithTag(SettingsTestTags.COMPACT_TITLE).assertIsDisplayed()
    compose.onNodeWithTag(SettingsTestTags.SCREEN_NAME).assertIsDisplayed()
  }

  @Test
  fun portraitKeepsTheFullHeader() {
    compose.setContent { Box(Modifier.fillMaxWidth().height(700.dp)) { Settings() } }
    compose.onNodeWithTag(SettingsTestTags.COMPACT_TITLE).assertDoesNotExist()
  }
}
