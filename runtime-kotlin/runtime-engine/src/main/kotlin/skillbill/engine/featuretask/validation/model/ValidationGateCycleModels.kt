package skillbill.engine.featuretask.validation.model

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.model.phase.ValidationFindingSetProjection
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRepairWindowPhase
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRunRecord
import java.nio.file.Path

sealed interface ValidationGateResolution {
  data class Declared(
    val packSlug: String,
    val declaration: ValidationGateDeclaration,
  ) : ValidationGateResolution

  data class Absent(val routedPackSlug: String?) : ValidationGateResolution

  data class Incompatible(val reason: String) : ValidationGateResolution
}

const val UNPARSEABLE_GATE_FAILURE_RULE_ID: String = "unparseable_gate_failure"

fun requiresUnparseableGateTriage(findings: List<ValidationGateFinding>): Boolean =
  findings.size == 1 && findings.single().ruleOrTestId == UNPARSEABLE_GATE_FAILURE_RULE_ID

fun interface ValidationGateAgentTriageLauncher {
  fun launch(findings: ValidationFindingSetProjection): ValidationGateTriageResult
}

sealed interface ValidationGateTriageResult {
  data class Captured(val validationRepairPlan: String) : ValidationGateTriageResult

  data class Stopped(val outcome: ValidationGateCycleTerminalOutcome) : ValidationGateTriageResult

  data object Empty : ValidationGateTriageResult
}

fun interface ValidationGateAgentRepairLauncher {
  fun launch(
    findings: ValidationFindingSetProjection,
    repairIteration: Int,
    triagePlan: String?,
  ): ValidationGateAgentRepairResult
}

sealed interface ValidationGateAgentRepairResult {
  data class Paused(val reason: String) : ValidationGateAgentRepairResult

  data class Completed(val output: FeatureTaskRuntimePhaseOutput) : ValidationGateAgentRepairResult

  data class Blocked(
    val reason: String,
    val failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
  ) : ValidationGateAgentRepairResult
}

sealed interface ValidationGateCycleResult {
  data class Terminal(val outcome: ValidationGateCycleTerminalOutcome) : ValidationGateCycleResult
}

sealed interface ValidationGateCycleTerminalOutcome {
  data class Paused(val reason: String) : ValidationGateCycleTerminalOutcome

  data class Completed(val output: FeatureTaskRuntimePhaseOutput) : ValidationGateCycleTerminalOutcome

  data class Blocked(
    val reason: String,
    val remainingFindings: ValidationFindingSetProjection? = null,
    val measurements: List<FeatureTaskRuntimeValidationGateRunRecord> = emptyList(),
    val failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
  ) : ValidationGateCycleTerminalOutcome
}

fun interface ValidationGateProgressStore {
  fun persist(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
  )

  fun load(workflowId: String): FeatureTaskRuntimeValidationGateProgress? = null
}

data class ValidationGateProgressWrite(
  val repairWindowPhase: FeatureTaskRuntimeValidationGateRepairWindowPhase,
  val remainingFindings: ValidationFindingSetProjection?,
  val completeFindings: List<ValidationGateFinding>,
  val repairsUsed: Int,
  val capturedTriagePlan: String?,
) {
  companion object {
    fun findingsOpen(
      completeFindings: List<ValidationGateFinding>,
      repairsUsed: Int,
      capturedTriagePlan: String?,
      remainingFindings: ValidationFindingSetProjection? = null,
    ): ValidationGateProgressWrite =
      ValidationGateProgressWrite(
        repairWindowPhase = FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN,
        remainingFindings = remainingFindings,
        completeFindings = completeFindings,
        repairsUsed = repairsUsed,
        capturedTriagePlan = capturedTriagePlan,
      )
  }
}

data class ValidationGateCycleRequest(
  val repoRoot: Path,
  val request: FeatureTaskRuntimeRunFacts,
  val phaseId: String,
  val validationDepth: ValidationDepth,
  val changedPaths: List<String>,
  val repositoryCheckpoint: String,
  val agentRepairLauncher: ValidationGateAgentRepairLauncher,
  val progressStore: ValidationGateProgressStore,
  val agentTriageLauncher: ValidationGateAgentTriageLauncher =
    ValidationGateAgentTriageLauncher {
      ValidationGateTriageResult.Empty
    },
  val commandFamily: ValidationGateCommandFamily = ValidationGateCommandFamily.BUILD,
  val repositoryCheckpointProvider: () -> String? = { repositoryCheckpoint },
)
