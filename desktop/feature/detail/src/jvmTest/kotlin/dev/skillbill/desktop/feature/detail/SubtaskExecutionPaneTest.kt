package dev.skillbill.desktop.feature.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.pressKey
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.AuditAttempt
import dev.skillbill.desktop.core.domain.model.AuditCriterion
import dev.skillbill.desktop.core.domain.model.AuditOutcome
import dev.skillbill.desktop.core.domain.model.DetailAttempts
import dev.skillbill.desktop.core.domain.model.PhaseCardState
import dev.skillbill.desktop.core.domain.model.SubtaskAuditResult
import dev.skillbill.desktop.core.domain.model.SubtaskExecution
import dev.skillbill.desktop.core.domain.model.SubtaskPhaseResults
import dev.skillbill.desktop.core.domain.model.SubtaskValidation
import dev.skillbill.desktop.core.domain.model.WorkSubtask
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SubtaskExecutionPaneTest {
  @Test
  fun `selected subtask scopes execution and runs while goal finish keeps goal values`() = runComposeUiTest {
    val selectedExecution = execution("wf-sub-1", mapOf("implement" to 1, "audit" to 2))
    val detail = emptyDetail.copy(
      currentSubtaskId = 2,
      executionLiveness = "live",
      subtasks = listOf(
        WorkSubtask(1, "Completed work", "complete", null, null, emptyList()),
        WorkSubtask(2, "Current work", "in_progress", null, null, emptyList()),
        WorkSubtask(3, "Pending work", "pending", null, null, emptyList()),
      ),
      attempts = DetailAttempts(mapOf("implement" to 9), mapOf("fix" to 4), mapOf("relaunch" to 2), 3, 1),
      phaseResults = listOf(SubtaskPhaseResults(1, emptyList(), null, null, execution = selectedExecution)),
      validation = listOf(SubtaskValidation(3, null, null, emptyList(), null)),
    )
    val state = mutableStateOf(
      DetailUiState(
        workItem("goal"), SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch,
        subtaskChoice = SubtaskChoice(SubtaskKey.Subtask(1)),
      ),
    )
    val open = mutableStateOf(true)
    var copied: DetailAction.Copy? = null
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          DetailScreen(state.value, DetailScreenHost(open.value, "Ctrl I", { open.value = !open.value }, { open.value = true })) {
            if (it is DetailAction.Copy) copied = it
          }
        }
      }
    }

    onNodeWithContentDescription("Copy workflow id").assertExists()
    onNodeWithText("claude-opus-5-5 · high").assertExists()
    onNodeWithText("claude").assertExists()
    onNodeWithText("Started").assertExists()
    onNodeWithText("Updated").assertExists()
    onNode(hasText("3") and !hasClickAction()).assertExists()
    onNodeWithText("Liveness").assertDoesNotExist()
    onNodeWithText("Invocation").assertDoesNotExist()
    onNodeWithText("Fixes").assertDoesNotExist()
    onNodeWithContentDescription("Copy workflow id").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
    onNodeWithContentDescription("Copy workflow id").performKeyInput { pressKey(Key.Enter) }
    runOnIdle { assertEquals(DetailAction.Copy("wf-sub-1"), copied) }

    runOnIdle { state.value = state.value.copy(subtaskChoice = SubtaskChoice(SubtaskKey.Subtask(2))) }
    onNodeWithText("Liveness").assertExists()
    onNodeWithText("Invocation").assertDoesNotExist()

    runOnIdle { state.value = state.value.copy(subtaskChoice = SubtaskChoice(SubtaskKey.Subtask(3))) }
    onNodeWithText("Execution").assertDoesNotExist()
    onNodeWithText("Runs and fixes").assertDoesNotExist()
    onNodeWithText("Validation").assertDoesNotExist()

    runOnIdle { state.value = state.value.copy(subtaskChoice = SubtaskChoice(SubtaskKey.GoalFinish)) }
    onNodeWithContentDescription("Copy workflow id").assertExists()
    onNodeWithText("Fixes").assertExists()
    onNodeWithContentDescription("Copy workflow id").performClick()
    runOnIdle { assertEquals(DetailAction.Copy("goal"), copied) }
  }

  @Test
  fun `keyboard activates commit copy audit show and close controls`() = runComposeUiTest {
    val commit = "0123456789abcdef0123456789abcdef01234567"
    val subtask = WorkSubtask(1, "Completed work", "complete", commit, null, emptyList())
    val detail = emptyDetail.copy(
      subtasks = listOf(subtask),
      phaseResults = listOf(
        SubtaskPhaseResults(
          1,
          emptyList(),
          null,
          null,
          auditResult = SubtaskAuditResult(
            AuditOutcome.Failed,
            1,
            2,
            listOf(AuditCriterion("AC-001", "Keep selected runs scoped", true, null)),
            listOf(AuditAttempt(1, false, 1, null)),
            1,
          ),
          phaseCards = listOf(finishCard("audit", PhaseCardState.Failed)),
        ),
      ),
    )
    val state = mutableStateOf(
      DetailUiState(
        workItem("goal"), SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch,
        subtaskChoice = SubtaskChoice(SubtaskKey.Subtask(1)),
      ),
    )
    val open = mutableStateOf(true)
    var copied: DetailAction.Copy? = null
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          DetailScreen(state.value, DetailScreenHost(open.value, "Ctrl I", { open.value = !open.value }, { open.value = true })) {
            if (it is DetailAction.Copy) copied = it
          }
        }
      }
    }

    onAllNodesWithContentDescription("Copy commit")[1].performSemanticsAction(SemanticsActions.RequestFocus) { it() }
    onAllNodesWithContentDescription("Copy commit")[1].performKeyInput { pressKey(Key.Enter) }
    runOnIdle { assertEquals(DetailAction.Copy(commit), copied) }
    onNodeWithText("Show").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
    onNodeWithText("Show").performKeyInput { pressKey(Key.Enter) }
    onNodeWithText("Keep selected runs scoped").assertExists()
    onNodeWithContentDescription("Close details").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
    onNodeWithContentDescription("Close details").performKeyInput { pressKey(Key.Enter) }
    runOnIdle {
      assertFalse(open.value)
      assertTrue(copied is DetailAction.Copy)
    }
  }
}

private fun execution(workflowId: String, attempts: Map<String, Int>) = SubtaskExecution(
  workflowId = workflowId,
  agentId = "claude",
  model = "claude-opus-5-5",
  effort = "high",
  startedAt = epoch,
  updatedAt = epoch,
  phaseAttemptCounts = attempts,
)
