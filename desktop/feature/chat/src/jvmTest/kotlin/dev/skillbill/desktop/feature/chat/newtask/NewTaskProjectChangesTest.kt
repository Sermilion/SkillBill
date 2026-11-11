package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ChatChanges
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.domain.model.ChatWorktreeChanges
import dev.skillbill.desktop.core.domain.model.ChangeScopeTimeline
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.ChangeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class NewTaskProjectChangesTest {
  @Test
  fun `same named files in two worktrees open their own checkout diff`() = runComposeUiTest {
    val baseline = ChatChangesBaseline("head", emptyList(), null, Instant.fromEpochMilliseconds(1))
    val changed = ChatChanges(listOf(ChangedFile("same.kt", null, ChangeStatus.Modified, 1, 1)), 1, 1, 0, true,
      "head", ChangeScopeTimeline("head", "head", emptyList()))
    val groups = listOf("/work/app", "/work/main").map { root -> ChatWorktreeChanges(root, baseline, setOf("same.kt"), changed) }
    val aggregate = changed.copy(files = groups.flatMap { it.changes.files }, insertions = 2, deletions = 2, worktrees = groups)
    var opened: Pair<String, String?>? = null
    setContent {
      SkillBillTheme(reducedMotion = true) {
        NewTaskChangesPanel(ChatChangesState.Shown(aggregate, baseline, false, emptySet()),
          onOpenWorktree = { worktree, path -> opened = worktree.repositoryRoot to path })
      }
    }

    onAllNodesWithContentDescription("same.kt")[1].performClick()
    runOnIdle { assertEquals("/work/main" to "same.kt", opened) }
    onAllNodesWithText("Open diff")[0].performClick()
    runOnIdle { assertEquals("/work/app" to null, opened) }
  }
}
