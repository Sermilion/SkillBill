package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

internal object KotlinCommentPolicyScanSupport {
  data class AuthoredSuppression(val relativePath: String, val symbol: String, val rule: String)

  data class CommentPolicyViolation(val lineNumber: Int, val kind: String)

  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { start ->
      var dir: Path? = start
      while (dir != null) {
        if (Files.isDirectory(dir.resolve("runtime-kotlin"))) return@let dir
        dir = dir.parent
      }
      start
    }

  private val KOTLIN_SOURCE_EXTENSIONS: Set<String> = setOf("kt", "kts")

  fun authoredKotlinSourcesUnder(root: Path): List<Path> {
    if (!Files.isDirectory(root)) {
      error("Missing architecture scan root: ${scanRootLabel(root)}")
    }
    return Files.walk(root).use { paths ->
      paths
        .filter { path ->
          path.isRegularFile() &&
            path.extension in KOTLIN_SOURCE_EXTENSIONS &&
            !isGeneratedOrBuildPath(path)
        }
        .toList()
    }
  }

  private fun scanRootLabel(root: Path): String {
    val normalized = root.toAbsolutePath().normalize()
    return if (normalized.startsWith(runtimeRoot)) {
      runtimeRoot.relativize(normalized).toString().replace('\\', '/')
    } else {
      normalized.toString().replace('\\', '/')
    }
  }

  fun commentAndInterfaceKdocViolations(
    scanRoots: List<String>,
    exemptRelativePaths: Set<String> = emptySet(),
  ): List<String> {
    val violations = mutableListOf<String>()
    scanRoots.forEach { scanRoot ->
      authoredKotlinSourcesUnder(runtimeRoot.resolve(scanRoot)).forEach { sourceFile ->
        val relativePath = runtimeRoot.relativize(sourceFile).toString().replace('\\', '/')
        if (relativePath in exemptRelativePaths) return@forEach
        val source = sourceFile.toFile().readText()
        collectCommentPolicyViolations(source).forEach { violation ->
          violations += "$relativePath:${violation.lineNumber}:${violation.kind}"
        }
      }
    }
    return violations.sorted()
  }

  fun lineCommentViolationsIgnoringStringLiterals(source: String): List<Int> =
    collectCommentPolicyViolations(source)
      .filter { violation -> violation.kind == "line-comment" }
      .map { violation -> violation.lineNumber }

  fun collectCommentPolicyViolations(source: String): List<CommentPolicyViolation> = CommentPolicyScanner(source).scan()

  private class CommentPolicyScanner(private val source: String) {
    private val violations = mutableListOf<CommentPolicyViolation>()
    private var index = 0
    private var line = 1
    private val braceKinds = mutableListOf<BraceKind>()
    private var pendingInterfaceOpen = false

    fun scan(): List<CommentPolicyViolation> {
      while (index < source.length) {
        if (!skipLiteralIfPresent() && !recordCommentIfPresent()) {
          advanceCodeChar()
        }
      }
      return violations
    }

    private fun skipLiteralIfPresent(): Boolean {
      if (source[index] == '`') {
        index = source.indexOf('`', index + 1).let { closing -> if (closing < 0) source.length else closing + 1 }
        return true
      }
      if (source.startsWith("\"\"\"", index)) {
        index = skipTripleQuotedString(source, index + 3)
        return true
      }
      if (source[index] == '"') {
        index = skipQuotedString(source, index + 1)
        return true
      }
      if (source[index] == '\'') {
        index = skipCharLiteral(source, index + 1)
        return true
      }
      return false
    }

    private fun recordCommentIfPresent(): Boolean {
      if (source.startsWith("//", index)) {
        violations += CommentPolicyViolation(line, "line-comment")
        index = skipToEndOfLine(source, index)
        return true
      }
      if (source.startsWith("/**", index)) {
        val start = index
        val end = skipBlockComment(source, index)
        if (!isAllowedKDocSite(source, end, braceKinds.lastOrNull() == BraceKind.INTERFACE)) {
          violations += CommentPolicyViolation(lineAt(start), "kdoc-outside-interface")
        }
        index = end
        return true
      }
      if (source.startsWith("/*", index)) {
        violations += CommentPolicyViolation(line, "block-comment")
        index = skipBlockComment(source, index)
        return true
      }
      return false
    }

