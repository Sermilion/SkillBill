package dev.skillbill.desktop.app.command

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private const val COPY_PREVIEW_LENGTH = 90

fun copyToClipboard(text: String) {
  val selection = StringSelection(text)
  Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
}

/** The copied text as the "Copied" toast shows it. */
fun copyPreview(text: String): String = if (text.length <= COPY_PREVIEW_LENGTH) text else text.take(COPY_PREVIEW_LENGTH) + "…"
