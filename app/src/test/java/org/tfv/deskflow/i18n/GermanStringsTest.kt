// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** U5 from the emulator test: every user-facing string has a German translation with the same placeholders. */
class GermanStringsTest {

  private class Str(val text: String, val translatable: Boolean)

  private fun read(dir: String): Map<String, Str> {
    // Unit tests run with the module directory (app/) as working directory.
    val file = File("src/main/res/$dir/strings.xml")
    assertTrue("$file not found", file.isFile)
    val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    val nodes = doc.getElementsByTagName("string")
    return (0 until nodes.length)
      .map { nodes.item(it) as Element }
      .associate { it.getAttribute("name") to Str(it.textContent, it.getAttribute("translatable") != "false") }
  }

  private val english = read("values")
  private val german = read("values-de")

  private fun placeholders(text: String) =
    Regex("""%(\d+\$)?[sd]|%%""").findAll(text).map { it.value }.sorted().toList()

  @Test
  fun `every translatable string is translated`() {
    val missing = english.filterValues { it.translatable }.keys - german.keys
    assertEquals("missing German strings", emptySet<String>(), missing)
  }

  @Test
  fun `no stale or untranslatable strings in German`() {
    val stale = german.keys - english.filterValues { it.translatable }.keys
    assertEquals("German strings without an English source (or marked untranslatable)", emptySet<String>(), stale)
  }

  @Test
  fun `placeholders match`() {
    for ((name, de) in german) {
      assertEquals(name, placeholders(english.getValue(name).text), placeholders(de.text))
    }
  }

  @Test
  fun `the German texts are not just copies of the English ones`() {
    val same =
      german.filter { (name, de) -> de.text == english.getValue(name).text }.keys -
        // Identical in both languages.
        setOf("logo_content_desc", "app_toolbar_logo_desc", "settings_screen_screen_port_label", "app_toolbar_button_start")
    assertEquals(emptySet<String>(), same)
  }
}
