// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.e2e

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tfv.deskflow.components.AndroidPrimaryClipSource

/** DSK-R11 on the real Android clip classes (no system clipboard access needed). */
@RunWith(AndroidJUnit4::class)
class ClipSourceTest {

  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  private fun source(clip: ClipData?) = AndroidPrimaryClipSource(context, { clip?.description }, { clip })

  @Test
  fun mixedClipWithOneItemGivesTheTextOfItemZero() {
    // Before: the MIME index (1) was used as item index → IndexOutOfBoundsException.
    val clip = ClipData(ClipDescription("mixed", arrayOf("image/png", ClipDescription.MIMETYPE_TEXT_PLAIN)), ClipData.Item("hello"))
    val meta = source(clip).meta()!!
    assertTrue(meta.hasText)
    assertEquals("hello", source(clip).firstItemText())
  }

  @Test
  fun sensitiveFlagAndLabelAreReadFromTheDescription() {
    val description =
      ClipDescription("pw", arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN)).apply {
        extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
      }
    val meta = source(ClipData(description, ClipData.Item("secret"))).meta()!!
    assertTrue(meta.isSensitive)
    assertEquals("pw", meta.label)
  }

  @Test
  fun imageOnlyClipHasNoText() {
    val clip = ClipData(ClipDescription("img", arrayOf("image/png")), ClipData.Item(android.net.Uri.parse("content://x/1")))
    assertFalse(source(clip).meta()!!.hasText)
  }

  @Test
  fun noClipGivesNothing() {
    assertNull(source(null).meta())
    assertNull(source(null).firstItemText())
  }
}
