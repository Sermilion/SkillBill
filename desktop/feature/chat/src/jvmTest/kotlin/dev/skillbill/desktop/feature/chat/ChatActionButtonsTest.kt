package dev.skillbill.desktop.feature.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ChatActionButtonsTest {
  @Test
  fun `buttons identify the chosen instruction and make pause behavior visible while preventing repeat clicks`() = runComposeUiTest {
    val file = ChatSuggestedAction("File an issue", "File an issue for this execution.", ChatPermissionMode.ReadOnly)
    val push = ChatSuggestedAction("Push with lease", "Push this execution's branch with a verified lease.", ChatPermissionMode.Act)
    val enabled = mutableStateOf(true)
    val chosen = mutableListOf<ChatSuggestedAction>()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        ChatActionButtons(listOf(file, push), enabled.value, ChatPermissionMode.ReadOnly) {
          chosen += it
          enabled.value = false
        }
      }
    }

    onNodeWithText("File an issue").assertExists()
    onNodeWithText("Pause and push with lease").performClick()
    assertEquals(listOf(push), chosen)
    onNodeWithText("File an issue").assertIsNotEnabled()
    onNodeWithText("Pause and push with lease").assertIsNotEnabled()
  }
}
