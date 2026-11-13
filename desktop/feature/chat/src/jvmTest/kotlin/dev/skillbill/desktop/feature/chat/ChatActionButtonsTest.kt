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
  fun `goal start renders as a card that names the change, key and checkout and starts the original action`() = runComposeUiTest {
    val action = ChatSuggestedAction("Run phase:goal", "Implement the change", ChatPermissionMode.Act, NewTaskActionKind.Goal, title = "Add CSV export")
    val chosen = mutableListOf<ChatSuggestedAction>()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        ChatActionButtons(listOf(action), true, ChatPermissionMode.Act, startIssueKey = "SKILL-530", startContext = StartCardContext("sona", "main")) { chosen += it }
      }
    }
    onNodeWithText("Add CSV export").assertExists()
    onNodeWithText("Goal · SKILL-530 · sona from main").assertExists()
    onNodeWithText("Run phase:goal").assertDoesNotExist()
    onNodeWithText("Start goal").performClick()
    assertEquals(listOf(action), chosen)
  }

  @Test
  fun `only the clicked start card shows Starting while its start is in flight`() = runComposeUiTest {
    val goal = ChatSuggestedAction("Run phase:goal", "Implement it", ChatPermissionMode.Act, NewTaskActionKind.Goal, title = "Add CSV export")
    val plan = ChatSuggestedAction("Run phase:plan", "Plan it", ChatPermissionMode.Act, NewTaskActionKind.Plan, title = "Plan CSV export")
    val starting = mutableStateOf(false)
    setContent {
      SkillBillTheme(reducedMotion = true) {
        ChatActionButtons(listOf(goal, plan), !starting.value, ChatPermissionMode.Act, startContext = StartCardContext("sona", "main", starting.value)) {
          starting.value = true
        }
      }
    }
    onNodeWithText("Start goal").performClick()
    waitForIdle()
    onNodeWithText("Starting…").assertExists()
    onNodeWithText("Start goal").assertDoesNotExist()
    onNodeWithText("Start plan").assertIsNotEnabled()
    starting.value = false
    waitForIdle()
    onNodeWithText("Starting…").assertDoesNotExist()
    onNodeWithText("Start goal").assertExists()
  }

  @Test
  fun `start card subtitle prefers the action branch and falls back without a checkout`() {
    val goal = ChatSuggestedAction("Run phase:goal", "x", ChatPermissionMode.Act, NewTaskActionKind.Goal)
    val plan = ChatSuggestedAction("Run phase:plan", "x", ChatPermissionMode.Act, NewTaskActionKind.Plan, branch = "feat/csv")
    assertEquals("New goal", startCardTitle(goal))
    assertEquals("Goal", startCardSubtitle(goal, null, null))
    assertEquals("Goal · sona", startCardSubtitle(goal, null, StartCardContext("sona", null)))
    assertEquals("Plan · sona on feat/csv", startCardSubtitle(plan, null, StartCardContext("sona", "main")))
    assertEquals("Plan · SONA-7 · on feat/csv", startCardSubtitle(plan, "SONA-7", null))
  }

  @Test
  fun `non-goal starts stay as rows next to a start card`() = runComposeUiTest {
    val goal = ChatSuggestedAction("Run phase:goal", "Implement it", ChatPermissionMode.Act, NewTaskActionKind.Goal, title = "Add CSV export")
    val review = ChatSuggestedAction("Run phase:review", "Review this branch.", ChatPermissionMode.Act, NewTaskActionKind.Review)
    setContent {
      SkillBillTheme(reducedMotion = true) {
        ChatActionButtons(listOf(goal, review), true, ChatPermissionMode.Act) {}
      }
    }
    onNodeWithText("Start goal").assertExists()
    onNodeWithText("Run phase:review").assertExists()
    onNodeWithText("Reviews the current changes and reports verified findings.").assertExists()
  }

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

  @Test
  fun `update-check suggestion hint uses the public product name`() = runComposeUiTest {
    val updateCheck = ChatSuggestedAction(
      "run operation:update-check",
      "Check for updates.",
      ChatPermissionMode.ReadOnly,
    )
    setContent {
      SkillBillTheme(reducedMotion = true) {
        ChatActionButtons(listOf(updateCheck), true, ChatPermissionMode.ReadOnly) {}
      }
    }

    onNodeWithText("run operation:update-check").assertExists()
    onNodeWithText("Checks for Sona updates.").assertExists()
    onNodeWithText("Checks for skill-bill updates.").assertDoesNotExist()
  }
}
