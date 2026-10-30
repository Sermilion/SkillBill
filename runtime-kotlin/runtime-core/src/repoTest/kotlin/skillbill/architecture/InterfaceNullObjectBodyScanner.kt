package skillbill.architecture

internal object InterfaceNullObjectBodyScanner {
  private const val RAW_QUOTE = "\"\"\""
  private const val MAX_CHAR_LITERAL_SPAN = 7
  private const val BLOCK_COMMENT_START = "/*"
  private const val BLOCK_COMMENT_END = "*/"
  private const val NEWLINE = '\n'
  private const val ARROW_OR_COMPARISON_PREFIXES = "-!<>="

  private val interfaceHeader =
    Regex("""(?m)^[ \t]*(?:(?:public|internal|private|protected|sealed|fun)\s+)*interface\s+(\w+)""")
  private val memberStart =
    Regex(
      """^[ \t]*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:override|abstract|open|public|internal|protected|private|""" +
        """suspend|operator|inline|infix|tailrec|external|const|lateinit|final)\s+)*(?:fun|val|var)\b""",
    )
  private val nonMemberStart =
    Regex(
      """^[ \t]*(?:(?:public|internal|private|protected|sealed|data|enum|value|fun|annotation|abstract|open|""" +
        """inner)\s+)*(?:companion|interface|class|object|typealias|init)\b|^[ \t]*@\w+(?:\([^)]*\))?[ \t]*$""",
    )
  private val headerEndingDeclaration =
    Regex(
      """^[ \t]*(?:(?:public|internal|private|protected|override|abstract|open)\s+)*""" +
        """(?:fun|val|var|import|package)\b""",
    )
  private val keyword = Regex("""\b(fun|val|var)\b""")
  private val genericStart = Regex("""\s*<""")
  private val functionName = Regex("""(\w+)\s*\(""")
  private val propertyName = Regex("""(\w+)""")
  private val getterStart = Regex("""\bget\s*\(\s*\)""")
  private val bodyStart = Regex("""[={]""")

  fun scan(source: String): List<String> {
    val structure = blankedCopy(source, blankStrings = true)
    val text = blankedCopy(source, blankStrings = false)
    return interfaceHeader.findAll(structure).flatMap { header ->
      val open = structure.indexOf('{', header.range.last + 1)
      if (open < 0 || headerEndsBeforeBody(structure.substring(header.range.last + 1, open))) {
        emptySequence()
      } else {
        val close = matchingClose(structure, open, '{', '}')
        membersOf(structure.substring(open + 1, close), text.substring(open + 1, close))
          .mapNotNull { (memberStructure, memberText) -> nullObjectBody(memberStructure, memberText) }
          .map { (member, form) -> "${header.groupValues[1]}.$member ($form)" }
          .asSequence()
      }
    }.toList()
  }

  private fun headerEndsBeforeBody(header: String): Boolean =
    header.lines().drop(1).any { line -> headerEndingDeclaration.containsMatchIn(line) }

  private fun membersOf(
    structure: String,
    text: String,
  ): List<Pair<String, String>> {
    val members = mutableListOf<Pair<StringBuilder, StringBuilder>>()
    var current: Pair<StringBuilder, StringBuilder>? = null
    var depth = 0
    structure.lines().zip(text.lines()).forEach { (structureLine, textLine) ->
      if (depth == 0 && nonMemberStart.containsMatchIn(structureLine)) {
        current = null
      } else if (depth == 0 && memberStart.containsMatchIn(structureLine)) {
        val member = StringBuilder(structureLine) to StringBuilder(textLine)
        members += member
        current = member
      } else {
        current?.let { (memberStructure, memberText) ->
          memberStructure.append(NEWLINE).append(structureLine)
          memberText.append(NEWLINE).append(textLine)
        }
      }
      depth += structureLine.count { it == '{' } - structureLine.count { it == '}' }
    }
    return members.map { (memberStructure, memberText) -> memberStructure.toString() to memberText.toString() }
  }

  private fun nullObjectBody(
    structure: String,
    text: String,
  ): Pair<String, InterfaceNullObjectBodyForm>? {
    val declaration = keyword.find(structure) ?: return null
    val from = declaration.range.last + 1
    val signature =
      if (declaration.groupValues[1] == "fun") functionSignature(structure, from) else getterSignature(structure, from)
    return signature?.let { (name, signatureEnd) ->
      bodyForm(structure, text, signatureEnd)?.let { form -> name to form }
    }
  }

