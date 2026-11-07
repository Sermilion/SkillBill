package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.PlanWave
import dev.skillbill.desktop.core.domain.model.StandalonePlanProgress
import dev.skillbill.desktop.core.domain.model.StandalonePlanStage
import dev.skillbill.desktop.core.domain.model.StandalonePlanUnit
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class PlanTextTest {
  @Test
  fun `pending fan out with a writing unit is running from the earliest unit start`() {
    val later = unit(4).copy(startedAt = epoch + 4.seconds)
    val earlier = unit(3).copy(startedAt = epoch + 2.seconds)
    val plan = progress(listOf(later, earlier)).copy(stages = listOf(StandalonePlanStage("plan_subtasks", null, null, null)))

    val stage = planStageViews(workItem("plan", kind = WorkKind.Phase), emptyDetail.copy(standalonePlan = plan)).last()

    assertEquals(PlanStageState.Running, stage.state)
    assertEquals(epoch + 2.seconds, stage.startedAt)
  }

  @Test
  fun `live finalization keeps saved spec progress instead of returning to repository reading`() {
    val item = workItem("plan", kind = WorkKind.Phase)
    val plan = progress(listOf(unit(1).copy(state = "saved"))).copy(
      plannedSubtaskCount = 1,
      stages = listOf(StandalonePlanStage("plan_subtasks", epoch, epoch + 2.seconds, null, "completed")),
    )

    assertEquals("Writing specs. 1 of 1 saved.", planStatusText(item, emptyDetail.copy(standalonePlan = plan)))
  }

  @Test
  fun `truncated tool output and subtask prefix remain readable`() {
    val parsed = planOutputText("[subtask 3] Tool Bash {\"command\":\"./gradlew che", emptyList())

    assertEquals(3, parsed.subtaskId)
    assertEquals("command", parsed.kind)
    assertEquals("Ran ./gradlew che", parsed.display)
  }

  @Test
  fun `absolute paths under a root are shortened and compressed`() {
    assertEquals(
      "runtime-engine/…/plan/PlanFanOutStrategy.kt",
      shortenPlanPath("/repo/runtime-engine/src/main/kotlin/skillbill/plan/PlanFanOutStrategy.kt", listOf("/repo")),
    )
    assertEquals(
      "cat runtime-engine/…/plan/PlanFanOutStrategy.kt",
      planOutputText("Tool Bash {\"command\":\"cat /repo/runtime-engine/src/main/kotlin/skillbill/plan/PlanFanOutStrategy.kt\"}", listOf("/repo")).subject,
    )
  }

  @Test
  fun `mcp tool output names the called tool`() {
    val parsed = planOutputText("Tool mcp__skill-bill__doctor {}", emptyList())

    assertEquals("mcp", parsed.kind)
    assertEquals("Called doctor", parsed.display)
  }

  @Test
  fun `activity summary counts folded read children as reads`() {
    val events = listOf(
      PlanActivityEvent("read", epoch, "A.kt", kind = "read", children = listOf(PlanActivityEvent("read-child", epoch, "B.kt", kind = "read"))),
      PlanActivityEvent("mcp", epoch, "doctor", kind = "mcp"),
    )

    assertEquals("2 read · 1 commands", planActivitySummary(events))
  }

  private fun unit(id: Int) = StandalonePlanUnit(id, "Subtask $id", "/specs/$id.md", emptyList(), "writing", null, null, null, null)

  private fun progress(units: List<StandalonePlanUnit>) = StandalonePlanProgress(
    null, emptyList(), 0, units.size, units.firstOrNull()?.subtaskId, units.map { it.subtaskId }, 5,
    null, null, units, null, 1, true, waves = listOf(PlanWave(1, units.map { it.subtaskId }, null)),
  )
}
