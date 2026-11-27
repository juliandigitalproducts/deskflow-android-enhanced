// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.components

/** What can be checked on the primary clip without reading its content (taken from the ClipDescription). */
data class ClipMeta(val label: String?, val isSensitive: Boolean, val hasText: Boolean)

/**
 * Access to the primary clip, split in two steps: [meta] does not read the content, [firstItemText] does
 * (on Android 12+ that shows the "app pasted from your clipboard" notice).
 */
interface PrimaryClipSource {
  fun meta(): ClipMeta?

  /** Text of the first item, or null when the clip is gone or has no items. */
  fun firstItemText(): String?
}

/**
 * Decides whether a clip copied on the device goes to the server (DSK-R11). Kept free of Android types so it can
 * be unit tested; [AndroidPrimaryClipSource] connects it to the ClipboardManager.
 */
object ClipboardShareFilter {

  sealed interface Result {
    data class Share(val text: String) : Result

    data class Skip(val reason: SkipReason) : Result
  }

  enum class SkipReason {
    SHARING_OFF,
    NO_CLIP,
    FROM_SERVER,
    SENSITIVE,
    NO_TEXT,
    BLANK,
  }

  fun evaluate(sharingEnabled: Boolean, serverClipLabel: String, source: PrimaryClipSource): Result {
    // The switch comes first: while sharing is off the clipboard is not touched at all.
    if (!sharingEnabled) return Result.Skip(SkipReason.SHARING_OFF)
    val meta = source.meta() ?: return Result.Skip(SkipReason.NO_CLIP)
    // M5: our own clip (just received from the server) is not sent back.
    if (meta.label == serverClipLabel) return Result.Skip(SkipReason.FROM_SERVER)
    // Password managers, OTP apps etc. mark their clips as sensitive.
    if (meta.isSensitive) return Result.Skip(SkipReason.SENSITIVE)
    if (!meta.hasText) return Result.Skip(SkipReason.NO_TEXT)
    val text = source.firstItemText() ?: return Result.Skip(SkipReason.NO_CLIP)
    if (text.isBlank()) return Result.Skip(SkipReason.BLANK)
    return Result.Share(text)
  }
}