  private fun functionSignature(
    structure: String,
    from: Int,
  ): Pair<String, Int>? {
    val generics = genericStart.matchAt(structure, from)
    val afterGenerics = generics?.let { matchingClose(structure, it.range.last, '<', '>') + 1 } ?: from
    val name = functionName.find(structure, afterGenerics) ?: return null
    return name.groupValues[1] to matchingClose(structure, name.range.last, '(', ')') + 1
  }

  private fun getterSignature(
    structure: String,
    from: Int,
  ): Pair<String, Int>? {
    val name = propertyName.find(structure, from) ?: return null
    val getter = getterStart.find(structure, name.range.last + 1) ?: return null
    return name.groupValues[1] to getter.range.last + 1
  }

  private fun bodyForm(
    structure: String,
    text: String,
    signatureEnd: Int,
  ): InterfaceNullObjectBodyForm? {
    val start =
      bodyStart.findAll(structure, signatureEnd).firstOrNull { !isArrowOrComparison(structure, it.range.first) }
        ?: return null
    val index = start.range.first
    return if (structure[index] == '=') {
      InterfaceNullObjectBodyForm.classify(text.substring(index + 1).trim())
    } else {
      val statement = text.substring(index + 1, matchingClose(structure, index, '{', '}')).trim()
      InterfaceNullObjectBodyForm.classifyBlockStatement(statement)
    }
  }

  private fun isArrowOrComparison(
    structure: String,
    index: Int,
  ): Boolean =
    structure[index] == '=' &&
      (
        structure.getOrNull(index - 1)?.let { it in ARROW_OR_COMPARISON_PREFIXES } == true ||
          structure.getOrNull(index + 1) == '='
      )

  private fun matchingClose(
    structure: String,
    openIndex: Int,
    open: Char,
    close: Char,
  ): Int {
    var depth = 0
    for (index in openIndex until structure.length) {
      when (structure[index]) {
        open -> depth++
        close -> if (--depth == 0) return index
      }
    }
    return structure.length
  }

  private fun blankedCopy(
    source: String,
    blankStrings: Boolean,
  ): String {
    val copy = StringBuilder(source)
    var index = 0
    while (index < source.length) {
      val span = blankSpan(source, index, blankStrings)
      for (position in span.blankFrom until span.blankTo) {
        if (copy[position] != NEWLINE) copy[position] = ' '
      }
      index = span.next
    }
    return copy.toString()
  }

  private class BlankSpan(
    val blankFrom: Int,
    val blankTo: Int,
    val next: Int,
  )

  private fun blankSpan(
    source: String,
    index: Int,
    blankStrings: Boolean,
  ): BlankSpan =
    when {
      source.startsWith("//", index) -> lineEnd(source, index).let { end -> BlankSpan(index, end, end) }
      source.startsWith(BLOCK_COMMENT_START, index) ->
        source.indexOf(BLOCK_COMMENT_END, index + BLOCK_COMMENT_START.length).let { close ->
          val end = if (close < 0) source.length else close + BLOCK_COMMENT_END.length
          BlankSpan(index, end, end)
        }
      source.startsWith(RAW_QUOTE, index) -> rawStringSpan(source, index, blankStrings)
      source[index] == '"' || source[index] == '\'' -> quotedSpan(source, index, blankStrings)
      else -> BlankSpan(index, index, index + 1)
    }

  private fun rawStringSpan(
    source: String,
    index: Int,
    blankStrings: Boolean,
  ): BlankSpan {
    val contentStart = index + RAW_QUOTE.length
    val close = source.indexOf(RAW_QUOTE, contentStart).let { found -> if (found < 0) source.length else found }
    val blankTo = if (blankStrings) close else contentStart
    return BlankSpan(contentStart, blankTo, minOf(source.length, close + RAW_QUOTE.length))
  }

  private fun quotedSpan(
    source: String,
    index: Int,
    blankStrings: Boolean,
  ): BlankSpan {
    val quote = source[index]
    var close = index + 1
    while (close < source.length && source[close] != quote && source[close] != NEWLINE) {
      if (source[close] == '\\') close++
      close++
    }
    val closed = close < source.length && source[close] == quote
    val isLiteral = closed && (quote == '"' || close - index <= MAX_CHAR_LITERAL_SPAN)
    return if (isLiteral) {
      BlankSpan(index + 1, if (blankStrings) close else index + 1, close + 1)
    } else {
      BlankSpan(index, index, index + 1)
    }
  }

  private fun lineEnd(
    source: String,
    from: Int,
  ): Int = source.indexOf(NEWLINE, from).let { end -> if (end < 0) source.length else end }
}
