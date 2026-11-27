// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import org.tfv.deskflow.R

/** The permission dialog the start screen shows next. */
enum class PermissionPrompt {
  ACCESSIBILITY,
  IME,
  NOTIFICATIONS,
  NONE,
}

/**
 * Decides which permission dialog is shown.
 *
 * - Accessibility and keyboard are required, but "Later" (or Back) hides the dialog for the rest of the session,
 *   so the settings and the explanation stay reachable (U1). The flags live in `rememberSaveable`, so a rotation
 *   does not bring the dialog back.
 * - Notifications are optional: "Ignore" is stored, the dialog does not come back after a rotation, after the app
 *   returns to the foreground or after a restart (U2). Notifications can still be turned on in the system settings.
 */
object PermissionPromptPolicy {
  fun next(
    accessibilityEnabled: Boolean,
    imeEnabled: Boolean,
    notificationsGranted: Boolean,
    accessibilityDeferred: Boolean,
    imeDeferred: Boolean,
    notificationsDismissed: Boolean,
  ): PermissionPrompt =
    when {
      !accessibilityEnabled ->
        if (accessibilityDeferred) PermissionPrompt.NONE else PermissionPrompt.ACCESSIBILITY
      !imeEnabled -> if (imeDeferred) PermissionPrompt.NONE else PermissionPrompt.IME
      !notificationsGranted ->
        if (notificationsDismissed) PermissionPrompt.NONE else PermissionPrompt.NOTIFICATIONS
      else -> PermissionPrompt.NONE
    }
}

/** Remembers that the user answered the optional notification dialog with "Ignore". */
class NotificationPromptStore(context: Context) {
  private val prefs =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  val isDismissed: Boolean
    get() = prefs.getBoolean(KEY_NOTIFICATIONS_DISMISSED, false)

  fun dismiss() {
    prefs.edit { putBoolean(KEY_NOTIFICATIONS_DISMISSED, true) }
  }

  companion object {
    const val PREFS_NAME = "deskflow_ui_prompts"
    const val KEY_NOTIFICATIONS_DISMISSED = "notifications_prompt_dismissed"
  }
}

/**
 * Shows the next permission dialog. "Later" survives rotation (rememberSaveable), "Ignore" is stored in [NotificationPromptStore].
 */
@Composable
fun PermissionPrompts(
  accessibilityEnabled: Boolean,
  imeEnabled: Boolean,
  notificationsGranted: Boolean,
  onOpenAccessibilitySettings: () -> Unit,
  onOpenKeyboardSettings: () -> Unit,
  onRequestNotifications: () -> Unit,
  onAccessibilityLater: () -> Unit = {},
  store: NotificationPromptStore? = null,
) {
  val context = LocalContext.current
  val promptStore = store ?: remember(context) { NotificationPromptStore(context) }
  var accessibilityDeferred by rememberSaveable { mutableStateOf(false) }
  var imeDeferred by rememberSaveable { mutableStateOf(false) }
  var notificationsDismissed by remember(promptStore) { mutableStateOf(promptStore.isDismissed) }

  when (
    PermissionPromptPolicy.next(
      accessibilityEnabled = accessibilityEnabled,
      imeEnabled = imeEnabled,
      notificationsGranted = notificationsGranted,
      accessibilityDeferred = accessibilityDeferred,
      imeDeferred = imeDeferred,
      notificationsDismissed = notificationsDismissed,
    )
  ) {
    PermissionPrompt.ACCESSIBILITY ->
      PermissionsNeededDialog(
        text = R.string.permission_dialog_accessibility_service_message,
        onLater = {
          accessibilityDeferred = true
          onAccessibilityLater()
        },
        onClick = onOpenAccessibilitySettings,
      )
    PermissionPrompt.IME ->
      PermissionsNeededDialog(
        text = R.string.permission_dialog_ime_enabled_message,
        onLater = { imeDeferred = true },
        onClick = onOpenKeyboardSettings,
      )
    PermissionPrompt.NOTIFICATIONS ->
      PermissionsNeededDialog(
        text = R.string.permission_dialog_notifications_message,
        required = false,
        onDismiss = {
          promptStore.dismiss()
          notificationsDismissed = true
        },
        onClick = onRequestNotifications,
      )
    PermissionPrompt.NONE -> Unit
  }
}
