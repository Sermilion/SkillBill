package dev.skillbill.desktop.feature.detail

import java.nio.file.InvalidPathException
import java.nio.file.Paths

internal actual fun parseRecordedPath(recorded: String): ParsedRecordedPath? =
  try {
    val path = Paths.get(recorded)
    ParsedRecordedPath(path.isAbsolute, path.normalize().toString())
  } catch (_: InvalidPathException) {
    null
  }

internal actual fun resolveAgainstBase(base: String, relative: String): RecordedReceiptPath? =
  try {
    val basePath = Paths.get(base).normalize()
    if (!basePath.isAbsolute) return RecordedReceiptPath(relative, openable = false)
    val resolved = basePath.resolve(relative).normalize()
    if (!resolved.startsWith(basePath)) RecordedReceiptPath(relative, openable = false)
    else RecordedReceiptPath(resolved.toString(), openable = true)
  } catch (_: InvalidPathException) {
    RecordedReceiptPath(relative, openable = false)
  }
