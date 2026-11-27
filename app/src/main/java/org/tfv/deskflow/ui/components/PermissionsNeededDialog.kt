/*
 * MIT License
 *
 * Copyright (c) 2025 Jonathan Glanz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.tfv.deskflow.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import org.tfv.deskflow.R
import org.tfv.deskflow.ui.annotations.PreviewAll
import org.tfv.deskflow.ui.theme.DeskflowTheme

/**
 * Asks for a permission. The caller decides whether the dialog is visible (see [PermissionPromptPolicy]).
 *
 * @param onLater for required permissions: "Later" and Back hide the dialog for this session (U1).
 * @param onDismiss for optional permissions: "Ignore" and Back; the caller stores the choice (U2).
 */
@Composable
fun PermissionsNeededDialog(
  @StringRes text: Int,
  required: Boolean = true,
  onLater: () -> Unit = {},
  onDismiss: () -> Unit = {},
  onClick: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = { if (required) onLater() else onDismiss() },
    title = {
      Text(
        stringResource(
          if (required) R.string.permission_dialog_title_required
          else R.string.permission_dialog_title_optional
        ),
        style = MaterialTheme.typography.headlineSmall,
      )
    },
    text = {
      Text(
        stringResource(text),
        modifier = Modifier.verticalScroll(rememberScrollState()),
      )
    },
    dismissButton = {
      TextButton(
        onClick = { if (required) onLater() else onDismiss() },
        modifier = Modifier.testTag(PermissionDialogTestTags.DISMISS),
      ) {
        Text(
          stringResource(
            if (required) R.string.permission_dialog_buttons_later
            else R.string.permission_dialog_buttons_dismiss
          )
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = onClick,
        modifier = Modifier.testTag(PermissionDialogTestTags.CONFIRM),
      ) {
        Text(stringResource(R.string.permission_dialog_buttons_open_settings))
      }
    },
  )
}

object PermissionDialogTestTags {
  const val DISMISS = "permission_dialog_dismiss"
  const val CONFIRM = "permission_dialog_confirm"
}

@PreviewAll
@Composable
fun PermissionsNeededDialogPreview() {
  val snackbarHostState = remember { SnackbarHostState() }

  CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
    DeskflowTheme {
      PermissionsNeededDialog(
        text = R.string.permission_dialog_accessibility_service_message,
        onClick = {},
      )
    }
  }
}

@PreviewAll
@Composable
fun PermissionsNeededDialogNonRequiredPreview() {
  val snackbarHostState = remember { SnackbarHostState() }

  CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
    DeskflowTheme {
      PermissionsNeededDialog(
        required = false,
        text = R.string.permission_dialog_accessibility_service_message,
        onClick = {},
      )
    }
  }
}
