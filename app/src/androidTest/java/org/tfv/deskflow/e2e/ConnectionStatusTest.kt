// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.tfv.deskflow.R
import org.tfv.deskflow.client.net.ConnectionFailure
import org.tfv.deskflow.ext.copy
import org.tfv.deskflow.ui.components.AppToolbar
import org.tfv.deskflow.ui.components.CONNECTION_ERROR_TEST_TAG
import org.tfv.deskflow.ui.components.ConnectionStatusWidget
import org.tfv.deskflow.ui.components.TOOLBAR_STATUS_TEST_TAG
import org.tfv.deskflow.ui.components.preview.PreviewAppState
import org.tfv.deskflow.ui.components.preview.PreviewDeskflowThemedRoot

/** U3 (reason instead of endless "Connecting…") and U7 (connection light in the toolbar) from the emulator test. */
@RunWith(AndroidJUnit4::class)
class ConnectionStatusTest {

  @get:Rule val compose = createComposeRule()

  private lateinit var state: PreviewAppState

  // Resolved from the resources, so the test also passes on a German emulator.
  private fun str(id: Int, vararg args: Any) =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

  private fun show() {
    compose.setContent {
      PreviewDeskflowThemedRoot { appState ->
        state = appState as PreviewAppState
        AppToolbar(appState)
        ConnectionStatusWidget(appState)
      }
    }
    compose.waitForIdle()
  }

  private fun update(failure: ConnectionFailure?, attempts: Int, connected: Boolean = false) {
    compose.runOnIdle {
      state.previewConnectionStateFlow.value =
        state.previewConnectionStateFlow.value.copy(
          isEnabled = true,
          isConnected = connected,
          failureKind = failure?.code ?: ConnectionFailure.NONE_CODE,
          failedAttempts = attempts,
        )
    }
    compose.waitForIdle()
  }

  @Test
  fun tlsMismatchShowsTheReasonAfterThreeAttempts() {
    show()
    update(ConnectionFailure.TLS_HANDSHAKE, 2)
    compose.onNodeWithTag(CONNECTION_ERROR_TEST_TAG).assertDoesNotExist()
    compose.onNodeWithText(str(R.string.connection_status_widget_header_status_connecting).uppercase()).assertIsDisplayed()

    update(ConnectionFailure.TLS_HANDSHAKE, 3)
    compose.onNodeWithText(str(R.string.connection_status_widget_header_status_tls_failed).uppercase()).assertIsDisplayed()
    compose.onNodeWithTag(CONNECTION_ERROR_TEST_TAG).assertIsDisplayed()
    compose.onNodeWithContentDescription(
      str(R.string.app_toolbar_status_desc, str(R.string.connection_status_widget_header_status_tls_failed))
    ).assertExists()
  }

  @Test
  fun unreachableServerIsNamedAndTheErrorGoesAwayOnceConnected() {
    show()
    update(ConnectionFailure.UNREACHABLE, 5)
    compose.onNodeWithText(str(R.string.connection_status_widget_header_status_unreachable).uppercase()).assertIsDisplayed()

    update(null, 0, connected = true)
    compose.onNodeWithTag(CONNECTION_ERROR_TEST_TAG).assertDoesNotExist()
    compose.onNodeWithContentDescription(
      str(R.string.app_toolbar_status_desc, str(R.string.connection_status_widget_header_status_connected))
    ).assertExists()
    compose.onNodeWithTag(TOOLBAR_STATUS_TEST_TAG).assertExists()
  }
}
