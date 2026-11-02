package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.designsystem.detail.PipelineStepState
import dev.skillbill.desktop.core.designsystem.detail.breakdownRows
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkDependency
import dev.skillbill.desktop.core.domain.model.WorkSubtask
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtaskIndexTest {
  private fun subtask(id: Int, status: String, vararg dependsOn: Pair<Int, Boolean>) =
    WorkSubtask(id, "Subtask $id", status, null, null, dependsOn.map { (target, optional) -> WorkDependency(target, optional, skipped = false) })

  @Test
  fun `a goal opens on its blocked subtask, else the current one, else the first`() {
    val running = workItem("SKILL-1").copy(currentSubtaskId = "2")
    val blocked = emptyDetail.copy(subtasks = listOf(subtask(1, "complete"), subtask(2, "in_progress"), subtask(3, "blocked")))
    val current = emptyDetail.copy(subtasks = listOf(subtask(1, "complete"), subtask(2, "in_progress"), subtask(3, "pending")))
    val idle = workItem("SKILL-1")

    assertEquals(SubtaskKey.Subtask(3), selectedSubtask(running, blocked, chosen = null))
    assertEquals(SubtaskKey.Subtask(2), selectedSubtask(running, current, chosen = null))
    assertEquals(SubtaskKey.Subtask(1), selectedSubtask(idle, current, chosen = null))
    assertEquals(SubtaskKey.GoalFinish, selectedSubtask(running, blocked, chosen = SubtaskKey.GoalFinish))
    assertEquals(SubtaskKey.Subtask(3), selectedSubtask(running, blocked, chosen = SubtaskKey.Subtask(9)))
  }

  @Test
  fun `each subtask places itself on the step pipeline`() {
    val item = workItem("SKILL-1").copy(lifecycle = Lifecycle.Active, currentSubtaskId = "2")
    val detail = emptyDetail.copy(stepPipeline = listOf("implement", "review", "fix"), currentPhase = "review")
    fun states(status: StatusKind, current: Boolean) = subtaskSteps(item, detail, status, current).map { it.state }

    assertEquals(List(3) { PipelineStepState.Done }, states(StatusKind.Completed, current = false))
    assertEquals(List(3) { PipelineStepState.Done }, states(StatusKind.CompletedNoChange, current = true))
    assertEquals(List(3) { PipelineStepState.Pending }, states(StatusKind.Pending, current = false))
    assertEquals(listOf(PipelineStepState.Done, PipelineStepState.Current, PipelineStepState.Pending), states(StatusKind.Active, current = true))
  }

  @Test
  fun `phase pipeline steps do not include execution attempt labels`() {
    val item = workItem("SKILL-1").copy(
      lifecycle = Lifecycle.Active,
      phaseExecutionLabel = "repair attempt 2",
    )
    val detail = emptyDetail.copy(stepPipeline = listOf("audit_implement_fix", "review"), currentPhase = "audit_implement_fix")

    assertEquals(listOf("audit_implement_fix", "review"), pipelineSteps(item, detail, detail.stepPipeline).map { it.label })
    assertEquals(listOf(null, null), pipelineSteps(item, detail, detail.stepPipeline).map { it.count })
  }

  @Test
  fun `needed by lists every subtask that depends on this one, keeping the optional mark`() {
    val detail = emptyDetail.copy(
      subtasks = listOf(subtask(1, "complete"), subtask(2, "pending", 1 to false), subtask(3, "pending", 1 to true, 2 to false)),
    )

    assertEquals(listOf(2 to false, 3 to true), neededBy(detail, 1).map { it.subtaskId to it.optional })
    assertEquals(listOf(3 to false), neededBy(detail, 2).map { it.subtaskId to it.optional })
    assertEquals(emptyList(), neededBy(detail, 3))
  }

  @Test
  fun `a breakdown shows the four largest counts and folds the rest behind plus n more`() {
    val counts = mapOf("plan" to 1, "implement" to 4, "review" to 0, "fix" to 3, "validate" to 2, "qa" to 1, "pr" to 5)

    val folded = breakdownRows(counts, open = false)
    assertEquals(listOf("pr" to 5, "implement" to 4, "fix" to 3, "validate" to 2), folded.shown)
    assertEquals(2, folded.hidden)

    val open = breakdownRows(counts, open = true)
    assertEquals(6, open.shown.size)
    assertEquals(0, open.hidden)
    assertEquals(true, open.folds)
  }
}
