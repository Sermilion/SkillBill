package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeTextTest {
  private fun spans(text: String) = runtimeSpans(text).map { it.kind to it.content }

  @Test
  fun `code, file names, paths with lines and urls are set apart, and sentence punctuation stays outside`() {
    assertEquals(
      listOf(
        RuntimeSpanKind.Code to "syncAfterAccessChange",
        RuntimeSpanKind.Path to "ContactsFriendsContent.kt:348",
        RuntimeSpanKind.Path to "feature/ui/ContactsPermissionFlow.kt:180",
        RuntimeSpanKind.Url to "https://github.com/o/r/pull/12",
        RuntimeSpanKind.Path to "/mnt/apps/repo",
        RuntimeSpanKind.Bold to "Lanes applied:",
      ),
      spans(
        "Nothing tests `syncAfterAccessChange` (ContactsFriendsContent.kt:348). See feature/ui/ContactsPermissionFlow.kt:180, " +
          "https://github.com/o/r/pull/12. Clone into /mnt/apps/repo. **Lanes applied:** two.",
      ),
    )
  }

  @Test
  fun `a backtick span keeps its operators and dotted names whole`() {
    assertEquals(
      listOf(
        RuntimeSpanKind.Code to "message != Res.string.contacts_permission_denied_message",
        RuntimeSpanKind.Code to "accessTitle",
      ),
      spans("comparing the message string (`message != Res.string.contacts_permission_denied_message`, and `accessTitle` at line 86)."),
    )
  }

  @Test
  fun `code inside bold is still set as code`() {
    val styles = RuntimeTextStyles(SpanStyle(fontFamily = FontFamily.Monospace), SpanStyle(), SpanStyle(), SpanStyle(fontWeight = FontWeight.Medium))
    val text = runtimeText("**`rememberAppSettingsOpener`:** has actuals.", styles)

    assertEquals("rememberAppSettingsOpener: has actuals.", text.text)
    assertEquals(
      "rememberAppSettingsOpener",
      text.spanStyles.single { it.item.fontFamily == FontFamily.Monospace }.let { text.text.substring(it.start, it.end) },
    )
  }

  @Test
  fun `a branch name after the word branch is set apart, a plain word is not`() {
    assertEquals(
      listOf(RuntimeSpanKind.Branch to "feat/SKILL-447-build-the-linear-design", RuntimeSpanKind.Branch to "main"),
      spans("Validation on branch feat/SKILL-447-build-the-linear-design. Rebased onto branch main, the branch is clean."),
    )
  }

  @Test
  fun `a single slash between words is prose, not a path`() {
    assertEquals(emptyList(), spans("Read/write access and/or a retry."))
  }
}
