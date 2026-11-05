package dev.skillbill.desktop.feature.detail

private const val PATH_TAIL = """[^\s`"'<>(),;]*"""

/** A path may only start where the character before it cannot belong to a URL or a longer path. */
private const val PATH_START = """(?<![\w./:~-])(?:file://)?"""

private val ROOTED_PATH = Regex("""$PATH_START(?:/home/|/Users/|/root/|/tmp/|/private/|/var/|/mnt/|/opt/|~/)$PATH_TAIL""")

private val LINE_SUFFIX = Regex(""":\d+(?::\d+)?$""")

/** [text] with paths under [invocationDirectory] and under user or temp roots reduced to file names; URLs and API routes stay. */
internal fun shortenPaths(text: String, invocationDirectory: String?): String {
  val directory = invocationDirectory?.trimEnd('/', '\\')?.takeIf { it.isNotBlank() }
  val underDirectory = directory?.let { Regex("""$PATH_START${Regex.escape(it)}(?:[/\\]$PATH_TAIL|(?=[.:!?]*(?:[\s`"'<>(),;]|$)))""") }
  val shortened = underDirectory?.replace(text) { shortenMatch(it.value) } ?: text
  return ROOTED_PATH.replace(shortened) { shortenMatch(it.value) }
}

private fun shortenMatch(match: String): String {
  val path = match.trimEnd('.', ':', '!', '?')
  val trailing = match.substring(path.length)
  val line = LINE_SUFFIX.find(path)?.value.orEmpty()
  return fileName(path.removeSuffix(line).trimEnd('/', '\\')) + line + trailing
}
