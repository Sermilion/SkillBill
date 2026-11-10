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

@OptIn(ExperimentalTestApi::class)
class NewTaskBranchWarningTest {
  @Test
  fun `off default warning offers both branch choices and follows refreshed checkout`() = runComposeUiTest {
    val draft = testNewTaskDraft().apply { repository = NewTaskRepository("/repo", "repo") }
    var branches by mutableStateOf(RepositoryBranches("/repo", "main", emptyList(), emptyList(), "feature/current"))
    val actions = mutableListOf<NewTaskAction>()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        NewTaskBranchWarning(NewTaskUiState(branchRead = BranchReadState.Loaded("/repo", branches)), draft, true, actions::add)
      }
    }
    onNodeWithText("This checkout is on feature/current.", substring = true).assertExists()
    onNodeWithText("Use current branch as base").performClick()
    assertEquals(NewTaskAction.UseCurrentBranchAsBase, actions.last())
    onNodeWithText("Switch to main").performClick()
    assertEquals(NewTaskAction.SwitchToDefaultBranch, actions.last())

    runOnIdle { branches = branches.copy(currentBranch = "main") }
    onNodeWithText("Switch to main").assertDoesNotExist()
    onNodeWithText("This checkout is on feature/current.", substring = true).assertDoesNotExist()
  }

  @Test
  fun `pill shows live checkout independently of selected base and never borrows another repository branch`() {
    val read = BranchReadState.Loaded("/repo", RepositoryBranches("/repo", "main", emptyList(), emptyList(), "feature/current"))
    assertEquals("feature/current", repositoryPillBranch("/repo", read))
    assertEquals("Reading branch", repositoryPillBranch("/other", read))
    assertEquals("detached HEAD", repositoryPillBranch("/repo", read.copy(branches = read.branches.copy(currentBranch = null))))
  }
}
