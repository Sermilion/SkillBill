package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillColors
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** A fenced or indented code block from an agent reply: a header with the language and a copy button, then the code. */
@Composable
internal fun ChatCodeBlock(code: String, language: String?) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.md)
  val diff = isDiff(language, code)
  Column(
    Modifier.fillMaxWidth().padding(vertical = 4.dp)
      .border(1.dp, colors.border, shape)
      .background(colors.bgInset, shape),
  ) {
    CodeHeader(code, label = language?.takeIf { it.isNotBlank() } ?: if (diff) "diff" else "code")
    if (diff) DiffBody(code) else HighlightedBody(code, language)
  }
}

@Composable
private fun CodeHeader(code: String, label: String) {
  val colors = SkillBillTheme.colors
  var copied by remember { mutableStateOf(false) }
  LaunchedEffect(copied) {
    if (copied) {
      delay(COPIED_FEEDBACK_MS)
      copied = false
    }
  }
  Row(
    Modifier.fillMaxWidth().background(colors.bgRaised, RoundedCornerShape(topStart = SkillBillRadius.md, topEnd = SkillBillRadius.md))
      .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BasicText(label.lowercase(), style = SkillBillTheme.type.monoSm.copy(color = colors.fgMuted))
    SbIconButton(
      icon = if (copied) LucideIcons.check else LucideIcons.copy,
      label = if (copied) "Copied" else "Copy code",
      size = SbControlSize.Small,
      onClick = {
        val selection = StringSelection(code)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        copied = true
      },
    )
  }
}

@Composable
private fun HighlightedBody(code: String, language: String?) {
  val colors = SkillBillTheme.colors
  val style = SkillBillTheme.type.monoSm.copy(color = colors.fg2)
  val syntax = remember(language) { syntaxLanguage(language) }
  val theme = remember(colors) { syntaxTheme(colors) }
  val text = if (syntax == null) {
    AnnotatedString(code)
  } else if (LocalInspectionMode.current) {
    remember(code, syntax, theme) { highlight(code, syntax, theme) }
  } else {
    val latest by produceState(AnnotatedString(""), code, syntax, theme) {
      value = withContext(Dispatchers.Default) { highlight(code, syntax, theme) }
    }
    // While a reply streams, the last highlight covers a prefix of the code, so its spans stay valid.
    if (latest.text == code) latest else extendSpans(latest, code)
  }
  BasicText(text, style = style, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp))
}

@Composable
private fun DiffBody(code: String) {
  val colors = SkillBillTheme.colors
  val style = SkillBillTheme.type.monoSm
  val lines = remember(code) { code.lines().map { it to diffLineKind(it) } }
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val viewport = maxWidth
    Column(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
      Column(Modifier.width(IntrinsicSize.Max).widthIn(min = viewport)) {
        lines.forEach { (line, kind) ->
          val look = diffLook(kind, colors)
          BasicText(
            text = line.ifEmpty { " " },
            style = style.copy(color = look.text, fontWeight = if (kind == DiffLineKind.File) FontWeight.SemiBold else null),
            modifier = Modifier.fillMaxWidth().background(look.background).padding(horizontal = 12.dp),
          )
        }
      }
    }
  }
}

internal enum class DiffLineKind { File, Hunk, Added, Removed, Context }

internal fun diffLineKind(line: String): DiffLineKind = when {
  line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff --git") || line.startsWith("index ") -> DiffLineKind.File
  line.startsWith("@@") -> DiffLineKind.Hunk
  line.startsWith("+") -> DiffLineKind.Added
  line.startsWith("-") -> DiffLineKind.Removed
  else -> DiffLineKind.Context
}

/** A block is a diff when its fence says so, or when an unlabeled block carries a git or unified diff header. */
internal fun isDiff(language: String?, code: String): Boolean {
  val lang = language?.trim()?.lowercase()
  if (lang == "diff" || lang == "patch" || lang == "udiff") return true
  if (!lang.isNullOrEmpty()) return false
  val lines = code.lineSequence()
  return lines.any { it.startsWith("diff --git") } ||
    (lines.any { it.startsWith("@@ ") } && lines.any { it.startsWith("+") || it.startsWith("-") })
}

