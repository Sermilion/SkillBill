package skillbill.cli.goal.core

import me.tatarka.inject.annotations.Inject
import skillbill.application.telemetry.service.TelemetryService
import skillbill.cli.goal.run.goalRunText
import skillbill.cli.goal.run.toGoalRunCliMap
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.drainTelemetryOnCompletion
import skillbill.cli.kernel.work.GoalIncidentPresentationKeys
import skillbill.cli.kernel.work.incidentPayload
import skillbill.cli.kernel.work.incidentText
import skillbill.cli.kernel.work.readGoalIncidentDetails
import skillbill.engine.work.IdeStatusService
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.ports.diagnostics.RuntimeDiagnostics

@Inject
class GoalRunReportPresentation(
  private val state: CliRunState,
  private val ideStatusService: IdeStatusService,
  private val telemetryService: TelemetryService,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun complete(report: GoalRunnerRunReport) {
    val references =
      listOf("parent" to report.parentWorkflowId) +
        (report as? GoalRunnerRunReport.Stopped)?.let { listOf("child" to it.stop.workflowId) }.orEmpty()
    val incidents = readGoalIncidentDetails(ideStatusService, references, retainFailures = true)
    val payload =
      report.toGoalRunCliMap() +
        (GoalIncidentPresentationKeys.INCIDENT_DETAILS to incidents.incidentPayload())
    state.completeText(
      goalRunText(report) +
        incidents.incidentText(false).takeIf { it.isNotEmpty() }
          ?.joinToString("\n", prefix = "\n").orEmpty(),
      payload,
      exitCode = report.goalRunExitCode(),
    )
    drainTelemetryOnCompletion(telemetryService, diagnostics)
  }
}
