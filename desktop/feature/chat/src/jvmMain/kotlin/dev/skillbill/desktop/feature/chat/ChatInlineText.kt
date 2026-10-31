package dev.skillbill.desktop.feature.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotator
import dev.skillbill.desktop.core.designsystem.component.RuntimeTextStyles
import dev.skillbill.desktop.core.designsystem.component.looksLikeBranch
import dev.skillbill.desktop.core.designsystem.component.runtimeSpans
import dev.skillbill.desktop.core.designsystem.component.runtimeText
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.getTextInNode

private const val BRANCH_LABEL = "branch"
private val TRAILING_PUNCTUATION = Regex("""[.,;:!?)]+$""")

/**
 * How the chat draws one inline Markdown token, the way runtime text reads elsewhere in the app: a code span as code, a word
 * that is a path or file name as a path, and the word after "branch" as a branch when it looks like one. Null leaves the token
 * to the renderer. [previousWord] is the last text token before this one in the same run.
 */
internal fun chatInlineToken(type: IElementType, text: String, previousWord: String, styles: RuntimeTextStyles): AnnotatedString? =
  when (type) {
    MarkdownElementTypes.CODE_SPAN -> buildAnnotatedString { withStyle(styles.code) { append(text.trim('`').trim()) } }
    MarkdownTokenTypes.TEXT -> {
      val name = text.replace(TRAILING_PUNCTUATION, "")
      when {
        previousWord.trimEnd(':').equals(BRANCH_LABEL, ignoreCase = true) && name.isNotEmpty() && looksLikeBranch(name) ->
          buildAnnotatedString {
            withStyle(styles.branch) { append(name) }
            append(text.substring(name.length))
          }
        runtimeSpans(text).isNotEmpty() -> runtimeText(text, styles)
        else -> null
      }
    }
    else -> null
  }

/** The chat's [MarkdownAnnotator]: [chatInlineToken] per token, remembering the last word so a branch after "branch" is found. */
internal fun chatMarkdownAnnotator(styles: RuntimeTextStyles): MarkdownAnnotator {
  var previousWord = ""
  return markdownAnnotator { content, node ->
    val text = node.getTextInNode(content).toString()
    val drawn = chatInlineToken(node.type, text, previousWord, styles)
    when (node.type) {
      MarkdownTokenTypes.TEXT -> previousWord = text
      MarkdownTokenTypes.WHITE_SPACE, MarkdownTokenTypes.COLON -> Unit
      else -> previousWord = ""
    }
    if (drawn != null) append(drawn)
    drawn != null
  }
}