internal fun syntaxLanguage(language: String?): SyntaxLanguage? {
  val name = language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
  return LANGUAGE_ALIASES[name] ?: SyntaxLanguage.getByName(name)?.takeIf { it != SyntaxLanguage.DEFAULT }
}

private val LANGUAGE_ALIASES = mapOf(
  "kt" to SyntaxLanguage.KOTLIN, "kts" to SyntaxLanguage.KOTLIN,
  "js" to SyntaxLanguage.JAVASCRIPT, "jsx" to SyntaxLanguage.JAVASCRIPT, "mjs" to SyntaxLanguage.JAVASCRIPT,
  "ts" to SyntaxLanguage.TYPESCRIPT, "tsx" to SyntaxLanguage.TYPESCRIPT,
  "py" to SyntaxLanguage.PYTHON, "rb" to SyntaxLanguage.RUBY, "rs" to SyntaxLanguage.RUST,
  "sh" to SyntaxLanguage.SHELL, "bash" to SyntaxLanguage.SHELL, "zsh" to SyntaxLanguage.SHELL,
  "fish" to SyntaxLanguage.SHELL, "console" to SyntaxLanguage.SHELL, "shell" to SyntaxLanguage.SHELL,
  "c++" to SyntaxLanguage.CPP, "cc" to SyntaxLanguage.CPP, "h" to SyntaxLanguage.C,
  "cs" to SyntaxLanguage.CSHARP, "c#" to SyntaxLanguage.CSHARP, "golang" to SyntaxLanguage.GO,
  "pl" to SyntaxLanguage.PERL, "coffee" to SyntaxLanguage.COFFEESCRIPT,
)

internal fun syntaxTheme(colors: SkillBillColors) = SyntaxTheme(
  key = "skillbill",
  code = colors.fg2.toArgb(),
  keyword = colors.data5.toArgb(),
  string = colors.data3.toArgb(),
  literal = colors.data4.toArgb(),
  comment = colors.fgFaint.toArgb(),
  metadata = colors.data1.toArgb(),
  multilineComment = colors.fgFaint.toArgb(),
  punctuation = colors.fgMuted.toArgb(),
  mark = colors.data2.toArgb(),
)

internal fun highlight(code: String, language: SyntaxLanguage, theme: SyntaxTheme): AnnotatedString {
  val highlights = Highlights.Builder().code(code).language(language).theme(theme).build().getHighlights()
  return buildAnnotatedString {
    append(code)
    highlights.forEach {
      val span = when (it) {
        is ColorHighlight -> SpanStyle(color = Color(it.rgb).copy(alpha = 1f))
        is BoldHighlight -> SpanStyle(fontWeight = FontWeight.SemiBold)
      }
      val end = it.location.end.coerceAtMost(code.length)
      if (it.location.start < end) addStyle(span, it.location.start, end)
    }
  }
}

private fun extendSpans(previous: AnnotatedString, code: String): AnnotatedString {
  if (!code.startsWith(previous.text)) return AnnotatedString(code)
  return AnnotatedString(code, spanStyles = previous.spanStyles)
}

@Immutable
private data class DiffLook(val text: Color, val background: Color)

private fun diffLook(kind: DiffLineKind, colors: SkillBillColors) = when (kind) {
  DiffLineKind.File -> DiffLook(colors.fg, Color.Transparent)
  DiffLineKind.Hunk -> DiffLook(colors.stPlanning, colors.stPlanning.copy(alpha = .08f))
  DiffLineKind.Added -> DiffLook(colors.stDone, colors.stDone.copy(alpha = .12f))
  DiffLineKind.Removed -> DiffLook(colors.stFailed, colors.stFailed.copy(alpha = .12f))
  DiffLineKind.Context -> DiffLook(colors.fg2, Color.Transparent)
}

private const val COPIED_FEEDBACK_MS = 1_500L
