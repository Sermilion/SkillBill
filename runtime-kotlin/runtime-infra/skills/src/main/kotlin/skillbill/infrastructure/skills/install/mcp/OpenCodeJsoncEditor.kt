package skillbill.infrastructure.skills.install.mcp

import com.fasterxml.jackson.databind.ObjectMapper

internal object OpenCodeJsoncEditor {
  private val stringMapper = ObjectMapper()

  data class Property(
    val key: String,
    val keyStart: Int,
    val valueStart: Int,
    val valueEnd: Int,
    val followingComma: Int?,
  )

  data class ObjectSpan(
    val start: Int,
    val end: Int,
    val properties: List<Property>,
    val trailingComma: Int?,
  )

  private data class Token(val text: String, val start: Int, val end: Int, val kind: Kind)

  private enum class Kind { STRING, SYMBOL, VALUE }

  fun objectAt(
    source: String,
    propertyPath: List<String>,
  ): ObjectSpan? {
    val tokens = tokenize(source)
    if (tokens.isEmpty() || tokens.first().text != "{") return null
    var current = readObject(tokens, 0)
    propertyPath.forEach { key ->
      val property = current.properties.singleOrNull { it.key == key } ?: return null
      val valueToken = tokens.firstOrNull { it.start == property.valueStart && it.text == "{" } ?: return null
      current = readObject(tokens, tokens.indexOf(valueToken))
    }
    return current
  }

  fun insertProperty(
    source: String,
    objectSpan: ObjectSpan,
    key: String,
    value: String,
  ): String {
    val newline = if (source.contains("\r\n")) "\r\n" else "\n"
    val parentIndent = lineIndent(source, objectSpan.start)
    val childIndent =
      objectSpan.properties.firstOrNull()?.let { lineIndent(source, it.keyStart) }
        ?.takeIf { it.length > parentIndent.length } ?: parentIndent + "  "
    val rendered = "$childIndent\"$key\": $value$newline$parentIndent"
    val insertion = objectSpan.end
    val prefix = if (objectSpan.properties.isNotEmpty() && objectSpan.trailingComma == null) "," else ""
    return source.substring(0, insertion) + prefix + newline + rendered + source.substring(insertion)
  }

  fun removeProperty(
    source: String,
    objectSpan: ObjectSpan,
    property: Property,
  ): String {
    val precedingProperty = objectSpan.properties.getOrNull(objectSpan.properties.indexOf(property) - 1)
    val edits = mutableListOf<IntRange>()
    edits += property.keyStart until property.valueEnd
    when {
      property.followingComma != null -> edits += property.followingComma until property.followingComma + 1
      precedingProperty?.followingComma != null ->
        edits += precedingProperty.followingComma until precedingProperty.followingComma + 1
    }
    return edits.sortedByDescending(IntRange::first).fold(source) { result, range ->
      result.removeRange(range.first, range.last + 1)
    }
  }

  private fun readObject(
    tokens: List<Token>,
    openIndex: Int,
  ): ObjectSpan {
    val open = tokens[openIndex]
    check(open.text == "{")
    val properties = mutableListOf<Property>()
    var cursor = openIndex + 1
    var trailingComma: Int? = null
    while (tokens[cursor].text != "}") {
      val keyToken = tokens[cursor]
      check(keyToken.kind == Kind.STRING)
      val key = decodeString(keyToken.text)
      check(tokens[cursor + 1].text == ":")
      val valueStartIndex = cursor + 2
      val valueEndIndex = valueEndIndex(tokens, valueStartIndex)
      val valueStart = tokens[valueStartIndex].start
      val valueEnd = tokens[valueEndIndex].end
      val commaIndex = valueEndIndex + 1
      val comma = if (tokens[commaIndex].text == ",") tokens[commaIndex].start else null
      properties += Property(key, keyToken.start, valueStart, valueEnd, comma)
      cursor = if (comma != null) commaIndex + 1 else valueEndIndex + 1
      if (comma != null && tokens[cursor].text == "}") trailingComma = comma
    }
    return ObjectSpan(open.start, tokens[cursor].start, properties, trailingComma)
  }

  private fun valueEndIndex(
    tokens: List<Token>,
    start: Int,
  ): Int {
    val first = tokens[start]
    if (first.text != "{" && first.text != "[") return start
    val stack = ArrayDeque<String>()
    stack.addLast(if (first.text == "{") "}" else "]")
    var index = start + 1
    while (stack.isNotEmpty()) {
      when (tokens[index].text) {
        "{" -> stack.addLast("}")
        "[" -> stack.addLast("]")
        "}", "]" -> {
          check(tokens[index].text == stack.removeLast())
        }
      }
      index++
    }
    return index - 1
  }

  private fun tokenize(source: String): List<Token> {
    val result = mutableListOf<Token>()
    var index = 0
    while (index < source.length) {
      when {
        source[index].isWhitespace() -> index++
        source.startsWith("//", index) -> {
          index = source.indexOf('\n', index).let { if (it < 0) source.length else it + 1 }
        }
        source.startsWith("/*", index) -> {
          val end = source.indexOf("*/", index + 2)
          check(end >= 0)
          index = end + 2
        }
        source[index] == '"' -> {
          val start = index
          index = stringEnd(source, start)
          result += Token(source.substring(start, index), start, index, Kind.STRING)
        }
        source[index] in "{}[]:," -> {
          result += Token(source[index].toString(), index, index + 1, Kind.SYMBOL)
          index++
        }
        else -> {
          val start = index
          while (isValueCharacter(source, index)) index++
          check(index > start)
          result += Token(source.substring(start, index), start, index, Kind.VALUE)
        }
      }
    }
    return result
  }

  private fun stringEnd(
    source: String,
    start: Int,
  ): Int {
    var index = start + 1
    while (index < source.length) {
      if (source[index] == '\\') {
        index += 2
      } else if (source[index++] == '"') {
        break
      }
    }
    check(source.getOrNull(index - 1) == '"')
    return index
  }

  private fun isValueCharacter(
    source: String,
    index: Int,
  ): Boolean =
    index < source.length && !source[index].isWhitespace() && source[index] !in "{}[]:,\"" &&
      !isCommentStart(source, index)

  private fun isCommentStart(
    source: String,
    index: Int,
  ): Boolean = source.startsWith("//", index) || source.startsWith("/*", index)

  private fun decodeString(value: String): String = stringMapper.readValue(value, String::class.java)

  private fun lineIndent(
    source: String,
    offset: Int,
  ): String {
    val lineStart = source.lastIndexOf('\n', offset).let { if (it < 0) 0 else it + 1 }
    return source.substring(lineStart, offset).takeWhile(Char::isWhitespace)
  }
}
