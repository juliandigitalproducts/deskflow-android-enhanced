// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.e2e

import android.content.Context
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.tfv.deskflow.ui.components.NotificationPromptStore
import org.tfv.deskflow.ui.components.PermissionDialogTestTags
import org.tfv.deskflow.ui.components.PermissionPrompts
import org.tfv.deskflow.ui.theme.DeskflowTheme

/**
 * U1/U2 from the emulator test: "Ignore" and "Later" must survive a configuration change (rotation), "Ignore"
 * must also survive a restart.
 */
@RunWith(AndroidJUnit4::class)
class PermissionPromptsTest {

  @get:Rule val compose = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()
  private lateinit var store: NotificationPromptStore

  @Before
  fun clearStoredChoice() {
    context
      .getSharedPreferences(NotificationPromptStore.PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
    store = NotificationPromptStore(context)
  }

  private fun dialog() = compose.onNodeWithTag(PermissionDialogTestTags.DISMISS)

  @Test
  fun ignoredNotificationDialogStaysAwayAfterRotationAndRestart() {
    val restoration = StateRestorationTester(compose)
    restoration.setContent {
      DeskflowTheme {
        PermissionPrompts(
          accessibilityEnabled = true,
          imeEnabled = true,
          notificationsGranted = false,
          onOpenAccessibilitySettings = {},
          onOpenKeyboardSettings = {},
          onRequestNotifications = {},
          store = store,
        )
      }
    }
    dialog().assertExists().performClick()
    dialog().assertDoesNotExist()

    restoration.emulateSavedInstanceStateRestore()
    dialog().assertDoesNotExist()
    // A new process reads the stored choice.
    assertTrue(NotificationPromptStore(context).isDismissed)
  }

  @Test
  fun laterOnTheAccessibilityDialogSurvivesRotation() {
    var laterCalls = 0
    var settingsOpened = 0
    val restoration = StateRestorationTester(compose)
    restoration.setContent {
      DeskflowTheme {
        PermissionPrompts(
          accessibilityEnabled = false,
          imeEnabled = false,
          notificationsGranted = false,
          onOpenAccessibilitySettings = { settingsOpened++ },
          onOpenKeyboardSettings = {},
          onRequestNotifications = {},
          onAccessibilityLater = { laterCalls++ },
          store = store,
        )
      }
    }
    compose.onNodeWithTag(PermissionDialogTestTags.CONFIRM).performClick()
    assertEquals(1, settingsOpened)
    dialog().assertExists().performClick()
    dialog().assertDoesNotExist()
    assertEquals(1, laterCalls)

    restoration.emulateSavedInstanceStateRestore()
    dialog().assertDoesNotExist()
  }
}
