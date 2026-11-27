// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.client.net

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.nio.channels.UnsupportedAddressTypeException
import javax.net.ssl.SSLException

/**
 * Why a connection attempt failed before it was established. The app shows a reason instead of an endless
 * "Connecting…" (U3 from the emulator test). The [code] is what the app passes over AIDL.
 */
enum class ConnectionFailure(val code: Int) {
  /** The TLS handshake failed, e.g. TLS is on here but off in Deskflow on the computer. */
  TLS_HANDSHAKE(1),

  /** Nobody answered: wrong address or port, Deskflow not running, other network, firewall. */
  UNREACHABLE(2),

  /** Anything else. */
  OTHER(3);

  companion object {
    const val NONE_CODE = 0

    fun fromCode(code: Int): ConnectionFailure? = entries.firstOrNull { it.code == code }

    fun classify(err: Throwable): ConnectionFailure {
      var current: Throwable? = err
      val seen = mutableSetOf<Throwable>()
      while (current != null && seen.add(current)) {
        when (current) {
          is SSLException -> return TLS_HANDSHAKE
          is ConnectException,
          is NoRouteToHostException,
          is UnknownHostException,
          is SocketTimeoutException,
          is UnresolvedAddressException,
          is UnsupportedAddressTypeException -> return UNREACHABLE
          is IOException ->
            // FullDuplexSocket gives up on servers that never answer the SYN (stealth firewalls).
            if (current.message?.startsWith("connect timeout") == true) return UNREACHABLE
        }
        current = current.cause
      }
      return OTHER
    }
  }
}
