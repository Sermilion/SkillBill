package skillbill.cli.qa

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.formatOption
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.qa.QaChecklistPayloadKeys
import skillbill.engine.featuretask.qa.checklist.QaChecklistOutcome
import skillbill.engine.featuretask.qa.checklist.QaChecklistRecorder
import skillbill.engine.featuretask.qa.checklist.QaStageReport
import skillbill.engine.featuretask.qa.checklist.QaStepReport
import skillbill.engine.featuretask.qa.checklist.QaTargetReport
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.idestatus.model.StandaloneQaStepStatus
import skillbill.ports.idestatus.model.StandaloneQaTargetState
import skillbill.ports.qa.QaChecklistPlanFile
import skillbill.workflow.taskruntime.model.qa.QaRunStageState
import java.nio.file.Path

private const val EXIT_RULE =
  "Prints recorded, rejected or failed and exits 0 for all three, because progress never decides a QA verdict."
private const val INVOCATION_HELP = "QA invocation id from the phase briefing."

@Inject
class QaPlanCommand(
  private val recorder: QaChecklistRecorder,
  private val state: CliRunState,
  private val planFile: QaChecklistPlanFile,
) : DocumentedCliCommand("plan", "Register the checklist plan of one QA invocation from a JSON file. $EXIT_RULE") {
  private val invocation by option("--invocation", help = INVOCATION_HELP).required()
  private val file by option("--file", help = "Path of the plan JSON file.").required()
  private val format by formatOption()

  override fun run() {
    val outcome =
      catchingNonCancellation {
        planFile.read(Path.of(file))
      }.fold(
        onSuccess = { text ->
          if (text == null) recorder.planFileOversized(invocation) else recorder.registerPlan(invocation, text)
        },
        onFailure = { recorder.planFileUnreadable(invocation) },
      )
    state.complete(outcome.toPayload(), format, exitCode = 0)
  }
}

@Inject
class QaStepCommand(
  private val recorder: QaChecklistRecorder,
  private val state: CliRunState,
) : DocumentedCliCommand("step", "Report one checklist step of one QA invocation. $EXIT_RULE") {
  private val invocation by option("--invocation", help = INVOCATION_HELP).required()
  private val case by option("--case", help = "Case id from the registered plan.").required()
  private val step by option("--step", help = "1-based step number within the case.").int().required()
  private val status by option("--status", help = "Step status.")
    .choice(
      StandaloneQaStepStatus.entries
        .filter { it != StandaloneQaStepStatus.PENDING }
        .associateBy { it.wireValue },
    )
    .required()
  private val note by option("--note", help = "Short note. Follow the report's redaction rules.")
  private val finding by option("--finding", help = "Finding id this step produced.")
  private val findingTitle by option("--finding-title", help = "Finding title. Defaults to the finding id.")
  private val evidence by option("--evidence", help = "Evidence path inside the invocation directory.").multiple()
  private val format by formatOption()

  override fun run() {
    val outcome =
      recorder.recordStep(QaStepReport(invocation, case, step, status, note, finding, findingTitle, evidence))
    state.complete(outcome.toPayload(), format, exitCode = 0)
  }
}

@Inject
class QaTargetCommand(
  private val recorder: QaChecklistRecorder,
  private val state: CliRunState,
) : DocumentedCliCommand("target", "Report the state of one QA target of one QA invocation. $EXIT_RULE") {
  private val invocation by option("--invocation", help = INVOCATION_HELP).required()
  private val name by option("--name", help = "Target kind from the QA run section.").required()
  private val targetState by option("--state", help = "Target state.")
    .choice(
      StandaloneQaTargetState.entries
        .filter { it != StandaloneQaTargetState.PENDING }
        .associateBy { it.wireValue },
    )
    .required()
  private val detail by option("--detail", help = "Short detail. Follow the report's redaction rules.")
  private val format by formatOption()

  override fun run() {
    val outcome = recorder.recordTarget(QaTargetReport(invocation, name, targetState, detail))
    state.complete(outcome.toPayload(), format, exitCode = 0)
  }
}

@Inject
class QaStageCommand(
  private val recorder: QaChecklistRecorder,
  private val state: CliRunState,
) : DocumentedCliCommand("stage", "Report an actual stage of one QA invocation. $EXIT_RULE") {
  private val invocation by option("--invocation", help = INVOCATION_HELP).required()
  private val name by option("--name", help = "QA operation reported by the runtime or pack guidance.").required()
  private val stageState by option("--state", help = "Stage state.")
    .choice(QaRunStageState.reportable.associate { it.wireValue to it.wireValue })
    .required()
  private val detail by option("--detail", help = "Short status detail. Follow the report's redaction rules.")
  private val format by formatOption()

  override fun run() {
    val outcome = recorder.recordStage(QaStageReport(invocation, name, stageState, detail))
    state.complete(outcome.toPayload(), format, exitCode = 0)
  }
}

@Inject
class QaScreenshotCommand(
  private val recorder: QaChecklistRecorder,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand("screenshot", "Retain one screenshot for an active QA invocation. $EXIT_RULE") {
  private val invocation by option("--invocation", help = INVOCATION_HELP).required()
  private val file by option("--file", help = "Screenshot path inside this invocation's evidence directory.").required()
  private val label by option("--label", help = "Short label for the captured screenshot.").required()
  private val asFrame by option("--frame", help = "Also retain this image as the occurrence's last frame.").flag()
  private val repoRoot by option("--repo-root", help = "Repository root. Defaults to the invocation repository root.")
  private val format by formatOption()

  override fun run() {
    val root = resolveCliRepositoryRoot(repoRoot, inputs)
    val outcome =
      catchingNonCancellation { Path.of(file) }.fold(
        onSuccess = { source -> recorder.recordScreenshot(root, invocation, source, label, asFrame) },
        onFailure = { QaChecklistOutcome.Rejected("screenshot path is invalid") },
      )
    state.complete(outcome.toPayload(), format, exitCode = 0)
  }
}

private enum class QaProgressStatus(val wireValue: String) {
  RECORDED("recorded"),
  REJECTED("rejected"),
  FAILED("failed"),
}

private fun QaChecklistOutcome.toPayload(): Map<String, Any?> =
  when (this) {
    is QaChecklistOutcome.Recorded ->
      buildMap {
        put(QaChecklistPayloadKeys.STATUS, QaProgressStatus.RECORDED.wireValue)
        if (rejectedEvidence.isNotEmpty()) put(QaChecklistPayloadKeys.REJECTED_EVIDENCE, rejectedEvidence)
        retainedPath?.let { path -> put(QaChecklistPayloadKeys.PATH, path) }
      }
    is QaChecklistOutcome.Rejected ->
      mapOf(
        QaChecklistPayloadKeys.STATUS to QaProgressStatus.REJECTED.wireValue,
        QaChecklistPayloadKeys.REASON to reason,
      )
    is QaChecklistOutcome.Failed ->
      mapOf(
        QaChecklistPayloadKeys.STATUS to QaProgressStatus.FAILED.wireValue,
        QaChecklistPayloadKeys.REASON to reason,
      )
  }
