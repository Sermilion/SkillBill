package skillbill.cli.kernel.work

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import skillbill.contracts.work.WorkIncidentWireKeys
import skillbill.idestatus.model.IdeStatusFreshness
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusStep
import skillbill.ports.idestatus.model.WorkDetailItem
import skillbill.ports.idestatus.model.WorkIncidentPrerequisite
import skillbill.ports.idestatus.model.WorkIncidentProgress
import skillbill.ports.idestatus.model.WorkIncidentProjection
import skillbill.ports.idestatus.model.WorkIncidentRecoveryProjection
import skillbill.ports.idestatus.model.WorkIncidentReportProjection
import skillbill.ports.idestatus.model.WorkIncidentTelemetryProjection
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCertainty
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentResponsibleCategory
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportStatus
import java.time.Instant

class WorkIncidentPayloadMappingTest {
  @Test
  fun `json and terminal use the same recovery command with each unmet prerequisite`() {
    val projection =
      WorkIncidentProjection(
        incidentToken = "bi_123",
        revision = 4,
        progress = WorkIncidentProgress.NEEDS_PREREQUISITES,
        summary = "Provider overloaded",
        cause = "Provider returned 529 overloaded",
        certainty = BlockIncidentCertainty.SUSPECTED,
        responsibleCategory = BlockIncidentResponsibleCategory.EXTERNAL_SERVICE,
        nextAction = "Confirm credentials",
        noWorkaroundKnown = false,
        prerequisites =
          listOf(
            WorkIncidentPrerequisite("credentials_configured", "Confirm credentials are configured."),
            WorkIncidentPrerequisite("external_service_available", "Confirm the external service is available."),
          ),
        evidence = emptyList(),
        degradations = emptySet(),
        recovery = WorkIncidentRecoveryProjection(null, null, null, "workflow", null, null, null, null),
        report = WorkIncidentReportProjection(ProductReportStatus.LOCAL_ONLY, null, null),
        telemetry = WorkIncidentTelemetryProjection("disabled", null, "bi_123", "workflow", null),
        observedAt = Instant.parse("2026-10-23T10:00:00Z"),
        settledAt = null,
      )
    val jsonCommand = workIncidentWireMap(projection, "SKILL-435")[WorkIncidentWireKeys.RECOVER_COMMAND]
    val detail =
      WorkDetailItem(
        snapshot =
          IdeStatusSnapshot(
            repositoryIdentity = "fixture",
            lifecycleState = IdeStatusLifecycleState.BLOCKED,
            currentStep = IdeStatusStep("implement", "Implement"),
            updatedAt = projection.observedAt,
            freshness = IdeStatusFreshness.FRESH,
            summary = projection.summary,
          ),
        recoveryWorkId = "SKILL-435",
        repositoryRoot = "/fixture",
        stepPipeline = listOf("implement"),
        incident = projection,
      )
    val textCommand =
      incidentTextLines(detail, verbose = false)
        .first { it.startsWith("Recover:") }
        .removePrefix("Recover: ")

    val expectedCommand =
      "skill-bill work recover SKILL-435 --incident bi_123 --revision 4 " +
        "--confirm-prerequisite credentials_configured --confirm-prerequisite external_service_available"
    assertEquals(expectedCommand, jsonCommand)
    assertEquals(jsonCommand, textCommand)
    assertTrue(jsonCommand is String)
  }
}
