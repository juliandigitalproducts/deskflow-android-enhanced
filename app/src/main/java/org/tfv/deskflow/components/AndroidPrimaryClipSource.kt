// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context

/**
 * [PrimaryClipSource] backed by the ClipboardManager getters (passed as functions, so tests can hand in a
 * ClipData without touching the system clipboard).
 */
class AndroidPrimaryClipSource(
  private val context: Context,
  private val description: () -> ClipDescription?,
  private val clip: () -> ClipData?,
) : PrimaryClipSource {

  override fun meta(): ClipMeta? =
    description()?.let { desc ->
      ClipMeta(
        label = desc.label?.toString(),
        isSensitive = desc.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true,
        hasText =
          desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
            desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML),
      )
    }

  // The MIME types describe the whole clip, their index is no item index (getItemAt(mimeIdx) threw
  // IndexOutOfBounds for e.g. [image/png, text/plain] with one item). Text clips carry the text in item 0.
  override fun firstItemText(): String? {
    val data = clip() ?: return null
    if (data.itemCount == 0) return null
    return data.getItemAt(0).coerceToText(context).toString()
  }
}