    private fun advanceCodeChar() {
      if (interfaceKeywordAt(source, index)) pendingInterfaceOpen = true
      if (pendingInterfaceOpen && nonInterfaceDeclarationKeywordAt(source, index)) {
        pendingInterfaceOpen = false
      }
      when (source[index]) {
        '\n' -> line++
        '{' -> {
          braceKinds += if (pendingInterfaceOpen) BraceKind.INTERFACE else BraceKind.OTHER
          pendingInterfaceOpen = false
        }
        '}' -> {
          if (braceKinds.isNotEmpty()) braceKinds.removeAt(braceKinds.lastIndex)
          pendingInterfaceOpen = false
        }
      }
      index++
    }

    private fun lineAt(charIndex: Int): Int {
      var currentLine = 1
      var scan = 0
      while (scan < charIndex && scan < source.length) {
        if (source[scan] == '\n') currentLine++
        scan++
      }
      return currentLine
    }
  }

  private fun isGeneratedOrBuildPath(path: Path): Boolean =
    path.toString().replace('\\', '/').split('/').any { segment ->
      segment == "build" || segment == "generated"
    }

  private fun interfaceKeywordAt(
    source: String,
    index: Int,
  ): Boolean {
    if (!source.startsWith("interface", index)) return false
    val before = source.getOrNull(index - 1)
    if (before != null && (before.isLetterOrDigit() || before == '_')) return false
    val after = source.getOrNull(index + "interface".length)
    if (after != null && (after.isLetterOrDigit() || after == '_')) return false
    return true
  }

  private fun nonInterfaceDeclarationKeywordAt(
    source: String,
    index: Int,
  ): Boolean =
    listOf("class", "object", "fun", "val", "var", "typealias").any { keyword ->
      source.startsWith(keyword, index) &&
        source.getOrNull(index - 1)?.let { character -> character.isLetterOrDigit() || character == '_' } != true &&
        source.getOrNull(index + keyword.length)
          ?.let { character -> character.isLetterOrDigit() || character == '_' } != true
    }

  private fun isAllowedKDocSite(
    source: String,
    afterCommentIndex: Int,
    insideInterfaceBody: Boolean,
  ): Boolean {
    var remainder = source.substring(afterCommentIndex).trimStart()
    while (remainder.startsWith("@")) {
      val nextLine = remainder.indexOf('\n').takeIf { lineBreak -> lineBreak != -1 } ?: remainder.length
      remainder = remainder.substring(nextLine).trimStart()
    }
    if (INTERFACE_DECLARATION_PREFIX.containsMatchIn(remainder)) return true
    if (!insideInterfaceBody) return false
    return KDOC_INTERFACE_MEMBER_PREFIX.containsMatchIn(remainder)
  }

  private fun skipToEndOfLine(
    source: String,
    from: Int,
  ): Int {
    var index = from
    while (index < source.length && source[index] != '\n') index++
    return index
  }

  private fun skipBlockComment(
    source: String,
    from: Int,
  ): Int {
    val end = source.indexOf("*/", from + 2)
    return if (end == -1) source.length else end + 2
  }

  private fun skipQuotedString(
    source: String,
    from: Int,
  ): Int {
    var index = from
    while (index < source.length) {
      when (source[index]) {
        '\\' -> index += 2
        '"' -> return index + 1
        else -> index++
      }
    }
    return index
  }

  private fun skipTripleQuotedString(
    source: String,
    from: Int,
  ): Int {
    var index = from
    while (index < source.length) {
      if (source.startsWith("\"\"\"", index)) return index + 3
      if (source[index] == '\\') {
        index += 2
        continue
      }
      index++
    }
    return index
  }

  private fun skipCharLiteral(
    source: String,
    from: Int,
  ): Int {
    var index = from
    while (index < source.length) {
      when (source[index]) {
        '\\' -> index += 2
        '\'' -> return index + 1
        else -> index++
      }
    }
    return index
  }

  private enum class BraceKind {
    INTERFACE,
    OTHER,
  }

  private val KDOC_INTERFACE_MEMBER_PREFIX =
    Regex(
      """^(?:(?:@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)]*\))?\s+)*)""" +
        """(?:(?:public|internal|private|protected|override|suspend|abstract|open|lateinit|const|data|enum|""" +
        """annotation|value|sealed|external|tailrec|expect|actual|infix|operator|companion)\s+)*""" +
        """(?:fun|val|var|(?:data\s+)?(?:class|object)|interface|typealias)\s+""",
    )
  private val INTERFACE_DECLARATION_PREFIX =
    Regex(
      """^(?:(?:@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)]*\))?\s+)*)""" +
        """(?:(?:public|internal|private|protected|sealed|fun)\s+)*interface\s+""",
    )
}
