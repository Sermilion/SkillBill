package skillbill.workflow.taskruntime.model.blockincident.report

internal object ProductReportRedaction {
  private const val REDACTION_MARKER = "[redacted]"
  private val patterns =
    listOf(
      Regex("(?i)\\b(?:github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,})\\b"),
      Regex("(?i)\\bsk-(?:ant-)?[A-Za-z0-9_-]{20,}\\b"),
      Regex("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
      Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+"),
      Regex("(?i)\\b(?:key|token|secret)\\s*=\\s*[^\\s,;]+"),
      Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b"),
      Regex("(?i)(?:/home/[^\\s,;]+|/Users/[^\\s,;]+|[A-Z]:\\\\Users\\\\[^\\s,;]+)"),
    )
  private val frameLocation =
    Regex(
      "(?:\\((?:Unknown Source|Native Method|[^\\r\\n()]*:[+-]?\\d+)\\)|:[+-]?\\d+)(?=$|[\\s\\\"'`\\[\\]{}(),;:.!?])",
    )
  private val hexRun = Regex("(?i)\\b[0-9a-f]{6,}\\b")
  private val digits = Regex("\\d+")
  private val quotedPaths =
    Regex(
      "[\"'](?:[a-z]:[\\\\/]|\\\\\\\\|//|/|\\.{1,2}[\\\\/]|[A-Za-z0-9._-]+[\\\\/])" +
        "[^\"'\\r\\n]+[\"']",
      RegexOption.IGNORE_CASE,
    )
  private val paths =
    listOf(
      Regex("(?i)(?<![\\w])[a-z]:[\\\\/](?:[^\\s\\\\/]+[\\\\/])*[^\\s\\\\/]*"),
      Regex("(?i)(?<![\\w:])(?:\\\\\\\\|//)[^\\s\\\\/]+[\\\\/][^\\s\\\\/]+(?:[\\\\/][^\\s\\\\/]+)*"),
      Regex("(?<![\\w:])/(?:[^\\s/:]+/)*[^\\s,;:/]+(?:/[^\\s,;:/]+)*"),
      Regex("(?<![\\w])(?:\\.{1,2}[\\\\/])(?:[^\\s\\\\/]+[\\\\/])*[^\\s\\\\/]*"),
      Regex("(?<![\\w./])(?:[A-Za-z0-9._-]+[\\\\/])+[A-Za-z0-9._-]+"),
    )

  fun redact(value: String): Pair<String, Boolean> {
    var result = replaceForeignFrames(value)
    patterns.forEach { pattern -> result = pattern.replace(result, REDACTION_MARKER) }
    return result to (result != value)
  }

  fun hasResidualToken(value: String): Boolean = patterns.any { it.containsMatchIn(value) }

  fun hasResidualSecret(value: String): Boolean = hasResidualToken(value) || hasForeignFrame(value)

  fun normalizeCause(
    value: String,
    identities: Collection<String>,
  ): String {
    var normalized = value.lowercase()
    identities
      .asSequence()
      .filter(String::isNotBlank)
      .distinct()
      .sortedByDescending(String::length)
      .forEach { identity -> normalized = Regex(Regex.escape(identity.lowercase())).replace(normalized, " ") }
    quotedPaths.findAll(normalized).toList().asReversed().forEach { match ->
      normalized = normalized.replaceRange(match.range, " ")
    }
    paths.forEach { normalized = it.replace(normalized, " ") }
    normalized = hexRun.replace(normalized, " ")
    normalized = digits.replace(normalized, " ")
    return normalized.replace(Regex("\\s+"), " ").trim()
  }

  private data class Frame(
    val range: IntRange,
    val declaringClass: String,
    val ambiguous: Boolean,
  )

  private data class FrameHead(
    val className: String,
    val methodName: String,
    val contentStart: Int,
    val ambiguous: Boolean,
  )

  private data class ClassName(
    val value: String,
    val ambiguous: Boolean,
  )

  private fun replaceForeignFrames(value: String): String {
    val frames = enumerateFrames(value)
    if (frames.isEmpty()) return value
    val result = StringBuilder(value)
    frames.asReversed().forEach { frame ->
      if (frame.ambiguous || !isSkillBillFrame(frame.declaringClass)) {
        result.replace(frame.range.first, frame.range.last + 1, REDACTION_MARKER)
      }
    }
    return result.toString()
  }

  private fun hasForeignFrame(value: String): Boolean =
    enumerateFrames(value).any { it.ambiguous || !isSkillBillFrame(it.declaringClass) }

  private fun enumerateFrames(value: String): List<Frame> =
    buildList {
      var nextCandidateStart = 0
      frameLocation.findAll(value).forEach { location ->
        val candidateStart = skipEntryPrefix(value, nextCandidateStart, location.range.first)
        val head =
          parseFrameHead(value, candidateStart, location.range.first, location.range.last)
            ?: return@forEach
        val className = parseDeclaringClass(head.className)
        val ambiguous =
          head.ambiguous || className.ambiguous || !isValidFrameHead(className.value, head.methodName)
        val frameStart = if (ambiguous) candidateStart else head.contentStart
        add(Frame(frameStart..location.range.last, className.value, ambiguous))
        nextCandidateStart = location.range.last + 1
      }
    }

  private fun skipEntryPrefix(
    value: String,
    start: Int,
    end: Int,
  ): Int {
    var index = start
    while (index < end) {
      when {
        value.startsWith(REDACTION_MARKER, index) && index + REDACTION_MARKER.length <= end ->
          index += REDACTION_MARKER.length
        value.startsWith("Suspected: ", index) -> index += "Suspected: ".length
        value.startsWith("at ", index) -> index += "at ".length
        value[index].isWhitespace() || value[index] in ",.;:!?()[]{}\"'`" -> index++
        else -> return index
      }
    }
    return index
  }

  private fun parseFrameHead(
    value: String,
    candidateStart: Int,
    headEnd: Int,
    locationEnd: Int,
  ): FrameHead? {
    var contentStart = candidateStart
    var ambiguous = false
    if (value.startsWith("Suspected: ", contentStart)) contentStart += "Suspected: ".length
    val opener = value.getOrNull(contentStart)
    val closer =
      when (opener) {
        '"', '\'', '`' -> opener
        '[' -> ']'
        '(' -> ')'
        '{' -> '}'
        else -> null
      }
    if (closer != null) {
      if (value.getOrNull(locationEnd + 1) == closer) {
        contentStart++
      } else {
        ambiguous = true
      }
    }
    if (value.startsWith("at ", contentStart)) contentStart += "at ".length
    val head = value.substring(contentStart, headEnd)
    val separator = head.lastIndexOf('.')
    if (separator <= 0 || separator == head.lastIndex) return null
    val className = head.substring(0, separator)
    val methodName = head.substring(separator + 1)
    if (className.isBlank()) return null
    return FrameHead(className, methodName, contentStart, ambiguous)
  }

  private fun parseDeclaringClass(frameClass: String): ClassName {
    val slashCount = frameClass.count { it == '/' }
    if (slashCount == 0 || Regex("/0x[0-9a-fA-F]+$").containsMatchIn(frameClass)) return ClassName(frameClass, false)
    if (slashCount == 1) {
      val module = frameClass.substringBefore('/')
      val className = frameClass.substringAfter('/')
      val recognizedModule = module.matches(Regex("[A-Za-z0-9_.@-]+")) && className.contains('.')
      return if (recognizedModule) ClassName(className, false) else ClassName(frameClass, true)
    }
    if (slashCount == 2) {
      val loader = frameClass.substringBefore('/')
      val remainder = frameClass.substringAfter('/')
      val module = remainder.substringBefore('/')
      val className = remainder.substringAfter('/')
      val recognizedLoader = loader.matches(Regex("[A-Za-z0-9_.$-]+"))
      val recognizedModule = module.isEmpty() || module.matches(Regex("[A-Za-z0-9_.@-]+"))
      return if (recognizedLoader && recognizedModule && className.contains('.')) {
        ClassName(className, false)
      } else {
        ClassName(frameClass, true)
      }
    }
    return ClassName(frameClass, true)
  }

  private fun isValidFrameHead(
    className: String,
    methodName: String,
  ): Boolean {
    val validMethod =
      methodName == "<init>" || methodName == "<clinit>" || methodName.none { it in ".;[/<>" }
    return validMethod && className.none { it in ";[" }
  }

  private fun isSkillBillFrame(className: String): Boolean = className.startsWith("skillbill.")
}
