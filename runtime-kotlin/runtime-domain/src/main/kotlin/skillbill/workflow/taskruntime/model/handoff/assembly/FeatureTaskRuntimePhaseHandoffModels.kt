package skillbill.workflow.taskruntime.model.handoff.assembly

import skillbill.agent.model.PhaseOutput
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairLedger
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence

data class FeatureTaskRuntimePhaseOutput(
  val phaseId: String,
  val iteration: Int,
  val output: PhaseOutput,
  val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput? = null,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
) {
  constructor(
    phaseId: String,
    iteration: Int,
    payload: String,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput? = null,
    repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
  ) : this(
    phaseId,
    iteration,
    normalizedOutput?.output ?: PhaseOutput(value = payload),
    normalizedOutput,
    repairEvidence,
  )

  init {
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimePhaseOutput.phaseId must be non-blank." }
    require(iteration >= 1) { "FeatureTaskRuntimePhaseOutput.iteration must be >= 1, was $iteration." }
  }

  val payload: String
    get() = normalizedOutput?.canonicalJson ?: output.value
}

data class FeatureTaskRuntimeResolvedUpstreamOutputs(
  val outputsByPhaseId: Map<String, FeatureTaskRuntimePhaseOutput>,
)

data class FeatureTaskRuntimePhaseHandoff(
  val phaseId: String,
  val runInvariants: FeatureTaskRuntimeRunInvariants,
  val upstreamOutputs: FeatureTaskRuntimeResolvedUpstreamOutputs,
  val derivedContextKeys: List<String>,
  val projectionDeclarations: List<PhaseHandoffProjectionDeclaration> = emptyList(),
  val repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
  val expectedRepositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
  val branchIdentity: String? = null,
  val baseBranch: String = "main",
  val validationDepth: ValidationDepth = ValidationDepth.DEFAULT,
  val unselectedStepIds: Set<String> = emptySet(),
  val drivingVerdict: FeatureTaskRuntimeVerdict? = null,
  val repairLedger: FeatureTaskRuntimeRepairLedger? = null,
  val recordedFindingVerdicts: List<ReviewFindingVerdict> = emptyList(),
)

data class FeatureTaskRuntimePhaseDeclaration(
  val phaseId: String,
  val projectionDeclarations: List<PhaseHandoffProjectionDeclaration>,
  val derivedContextKeys: List<String>,
) {
  init {
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimePhaseDeclaration.phaseId must be non-blank." }
    require(projectionDeclarations.all { it.consumerPhaseId == phaseId }) {
      "FeatureTaskRuntimePhaseDeclaration '$phaseId' carries a projection declared for another consumer phase."
    }
    val names = projectionDeclarations.map { it.projectionName }
    require(names.distinct().size == names.size) {
      "FeatureTaskRuntimePhaseDeclaration '$phaseId' declares duplicate projection names: " +
        "${names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys}."
    }
  }

  val consumedUpstreamPhaseIds: List<String>
    get() =
      projectionDeclarations
        .mapNotNull { (it.sourceRef as? FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput)?.producingPhaseId }
        .distinct()
}
