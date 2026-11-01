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
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateTriageDisposition
import java.nio.file.Path

public sealed interface ValidationGateResolution {
  public data class Declared(
    public val packSlug: String,
    public val declaration: ValidationGateDeclaration,
  ) : ValidationGateResolution

  public data class Absent(val routedPackSlug: String?) : ValidationGateResolution

  public data class Incompatible(val reason: String) : ValidationGateResolution
}

public const val UNPARSEABLE_GATE_FAILURE_RULE_ID: String = "unparseable_gate_failure"

public fun requiresUnparseableGateTriage(findings: List<ValidationGateFinding>): Boolean =
  findings.size == 1 && findings.single().ruleOrTestId == UNPARSEABLE_GATE_FAILURE_RULE_ID

public fun interface ValidationGateAgentTriageLauncher {
  public fun launch(findings: ValidationFindingSetProjection): ValidationGateTriageResult
}

public sealed interface ValidationGateTriageResult {
  public data class Captured(val validationRepairPlan: String) : ValidationGateTriageResult

  public data class Stopped(val outcome: ValidationGateCycleTerminalOutcome) : ValidationGateTriageResult

  public data object Empty : ValidationGateTriageResult
}

public fun interface ValidationGateAgentRepairLauncher {
  public fun launch(
    findings: ValidationFindingSetProjection,
    repairIteration: Int,
    triagePlan: String?,
  ): ValidationGateAgentRepairResult
}

public sealed interface ValidationGateAgentRepairResult {
  public data class Paused(val reason: String) : ValidationGateAgentRepairResult

  public data class Completed(val output: FeatureTaskRuntimePhaseOutput) : ValidationGateAgentRepairResult

  public data class Blocked(
    public val reason: String,
    public val failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
    public val failureCode: String? = null,
  ) : ValidationGateAgentRepairResult
}

public sealed interface ValidationGateCycleResult {
  public data class Terminal(val outcome: ValidationGateCycleTerminalOutcome) : ValidationGateCycleResult
}

public sealed interface ValidationGateCycleTerminalOutcome {
  public data class Paused(val reason: String) : ValidationGateCycleTerminalOutcome

  public data class Completed(val output: FeatureTaskRuntimePhaseOutput) : ValidationGateCycleTerminalOutcome

  public data class Blocked(
    public val reason: String,
    public val remainingFindings: ValidationFindingSetProjection? = null,
    public val measurements: List<FeatureTaskRuntimeValidationGateRunRecord> = emptyList(),
    public val failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
    public val failureCode: String? = null,
  ) : ValidationGateCycleTerminalOutcome
}

public interface ValidationGateProgressStore {
  public fun persist(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
  )

  public fun load(workflowId: String): FeatureTaskRuntimeValidationGateProgress?
}

internal data class ValidationGateProgressWrite(
  val repairWindowPhase: FeatureTaskRuntimeValidationGateRepairWindowPhase,
  val remainingFindings: ValidationFindingSetProjection?,
  val completeFindings: List<ValidationGateFinding>,
  val repairsUsed: Int,
  val capturedTriagePlan: String?,
  val triageDisposition: FeatureTaskRuntimeValidationGateTriageDisposition? = null,
) {
  companion object {
    fun findingsOpen(
      completeFindings: List<ValidationGateFinding>,
      repairsUsed: Int,
      capturedTriagePlan: String?,
      remainingFindings: ValidationFindingSetProjection? = null,
      triageDisposition: FeatureTaskRuntimeValidationGateTriageDisposition? = null,
    ): ValidationGateProgressWrite =
      ValidationGateProgressWrite(
        repairWindowPhase = FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN,
        remainingFindings = remainingFindings,
        completeFindings = completeFindings,
        repairsUsed = repairsUsed,
        capturedTriagePlan = capturedTriagePlan,
        triageDisposition = triageDisposition,
      )
  }
}

public data class ValidationGateCycleRequest(
  public val repoRoot: Path,
  public val request: FeatureTaskRuntimeRunFacts,
  public val phaseId: String,
  public val validationDepth: ValidationDepth,
  public val changedPaths: List<String>,
  public val repositoryCheckpoint: String,
  public val agentRepairLauncher: ValidationGateAgentRepairLauncher,
  public val progressStore: ValidationGateProgressStore,
  public val agentTriageLauncher: ValidationGateAgentTriageLauncher =
    ValidationGateAgentTriageLauncher {
      ValidationGateTriageResult.Empty
    },
  public val commandFamily: ValidationGateCommandFamily = ValidationGateCommandFamily.BUILD,
  public val repositoryCheckpointProvider: () -> String? = { repositoryCheckpoint },
  internal val preserveConsumedRepairTurns: Boolean = false,
  internal val progressKey: String = request.workflowId,
)
