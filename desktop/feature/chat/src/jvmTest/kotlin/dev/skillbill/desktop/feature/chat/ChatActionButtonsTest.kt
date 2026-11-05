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
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
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

  @Test
  fun `start action on a running execution never promises a pause`() = runComposeUiTest {
    val review = ChatSuggestedAction("Run phase:review", "Review this branch.", ChatPermissionMode.Act, NewTaskActionKind.Review)
    val chosen = mutableListOf<ChatSuggestedAction>()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        ChatActionButtons(listOf(review), true, ChatPermissionMode.ReadOnly) { chosen += it }
      }
    }

    onNodeWithText("Run phase:review").performClick()
    assertEquals(listOf(review), chosen)
  }

  @Test
  fun `run suggestions explain their effects even when disabled`() = runComposeUiTest {
    val actions = listOf(
      ChatSuggestedAction("Run phase:pr", "Open a PR.", ChatPermissionMode.Act, NewTaskActionKind.Pr),
      ChatSuggestedAction("Run operation:feature-guard", "Add a flag.", ChatPermissionMode.Act),
    )
    setContent {
      SkillBillTheme(reducedMotion = true) {
        ChatActionButtons(actions, false, ChatPermissionMode.Act) {}
      }
    }

    onNodeWithText("Commits and pushes changes, opens or updates a pull request, and monitors CI.").assertExists()
    onNodeWithText("Proposes a feature flag, then adds it after confirmation.").assertExists()
  }
}
