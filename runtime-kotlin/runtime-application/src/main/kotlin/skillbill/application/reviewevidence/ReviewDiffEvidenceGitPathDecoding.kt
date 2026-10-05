package skillbill.application.reviewevidence

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import skillbill.review.model.repositoryRelativePathViolation

private const val REVIEW_DIFF_GIT_OCTAL_WIDTH = 3
private const val REVIEW_DIFF_GIT_OCTAL_RADIX = 8

internal fun parseReviewDiffGitTokens(value: String): ReviewDiffParseOutcome<List<String>> {
  val tokens = mutableListOf<String>()
  var index = 0
  while (index < value.length) {
    while (index < value.length && value[index].isWhitespace()) index++
    if (index == value.length) break
    val start = index
    if (value[index] == '"') {
      index++
      var closed = false
      while (index < value.length) {
        if (value[index] == '\\') {
          if (index + 1 >= value.length) {
            return ReviewDiffParseOutcome.Rejected("Malformed quoted Git path ends with an escape.")
          }
          index += 2
        } else if (value[index++] == '"') {
          closed = true
          break
        }
      }
      if (!closed) return ReviewDiffParseOutcome.Rejected("Malformed quoted Git path is missing its closing quote.")
    } else {
      while (index < value.length && !value[index].isWhitespace()) index++
    }
    tokens += value.substring(start, index)
  }
  return ReviewDiffParseOutcome.Accepted(tokens)
}

internal fun reviewDiffRepositoryPath(
  value: String,
  prefix: String?,
): ReviewDiffParseOutcome<String?> {
  if (value.trim() == "/dev/null") return ReviewDiffParseOutcome.Accepted(null)
  val path = when (val result = decodeReviewDiffGitPath(value)) {
    is ReviewDiffParseOutcome.Accepted -> result.value
    is ReviewDiffParseOutcome.Rejected -> return result
  }
  val relativePath =
    if (prefix == null) {
      path
    } else {
      if (!path.startsWith(prefix)) {
        return ReviewDiffParseOutcome.Rejected("Git path source must carry the '$prefix' prefix.")
      }
      path.removePrefix(prefix)
    }
  val violation = repositoryRelativePathViolation(relativePath)
  if (
    relativePath.isBlank() || relativePath.startsWith('/') || ".." in relativePath.split('/')
  ) {
    return ReviewDiffParseOutcome.Rejected("Malformed Git diff record has a non-repository path '$relativePath'.")
  }
  if (violation != null) return ReviewDiffParseOutcome.Rejected(violation)
  return ReviewDiffParseOutcome.Accepted(relativePath)
}

private fun decodeReviewDiffGitPath(value: String): ReviewDiffParseOutcome<String> {
  val trimmed = value.trim()
  if (!(trimmed.startsWith('"') && trimmed.endsWith('"'))) return ReviewDiffParseOutcome.Accepted(trimmed)
  return decodeReviewDiffQuotedGitPath(trimmed.substring(1, trimmed.length - 1))
}

private fun decodeReviewDiffQuotedGitPath(body: String): ReviewDiffParseOutcome<String> {
  val decoded = StringBuilder()
  var index = 0
  while (index < body.length) {
    when (val next = decodeReviewDiffQuotedGitPathSegment(body, index, decoded)) {
      is ReviewDiffParseOutcome.Accepted -> index = next.value
      is ReviewDiffParseOutcome.Rejected -> return next
    }
  }
  return ReviewDiffParseOutcome.Accepted(decoded.toString())
}

private fun decodeReviewDiffQuotedGitPathSegment(
  body: String,
  index: Int,
  decoded: StringBuilder,
): ReviewDiffParseOutcome<Int> {
  if (body[index] != '\\') {
    decoded.append(body[index])
    return ReviewDiffParseOutcome.Accepted(index + 1)
  }
  val octal = consumeReviewDiffGitOctalBytes(body, index)
  if (octal != null) {
    when (val result = decodeReviewDiffGitOctalUtf8(octal.bytes)) {
      is ReviewDiffParseOutcome.Accepted -> decoded.append(result.value)
      is ReviewDiffParseOutcome.Rejected -> return result
    }
    return ReviewDiffParseOutcome.Accepted(octal.nextIndex)
  }
  val escapedIndex = index + 1
  if (escapedIndex >= body.length) {
    return ReviewDiffParseOutcome.Rejected("Malformed quoted Git path ends with an escape.")
  }
  when (val result = decodeReviewDiffGitEscapeChar(body[escapedIndex])) {
    is ReviewDiffParseOutcome.Accepted -> decoded.append(result.value)
    is ReviewDiffParseOutcome.Rejected -> return result
  }
  return ReviewDiffParseOutcome.Accepted(escapedIndex + 1)
}

private data class ReviewDiffGitOctalBytes(val bytes: ByteArray, val nextIndex: Int)

private fun consumeReviewDiffGitOctalBytes(
  body: String,
  startIndex: Int,
): ReviewDiffGitOctalBytes? {
  val bytes = ByteArrayOutputStream()
  var index = startIndex
  while (index + REVIEW_DIFF_GIT_OCTAL_WIDTH < body.length && body[index] == '\\' &&
    body.substring(index + 1, index + 1 + REVIEW_DIFF_GIT_OCTAL_WIDTH).all { it in '0'..'7' }
  ) {
    bytes.write(body.substring(index + 1, index + 1 + REVIEW_DIFF_GIT_OCTAL_WIDTH).toInt(REVIEW_DIFF_GIT_OCTAL_RADIX))
    index += REVIEW_DIFF_GIT_OCTAL_WIDTH + 1
  }
  return if (bytes.size() > 0) ReviewDiffGitOctalBytes(bytes.toByteArray(), index) else null
}

private fun decodeReviewDiffGitOctalUtf8(raw: ByteArray): ReviewDiffParseOutcome<CharSequence> {
  val decoder =
    Charsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
  return try {
    ReviewDiffParseOutcome.Accepted(decoder.decode(ByteBuffer.wrap(raw)))
  } catch (_: CharacterCodingException) {
    ReviewDiffParseOutcome.Rejected("Quoted Git path contains invalid UTF-8 bytes.")
  }
}

private fun decodeReviewDiffGitEscapeChar(escaped: Char): ReviewDiffParseOutcome<Char> =
  when (escaped) {
    'a' -> ReviewDiffParseOutcome.Accepted('\u0007')
    'b' -> ReviewDiffParseOutcome.Accepted('\b')
    'f' -> ReviewDiffParseOutcome.Accepted('\u000c')
    'n' -> ReviewDiffParseOutcome.Accepted('\n')
    'r' -> ReviewDiffParseOutcome.Accepted('\r')
    't' -> ReviewDiffParseOutcome.Accepted('\t')
    'v' -> ReviewDiffParseOutcome.Accepted('\u000b')
    '\\' -> ReviewDiffParseOutcome.Accepted('\\')
    '"' -> ReviewDiffParseOutcome.Accepted('"')
    else -> ReviewDiffParseOutcome.Rejected("Unsupported quoted Git path escape '\\$escaped'.")
  }
