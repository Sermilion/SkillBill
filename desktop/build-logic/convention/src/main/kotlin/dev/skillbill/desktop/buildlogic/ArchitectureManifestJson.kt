package dev.skillbill.desktop.buildlogic

internal fun renderJson(value: Any?): String = buildString { appendJson(value) }

private fun StringBuilder.appendJson(value: Any?) {
  when (value) {
    null -> append("null")
    is String -> appendQuoted(value)
    is Boolean, is Number -> append(value.toString())
    is Map<*, *> -> {
      append('{')
      value.entries.sortedBy { it.key.toString() }.forEachIndexed { index, entry ->
        if (index > 0) append(',')
        appendQuoted(entry.key.toString())
        append(':')
        appendJson(entry.value)
      }
      append('}')
    }
    is Iterable<*> -> {
      append('[')
      value.forEachIndexed { index, element ->
        if (index > 0) append(',')
        appendJson(element)
      }
      append(']')
    }
    else -> error("Cannot render ${value::class.java.name} as architecture manifest JSON.")
  }
}

private fun StringBuilder.appendQuoted(text: String) {
  append('"')
  text.forEach { char ->
    when {
      char == '"' -> append("\\\"")
      char == '\\' -> append("\\\\")
      char == '\n' -> append("\\n")
      char == '\r' -> append("\\r")
      char == '\t' -> append("\\t")
      char < ' ' -> append("\\u%04x".format(char.code))
      else -> append(char)
    }
  }
  append('"')
}
