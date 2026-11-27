// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionPromptPolicyTest {

  private fun next(
    accessibility: Boolean = true,
    ime: Boolean = true,
    notifications: Boolean = true,
    accessibilityDeferred: Boolean = false,
    imeDeferred: Boolean = false,
    notificationsDismissed: Boolean = false,
  ) =
    PermissionPromptPolicy.next(
      accessibilityEnabled = accessibility,
      imeEnabled = ime,
      notificationsGranted = notifications,
      accessibilityDeferred = accessibilityDeferred,
      imeDeferred = imeDeferred,
      notificationsDismissed = notificationsDismissed,
    )

  @Test
  fun `missing accessibility is asked first`() {
    assertEquals(PermissionPrompt.ACCESSIBILITY, next(accessibility = false, ime = false, notifications = false))
  }

  @Test
  fun `later on the accessibility dialog leaves the app usable`() {
    assertEquals(
      PermissionPrompt.NONE,
      next(accessibility = false, ime = false, notifications = false, accessibilityDeferred = true),
    )
  }

  @Test
  fun `keyboard is asked once accessibility is on and can be deferred`() {
    assertEquals(PermissionPrompt.IME, next(ime = false))
    assertEquals(PermissionPrompt.NONE, next(ime = false, imeDeferred = true))
  }

  @Test
  fun `ignored notification dialog stays away`() {
    assertEquals(PermissionPrompt.NOTIFICATIONS, next(notifications = false))
    assertEquals(PermissionPrompt.NONE, next(notifications = false, notificationsDismissed = true))
  }

  @Test
  fun `nothing is asked when everything is granted`() {
    assertEquals(PermissionPrompt.NONE, next())
    assertEquals(PermissionPrompt.NONE, next(notificationsDismissed = true))
  }
}
