package skillbill.approval.model

sealed interface ApprovalMatchFacts {
  data class Command(val text: String) : ApprovalMatchFacts

  data class Paths(val relative: List<String>) : ApprovalMatchFacts
}

private const val COMMAND_SUBSTITUTION_OPEN = "\$("
private const val COMMAND_SUGGESTION_KEEP_TWO_TOKEN_MINIMUM = 3

private fun isUnsafeCommand(text: String): Boolean =
  text.any { it == ';' || it == '|' || it == '&' || it == '<' || it == '>' || it == '`' || it == '\n' || it == '\r' } ||
    COMMAND_SUBSTITUTION_OPEN in text

private fun isValidRelativePath(path: String): Boolean {
  if (path.isEmpty() || path.startsWith('/') || '\\' in path) return false
  return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
}

private fun patternOnlyWildcardsSlashesWhitespace(trimmed: String): Boolean =
  trimmed.isNotEmpty() && trimmed.all { it == '*' || it == '/' || it.isWhitespace() }

private fun pathPatternHasDotDotSegment(pattern: String): Boolean = pattern.split('/').any { it == ".." }

private fun patternShapeRefusal(
  trimmed: String,
  facts: ApprovalMatchFacts,
): ApprovalGrantRefusal? =
  when {
    trimmed.isBlank() -> ApprovalGrantRefusal.INVALID_PATTERN
    patternOnlyWildcardsSlashesWhitespace(trimmed) -> ApprovalGrantRefusal.INVALID_PATTERN
    facts is ApprovalMatchFacts.Command && isUnsafeCommand(trimmed) -> ApprovalGrantRefusal.INVALID_PATTERN
    facts is ApprovalMatchFacts.Paths && trimmed.startsWith('/') -> ApprovalGrantRefusal.INVALID_PATTERN
    facts is ApprovalMatchFacts.Paths && pathPatternHasDotDotSegment(trimmed) -> ApprovalGrantRefusal.INVALID_PATTERN
    else -> null
  }

private fun commandGlobMatches(
  pattern: String,
  text: String,
): Boolean {
  val regexBody =
    buildString {
      var index = 0
      while (index < pattern.length) {
        if (pattern[index] == '*') {
          append(".*")
          index++
        } else {
          val start = index
          while (index < pattern.length && pattern[index] != '*') index++
          append(Regex.escape(pattern.substring(start, index)))
        }
      }
    }
  return Regex("^$regexBody$").matches(text)
}

private fun pathGlobMatches(
  pattern: String,
  path: String,
): Boolean = pathGlobMatch(pattern, 0, path, 0)

private fun pathGlobMatch(
  pattern: String,
  patternIndex: Int,
  path: String,
  pathIndex: Int,
): Boolean =
  when {
    patternIndex == pattern.length -> pathIndex == path.length
    pattern.startsWith("**", patternIndex) -> matchDoubleStar(pattern, patternIndex, path, pathIndex)
    pattern[patternIndex] == '*' -> matchSingleStar(pattern, patternIndex, path, pathIndex)
    pathIndex < path.length && pattern[patternIndex] == path[pathIndex] ->
      pathGlobMatch(pattern, patternIndex + 1, path, pathIndex + 1)
    else -> false
  }

private fun matchDoubleStar(
  pattern: String,
  patternIndex: Int,
  path: String,
  pathIndex: Int,
): Boolean {
  val afterDoubleStar = patternIndex + 2
  return afterDoubleStar == pattern.length ||
    (pathIndex..path.length).any { suffixStart -> pathGlobMatch(pattern, afterDoubleStar, path, suffixStart) }
}

private fun matchSingleStar(
  pattern: String,
  patternIndex: Int,
  path: String,
  pathIndex: Int,
): Boolean {
  val limit = path.indexOf('/', pathIndex).let { index -> if (index < 0) path.length else index }
  return (pathIndex..limit).any { end -> pathGlobMatch(pattern, patternIndex + 1, path, end) }
}

