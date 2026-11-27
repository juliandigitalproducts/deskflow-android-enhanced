// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.client.net

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Trust-on-first-use: a server certificate is accepted only when the SHA-256
 * fingerprint of its leaf certificate was confirmed by the user before.
 * Deskflow servers use self-signed certificates, so a CA check is not possible.
 */
object CertificateFingerprint {
  /** SHA-256 of the DER-encoded certificate as `AB:CD:…` (Deskflow's display format). */
  fun sha256(cert: X509Certificate): String =
    format(MessageDigest.getInstance("SHA-256").digest(cert.encoded))

  fun format(bytes: ByteArray): String = bytes.joinToString(":") { "%02X".format(it) }

  /** Accepts any spelling (lower case, spaces, without colons) and returns `AB:CD:…`. */
  fun normalize(value: String): String =
    value.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.chunked(2).joinToString(":")
}

class UntrustedServerCertificateException(val fingerprint: String) :
  CertificateException("Server certificate is not trusted (SHA-256 $fingerprint)")

class PinningTrustManager(
  private val isTrusted: (String) -> Boolean,
  private val onUntrusted: (String) -> Unit = {},
) : X509TrustManager {

  @Volatile
  var lastFingerprint: String? = null
    private set

  override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
    val leaf = chain?.firstOrNull() ?: throw CertificateException("Empty certificate chain")
    val fingerprint = CertificateFingerprint.sha256(leaf)
    lastFingerprint = fingerprint
    if (!isTrusted(fingerprint)) {
      onUntrusted(fingerprint)
      throw UntrustedServerCertificateException(fingerprint)
    }
  }

  override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
    throw CertificateException("Client certificates are not accepted")
  }

  override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
