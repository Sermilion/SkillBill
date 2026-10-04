package skillbill.review.model

private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[/\\\\].*")

fun requireRepositoryRelativePath(path: String) {
  val violation = repositoryRelativePathViolation(path)
  require(violation == null) { violation.orEmpty() }
}

fun repositoryRelativePathViolation(path: String): String? =
  when {
    path.isEmpty() || path.startsWith('/') || path.startsWith('\\') -> "Review paths must be repository-relative."
    '\u0000' in path || !path.hasWellFormedUtf16() -> "Review paths must contain valid Unicode and no NUL."
    WINDOWS_ABSOLUTE_PATH.matches(path) -> "Review paths must be repository-relative."
    repositoryPathSegments(path).any { it == "." || it == ".." } ->
      "Review paths must use non-traversing Git path components."
    else -> null
  }

internal fun repositoryPathSegments(path: String): List<String> = path.split('/', '\\').filter { it.isNotEmpty() }

private fun String.hasWellFormedUtf16(): Boolean {
  var index = 0
  while (index < length) {
    val current = this[index]
    when {
      Character.isHighSurrogate(current) -> {
        if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return false
        index += 2
      }
      Character.isLowSurrogate(current) -> return false
      else -> index++
    }
  }
  return true
}