fun ApprovalMatchFacts.matches(pattern: String): Boolean {
  val trimmed = pattern.trim()
  if (patternShapeRefusal(trimmed, this) != null) return false
  return when (this) {
    is ApprovalMatchFacts.Command ->
      !isUnsafeCommand(text) && commandGlobMatches(trimmed, text)
    is ApprovalMatchFacts.Paths ->
      relative.isNotEmpty() &&
        relative.all(::isValidRelativePath) &&
        relative.all { pathGlobMatches(trimmed, it) }
  }
}

fun validateApprovalPattern(
  pattern: String,
  facts: ApprovalMatchFacts,
): ApprovalGrantRefusal? {
  val trimmed = pattern.trim()
  patternShapeRefusal(trimmed, facts)?.let { return it }
  if (facts is ApprovalMatchFacts.Command && isUnsafeCommand(facts.text)) {
    return ApprovalGrantRefusal.UNSAFE_COMMAND
  }
  if (!facts.matches(trimmed)) {
    return ApprovalGrantRefusal.PATTERN_MISMATCH
  }
  return null
}

fun ApprovalMatchFacts?.patternEligibilityRefusal(): ApprovalGrantRefusal? =
  when {
    this == null -> ApprovalGrantRefusal.NO_MATCH_FACTS
    this is ApprovalMatchFacts.Command && isUnsafeCommand(text) -> ApprovalGrantRefusal.UNSAFE_COMMAND
    else -> null
  }

fun suggestApprovalPattern(facts: ApprovalMatchFacts): String? {
  val candidate =
    when (facts) {
      is ApprovalMatchFacts.Command -> commandPatternSuggestion(facts.text)
      is ApprovalMatchFacts.Paths -> pathsPatternSuggestion(facts.relative)
    } ?: return null
  return candidate.takeIf { validateApprovalPattern(it, facts) == null }
}

private fun commandPatternSuggestion(text: String): String? {
  if (isUnsafeCommand(text)) return null
  val trimmed = text.trim()
  val tokenRanges = mutableListOf<IntRange>()
  var index = 0
  while (index < trimmed.length) {
    if (trimmed[index].isWhitespace()) {
      index++
      continue
    }
    val start = index
    while (index < trimmed.length && !trimmed[index].isWhitespace()) index++
    tokenRanges.add(start until index)
  }
  return when {
    tokenRanges.size >= COMMAND_SUGGESTION_KEEP_TWO_TOKEN_MINIMUM ->
      trimmed.substring(0, tokenRanges[1].last + 1) + " *"
    tokenRanges.size == 2 ->
      trimmed.substring(0, tokenRanges[0].last + 1) + " *"
    else -> null
  }
}

private fun pathsPatternSuggestion(relative: List<String>): String? {
  if (relative.isEmpty() || relative.any { !isValidRelativePath(it) }) return null
  return when (relative.size) {
    1 -> singlePathPatternSuggestion(relative[0])
    else -> multiPathPatternSuggestion(relative)
  }
}

private fun singlePathPatternSuggestion(path: String): String? {
  val slashIndex = path.lastIndexOf('/')
  if (slashIndex > 0) {
    return path.substring(0, slashIndex) + "/**"
  }
  val name = if (slashIndex < 0) path else path.substring(slashIndex + 1)
  if ('.' !in name) return null
  val extension = name.substringAfterLast('.')
  return if (extension.isNotEmpty()) "*.$extension" else null
}

private fun multiPathPatternSuggestion(relative: List<String>): String? {
  val common = longestCommonPrefix(relative.map(::parentDirectorySegments)) ?: return null
  return common.joinToString("/") + "/**"
}

private fun parentDirectorySegments(path: String): List<String> {
  val slashIndex = path.lastIndexOf('/')
  if (slashIndex <= 0) return emptyList()
  return path.substring(0, slashIndex).split('/').filter { it.isNotEmpty() }
}

private fun longestCommonPrefix(segmentLists: List<List<String>>): List<String>? {
  if (segmentLists.isEmpty()) return null
  val limit = segmentLists.minOf { it.size }
  val common = mutableListOf<String>()
  for (position in 0 until limit) {
    val segment = segmentLists[0][position]
    if (segmentLists.all { it[position] == segment }) {
      common.add(segment)
    } else {
      break
    }
  }
  return common.takeIf { it.isNotEmpty() }
}
