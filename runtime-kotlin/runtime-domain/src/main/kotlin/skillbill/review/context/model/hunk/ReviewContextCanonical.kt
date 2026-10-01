package skillbill.review.context.model.hunk

internal val SHA256_HEX = Regex("[a-f0-9]{64}")

fun requireRepositoryRelativePath(path: String) = skillbill.review.model.requireRepositoryRelativePath(path)

private const val ASCII_PRINTABLE_FLOOR = 0x20

internal fun canonicalFields(vararg values: Any): String = canonicalFieldList(values.asList())

internal fun canonicalFieldList(values: List<Any>): String =
  values.joinToString("") { value ->
    val text = value.toString()
    "${text.toByteArray(Charsets.UTF_8).size}:$text"
  }

fun structuredString(value: String): String =
  buildString {
    append('"')
    value.forEach { char ->
      when (char) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\b' -> append("\\b")
        '\u000c' -> append("\\f")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else ->
          if (char.code < ASCII_PRINTABLE_FLOOR) {
            append("\\u%04x".format(char.code))
          } else {
            append(char)
          }
      }
    }
    append('"')
  }
