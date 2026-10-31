package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

/** How [runtimeText] marks up one span it recognised. */
enum class RuntimeSpanKind { Bold, Code, Branch, Path, Url }

/** A recognised span of runtime text: [range] in the source, [content] the text to show (backticks and `**` dropped). */
data class RuntimeSpan(val kind: RuntimeSpanKind, val range: IntRange, val content: String)

private const val FILE_EXTENSIONS =
  "kt|kts|java|swift|m|h|ts|tsx|js|jsx|mjs|py|go|rs|php|rb|cs|c|cc|cpp|xml|json|yaml|yml|toml|md|gradle|properties|sql|sh|html|css|scss|txt|plist|lock"

private val RUNTIME_SPAN = Regex(
  """\*\*(?<bold>[^*\n]+?)\*\*""" +
    """|`(?<code>[^`\n]+)`""" +
    """|(?<url>https?://[^\s<>()\[\]`"']+)""" +
    """|(?<=\b(?i:branch):?\s)(?<branch>[A-Za-z0-9][\w./+-]*)""" +
    """|(?<path>(?:~|\.{1,2})?/?(?:[\w.@+-]+/)+[\w.@+-]+(?::\d+(?:-\d+)?)?)""" +
    """|(?<file>\b[\w-]+(?:\.[\w-]+)*\.(?:$FILE_EXTENSIONS)(?::\d+(?:-\d+)?)?\b)""",
)

private val COMMON_BRANCHES = setOf("main", "master", "develop", "dev", "trunk")

/** After the word "branch", a name is a branch when it is a common trunk name or has a separator or digit no plain word has. */
private fun looksLikeBranch(candidate: String): Boolean =
  candidate in COMMON_BRANCHES || candidate.any { it == '/' || it == '-' || it == '_' || it == '.' || it.isDigit() }

private val TRAILING_PUNCTUATION = Regex("""[.,;:!?]+$""")
private val EXTENSION = Regex("""\.(?:$FILE_EXTENSIONS)(?::\d+(?:-\d+)?)?$""")

/** A slash alone ("and/or", "read/write") is prose; a path also starts at a root, has two slashes, or names a file. */
private fun looksLikePath(candidate: String): Boolean =
  candidate.startsWith("/") || candidate.startsWith("~/") || candidate.startsWith("./") || candidate.startsWith("../") ||
    candidate.count { it == '/' } >= 2 || EXTENSION.containsMatchIn(candidate)

/**
 * The spans of [text] the app sets apart: `**bold**`, `` `code` ``, URLs, a branch name after the word "branch", file paths (a `:line` suffix stays with the path) and
 * bare file names with a known extension. Trailing sentence punctuation stays outside a URL or path.
 */
fun runtimeSpans(text: String): List<RuntimeSpan> = RUNTIME_SPAN.findAll(text).mapNotNull { match ->
  val groups = match.groups
  fun trimmed(kind: RuntimeSpanKind, value: String): RuntimeSpan {
    val content = value.replace(TRAILING_PUNCTUATION, "")
    return RuntimeSpan(kind, match.range.first until match.range.first + content.length, content)
  }
  when {
    groups["bold"] != null -> RuntimeSpan(RuntimeSpanKind.Bold, match.range, groups["bold"]!!.value)
    groups["code"] != null -> RuntimeSpan(RuntimeSpanKind.Code, match.range, groups["code"]!!.value)
    groups["url"] != null -> trimmed(RuntimeSpanKind.Url, match.value)
    groups["branch"] != null -> trimmed(RuntimeSpanKind.Branch, match.value).takeIf { looksLikeBranch(it.content) }
    groups["path"] != null -> trimmed(RuntimeSpanKind.Path, match.value).takeIf { looksLikePath(it.content) }
    groups["file"] != null -> trimmed(RuntimeSpanKind.Path, match.value)
    else -> null
  }
}.toList()

/** The span styles [runtimeText] applies; [link] is the URL style, [code] covers code and paths. */
class RuntimeTextStyles(val code: SpanStyle, val path: SpanStyle, val link: SpanStyle, val bold: SpanStyle, val branch: SpanStyle = code)

/**
 * [text] with every [runtimeSpans] span styled: code and paths in mono on a raised chip, URLs as clickable links. A bold span's
 * content is marked up the same way, so `**`code`:**` reads as bold code.
 */
fun runtimeText(text: String, styles: RuntimeTextStyles): AnnotatedString = buildAnnotatedString { appendRuntimeText(text, styles) }

private fun AnnotatedString.Builder.appendRuntimeText(text: String, styles: RuntimeTextStyles) {
  var at = 0
  runtimeSpans(text).forEach { span ->
    append(text.substring(at, span.range.first))
    when (span.kind) {
      RuntimeSpanKind.Bold -> withStyle(styles.bold) { appendRuntimeText(span.content, styles) }
      RuntimeSpanKind.Code -> withStyle(styles.code) { append(span.content) }
      RuntimeSpanKind.Path -> withStyle(styles.path) { append(span.content) }
      RuntimeSpanKind.Branch -> withStyle(styles.branch) { append(span.content) }
      RuntimeSpanKind.Url -> withLink(LinkAnnotation.Url(span.content, TextLinkStyles(styles.link))) { append(span.content) }
    }
    at = span.range.last + 1
  }
  append(text.substring(at))
}

/** The theme's [RuntimeTextStyles]. */
@Composable
fun runtimeTextStyles(): RuntimeTextStyles {
  val colors = SkillBillTheme.colors
  val mono = SkillBillTheme.type.monoSm.fontFamily
  return remember(colors, mono) {
    RuntimeTextStyles(
      code = SpanStyle(fontFamily = mono, fontSize = 0.92.em, color = colors.data2, background = colors.border),
      path = SpanStyle(fontFamily = mono, fontSize = 0.92.em, color = colors.accentText, background = colors.border),
      link = SpanStyle(color = colors.data2, textDecoration = TextDecoration.Underline),
      bold = SpanStyle(fontWeight = FontWeight.Medium),
      branch = SpanStyle(fontFamily = mono, fontSize = 0.92.em, color = colors.data5, background = colors.border),
    )
  }
}

/** [text] marked up by [runtimeText] in the theme's styles, remembered per text. */
@Composable
fun rememberRuntimeText(text: String): AnnotatedString {
  val styles = runtimeTextStyles()
  return remember(text, styles) { runtimeText(text, styles) }
}
