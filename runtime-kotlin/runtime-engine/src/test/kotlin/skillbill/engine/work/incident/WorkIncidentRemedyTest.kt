package skillbill.engine.work.incident

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import skillbill.goalrunner.model.quality.GoalQualityReadiness
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentResponsibleCategory
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportStatus

class WorkIncidentRemedyTest {
  @Test
  fun `blocked goal with ambiguous quality history suggests fresh quality for its issue key`() {
    val command =
      WorkIncidentUnblockCommands.freshQualityCommand(
        WorkflowStatus.BLOCKED,
        GoalQualityReadiness.HISTORICAL_AMBIGUITY,
      ) { "SKILL-453" }

    assertEquals("skill-bill goal fresh-quality SKILL-453 --reason \"<why>\"", command)
    assertEquals("Run: $command", workIncidentRemedy(facts(unblockCommand = command)))
    assertNull(
      WorkIncidentUnblockCommands.freshQualityCommand(WorkflowStatus.BLOCKED, GoalQualityReadiness.CLEAN_UNSTARTED) {
        "SKILL-453"
      },
    )
    assertNull(
      WorkIncidentUnblockCommands.freshQualityCommand(
        WorkflowStatus.RUNNING,
        GoalQualityReadiness.HISTORICAL_AMBIGUITY,
      ) { "SKILL-453" },
    )
  }

  @Test
  fun `unreported skill-bill defect asks the operator to file an issue with the incident token`() {
    val remedy = workIncidentRemedy(facts(category = BlockIncidentResponsibleCategory.SKILL_BILL_DEFECT))

    assertEquals(
      "If this is a skill-bill bug, file an issue at $SKILL_BILL_NEW_ISSUE_URL and include incident bi_1.",
      remedy,
    )
    assertNull(
      workIncidentRemedy(
        facts(category = BlockIncidentResponsibleCategory.SKILL_BILL_DEFECT, report = ProductReportStatus.DELIVERED),
      ),
    )
    assertNull(workIncidentRemedy(facts(category = BlockIncidentResponsibleCategory.USER_ENVIRONMENT)))
  }

  private fun facts(
    unblockCommand: String? = null,
    category: BlockIncidentResponsibleCategory? = BlockIncidentResponsibleCategory.UNRESOLVED,
    report: ProductReportStatus = ProductReportStatus.LOCAL_ONLY,
  ) = WorkIncidentRemedyFacts(
    incidentToken = "bi_1",
    unblockCommand = unblockCommand,
    responsibleCategory = category,
    noWorkaroundKnown = false,
    reportStatus = report,
  )
}
