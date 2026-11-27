// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.tfv.deskflow.R
import org.tfv.deskflow.ui.components.ShortcutsListWidget
import org.tfv.deskflow.ui.components.preview.PreviewDeskflowThemedRoot

/** U6 from the emulator test: the shortcuts card had an edit button without a function. */
@RunWith(AndroidJUnit4::class)
class ShortcutsListWidgetTest {

  @get:Rule val compose = createComposeRule()

  private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

  @Test
  fun theShortcutsCardHasNoDeadEditButton() {
    compose.setContent { PreviewDeskflowThemedRoot { appState -> ShortcutsListWidget(appState) } }
    compose.onNodeWithText(str(R.string.shortcuts_list_widget_title)).assertExists()
    // The only clickable nodes left are the scroll arrows of the list.
    compose
      .onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick) and
          !hasContentDescription(str(R.string.scroll_up)) and
          !hasContentDescription(str(R.string.scroll_down))
      )
      .assertCountEquals(0)
    compose.onNodeWithContentDescription("Edit").assertDoesNotExist()
    compose.onNodeWithContentDescription("Bearbeiten").assertDoesNotExist()
  }
}
