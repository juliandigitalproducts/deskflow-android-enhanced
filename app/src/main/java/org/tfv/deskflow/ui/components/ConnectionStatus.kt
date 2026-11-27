// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.ui.components

import androidx.annotation.StringRes
import org.tfv.deskflow.R
import org.tfv.deskflow.client.net.ConnectionFailure

/** What the app shows as connection status (toolbar, status card, keyboard bar). */
enum class ConnectionStatus(
  @StringRes val label: Int,
  @StringRes val detail: Int? = null,
) {
  STOPPED(R.string.connection_status_widget_header_status_stopped),
  CONNECTED(R.string.connection_status_widget_header_status_connected),
  CONNECTING(R.string.connection_status_widget_header_status_connecting),
  TLS_FAILED(R.string.connection_status_widget_header_status_tls_failed, R.string.connection_status_error_tls),
  UNREACHABLE(
    R.string.connection_status_widget_header_status_unreachable,
    R.string.connection_status_error_unreachable,
  ),
  FAILED(R.string.connection_status_widget_header_status_failed, R.string.connection_status_error_other);

  val isError: Boolean
    get() = detail != null
}

object ConnectionStatusPolicy {
  /**
   * The client retries after 1, 1, 2, 4 … s. A single failed attempt is normal (e.g. while the computer wakes up),
   * so the reason is shown from the third attempt in a row on (U3 from the emulator test).
   */
  const val SHOW_ERROR_AFTER_ATTEMPTS = 3

  fun of(
    isEnabled: Boolean,
    isConnected: Boolean,
    awaitingTrust: Boolean,
    failureKind: Int,
    failedAttempts: Int,
  ): ConnectionStatus =
    when {
      !isEnabled -> ConnectionStatus.STOPPED
      isConnected -> ConnectionStatus.CONNECTED
      // The trust dialog explains this case itself.
      awaitingTrust || failedAttempts < SHOW_ERROR_AFTER_ATTEMPTS -> ConnectionStatus.CONNECTING
      else ->
        when (ConnectionFailure.fromCode(failureKind)) {
          ConnectionFailure.TLS_HANDSHAKE -> ConnectionStatus.TLS_FAILED
          ConnectionFailure.UNREACHABLE -> ConnectionStatus.UNREACHABLE
          ConnectionFailure.OTHER -> ConnectionStatus.FAILED
          null -> ConnectionStatus.CONNECTING
        }
    }
}
