// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.components

import org.junit.Assert.assertEquals
import org.junit.Test
import org.tfv.deskflow.components.ClipboardShareFilter.Result
import org.tfv.deskflow.components.ClipboardShareFilter.SkipReason

/** DSK-R11: the clipboard must not be read while sharing is off, nor for clips that are never sent. */
class ClipboardShareFilterTest {

  private class FakeClipSource(private val meta: ClipMeta?, private val text: String? = "hello") : PrimaryClipSource {
    var metaReads = 0
    var contentReads = 0

    override fun meta(): ClipMeta? = meta.also { metaReads++ }

    override fun firstItemText(): String? = text.also { contentReads++ }
  }

  private val textClip = ClipMeta(label = "note", isSensitive = false, hasText = true)

  private fun evaluate(enabled: Boolean, source: PrimaryClipSource) =
    ClipboardShareFilter.evaluate(enabled, SERVER_LABEL, source)

  @Test
  fun `sharing off - the clipboard is not touched at all`() {
    val source = FakeClipSource(textClip)
    assertEquals(Result.Skip(SkipReason.SHARING_OFF), evaluate(false, source))
    assertEquals(0, source.metaReads)
    assertEquals(0, source.contentReads)
  }

  @Test
  fun `sharing on - plain text is shared`() {
    val source = FakeClipSource(textClip, "Grüße")
    assertEquals(Result.Share("Grüße"), evaluate(true, source))
    assertEquals(1, source.contentReads)
  }

  @Test
  fun `own clip from the server is not sent back and not read`() {
    val source = FakeClipSource(textClip.copy(label = SERVER_LABEL))
    assertEquals(Result.Skip(SkipReason.FROM_SERVER), evaluate(true, source))
    assertEquals(0, source.contentReads)
  }

  @Test
  fun `sensitive clip (password manager) is not read`() {
    val source = FakeClipSource(textClip.copy(isSensitive = true))
    assertEquals(Result.Skip(SkipReason.SENSITIVE), evaluate(true, source))
    assertEquals(0, source.contentReads)
  }

  @Test
  fun `clip without text is not read`() {
    val source = FakeClipSource(textClip.copy(hasText = false))
    assertEquals(Result.Skip(SkipReason.NO_TEXT), evaluate(true, source))
    assertEquals(0, source.contentReads)
  }

  @Test
  fun `no clip, a vanished clip and blank text are skipped`() {
    assertEquals(Result.Skip(SkipReason.NO_CLIP), evaluate(true, FakeClipSource(null)))
    assertEquals(Result.Skip(SkipReason.NO_CLIP), evaluate(true, FakeClipSource(textClip, null)))
    assertEquals(Result.Skip(SkipReason.BLANK), evaluate(true, FakeClipSource(textClip, "  \n")))
  }

  private companion object {
    const val SERVER_LABEL = "deskflow_clipboard"
  }
}
