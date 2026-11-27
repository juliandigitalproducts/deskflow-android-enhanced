// SPDX-License-Identifier: MIT
// Copyright (c) 2026 juliandigitalproducts

package org.tfv.deskflow.logging

/**
 * Log lines for places that see typed text (DSK-R6/R12): only sizes, positions and types – never the field
 * content (it can be a password) or a key id (it is the typed character).
 */
object SafeLogText {
  fun extractedText(calledBy: String, text: CharSequence?, selectionStart: Int?, selectionEnd: Int?): String =
    "$calledBy >> Extracted text (${text?.length ?: 0} chars) — selection: [$selectionStart, $selectionEnd]"

  fun selectedText(calledBy: String, selected: CharSequence?): String =
    "$calledBy >> Selected text (${selected?.length ?: 0} chars)"

  fun incomingMessage(className: String): String = "onMessage($className) -> ClientEventBus"
}
