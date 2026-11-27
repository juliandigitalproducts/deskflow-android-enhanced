// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import org.tfv.deskflow.R

/**
 * Trust-on-first-use prompt: shown while the service waits for the user to
 * confirm the certificate fingerprint of a TLS server it has not seen before.
 */
@Composable
fun UntrustedServerDialog(
  server: String,
  fingerprint: String,
  onTrust: () -> Unit,
  onReject: () -> Unit,
) {
  AlertDialog(
    // Tapping outside must not count as a decision.
    onDismissRequest = {},
    title = {
      Text(
        stringResource(R.string.untrusted_server_title),
        style = MaterialTheme.typography.headlineSmall,
      )
    },
    text = {
      Text(
        stringResource(R.string.untrusted_server_message, server, fingerprint),
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium,
      )
    },
    dismissButton = {
      TextButton(onClick = onReject) { Text(stringResource(R.string.untrusted_server_reject)) }
    },
    confirmButton = {
      TextButton(onClick = onTrust) { Text(stringResource(R.string.untrusted_server_trust)) }
    },
  )
}
