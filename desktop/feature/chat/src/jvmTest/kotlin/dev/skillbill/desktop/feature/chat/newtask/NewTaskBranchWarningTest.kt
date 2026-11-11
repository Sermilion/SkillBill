package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.RepositoryBranches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import dev.skillbill.desktop.feature.chat.ChatMessage
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind

@OptIn(ExperimentalTestApi::class)
class NewTaskBranchWarningTest {
  @Test
  fun `off default warning offers both branch choices and follows refreshed checkout`() = runComposeUiTest {
    val draft = testNewTaskDraft().apply { repository = NewTaskRepository("/repo", "repo") }
    var branches by mutableStateOf(RepositoryBranches("/repo", "main", emptyList(), emptyList(), "feature/current"))
    var requested by mutableStateOf(false)
    val actions = mutableListOf<NewTaskAction>()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        NewTaskBranchWarning(NewTaskUiState(branchRead = BranchReadState.Loaded("/repo", branches)), draft, true, actions::add, requested)
      }
    }
    onNodeWithText("Switch to main").assertDoesNotExist()
    runOnIdle { requested = true }
    onNodeWithText("This checkout is on feature/current.", substring = true).assertExists()
    onNodeWithText("Use current branch as base").performClick()
    assertEquals(NewTaskAction.UseCurrentBranchAsBase, actions.last())
    runOnIdle { draft.baseBranch = "feature/current" }
    onNodeWithText("Switch to main").assertDoesNotExist()
    runOnIdle { draft.baseBranch = "" }
    onNodeWithText("Switch to main").performClick()
    assertEquals(NewTaskAction.SwitchToDefaultBranch, actions.last())

    runOnIdle { branches = branches.copy(currentBranch = "main") }
    onNodeWithText("Switch to main").assertDoesNotExist()
    onNodeWithText("This checkout is on feature/current.", substring = true).assertDoesNotExist()
  }

  @Test
  fun `read only followup clears an earlier edit warning while operations and edits request branch choices`() {
    val read = ChatSuggestedAction("Inspect", "Inspect without edits", ChatPermissionMode.ReadOnly)
    val edit = read.copy(label = "Edit", mode = ChatPermissionMode.Act)
    val operation = read.copy(start = NewTaskActionKind.Verify)
    fun reply(action: ChatSuggestedAction) = ChatMessage.Agent("Ready", false, emptyList(), listOf(action))
    assertFalse(newTaskNeedsBranchWarning(emptyList()))
    assertFalse(newTaskNeedsBranchWarning(listOf(reply(read))))
    assertTrue(newTaskNeedsBranchWarning(listOf(reply(edit))))
    assertTrue(newTaskNeedsBranchWarning(listOf(reply(operation))))
    assertTrue(newTaskNeedsBranchWarning(listOf(ChatMessage.Operator(edit.instruction, edit))))
    assertFalse(newTaskNeedsBranchWarning(listOf(reply(edit), ChatMessage.Operator("What does this do?"))))
  }

  @Test
  fun `pill shows live checkout independently of selected base and never borrows another repository branch`() {
    val read = BranchReadState.Loaded("/repo", RepositoryBranches("/repo", "main", emptyList(), emptyList(), "feature/current"))
    assertEquals("feature/current", repositoryPillBranch("/repo", read))
    assertEquals("Reading branch", repositoryPillBranch("/other", read))
    assertEquals("detached HEAD", repositoryPillBranch("/repo", read.copy(branches = read.branches.copy(currentBranch = null))))
  }
}
