package skillbill.workflow.taskruntime.model.handoff.task

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseHandoffSchemaError
import skillbill.review.context.model.launch.CodeReviewExecutionMode
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairLedger
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.core.MAX_ACCEPTANCE_CRITERION_ORDINAL
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence

data class FeatureTaskRuntimeRunInvariants(
  val specReference: String,
  val featureSize: FeatureTaskRuntimeFeatureSize = FeatureTaskRuntimeFeatureSize.DEFAULT,
  val acceptanceCriteria: List<String>,
  val mandatesAndOverrides: List<String>,
  val codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.DEFAULT,
  val agentAddonSelection: AgentAddonSelection = AgentAddonSelection(),
) {
  init {
    require(specReference.isNotBlank()) {
      "FeatureTaskRuntimeRunInvariants.specReference must be a non-blank spec reference; " +
        "run-invariants cannot be partially specified."
    }
    require(acceptanceCriteria.isNotEmpty()) {
      "FeatureTaskRuntimeRunInvariants.acceptanceCriteria must list at least one criterion; " +
        "a run with no acceptance criteria has no contract to satisfy."
    }
    require(acceptanceCriteria.none(String::isBlank)) {
      "FeatureTaskRuntimeRunInvariants.acceptanceCriteria must not contain blank entries."
    }
    require(acceptanceCriteria.size <= MAX_ACCEPTANCE_CRITERION_ORDINAL) {
      "FeatureTaskRuntimeRunInvariants.acceptanceCriteria supports at most " +
        "$MAX_ACCEPTANCE_CRITERION_ORDINAL criteria, had ${acceptanceCriteria.size}."
    }
  }
}

enum class FeatureTaskRuntimeFeatureSize {
  SMALL,
  MEDIUM,
  LARGE,
  ;

  companion object {
    val DEFAULT: FeatureTaskRuntimeFeatureSize = MEDIUM

    fun fromWire(value: String): FeatureTaskRuntimeFeatureSize =
      entries.firstOrNull { it.name == value.trim().uppercase() }
        ?: throw InvalidFeatureTaskRuntimePhaseHandoffSchemaError(
          sourceLabel = "<wire>",
          reason = "Unknown feature-task-runtime feature size '$value'.",
        )
  }
}

enum class FeatureTaskRuntimePreplanCeremony(val wireValue: String, val promptLabel: String) {
  LIGHT("light", "lighter preplan focused on the current unit of work"),
  FULL("full", "full preplan covering boundaries, risks, rollout, and unknowns"),
}

enum class FeatureTaskRuntimeReviewScope(val wireValue: String, val promptLabel: String) {
  CURRENT_UNIT_OF_WORK("current_unit_of_work", "current-unit-of-work review scope"),
  BRANCH_DIFF("branch_diff", "branch-diff review scope"),
}

enum class FeatureTaskRuntimeAuditCeremony(val wireValue: String, val promptLabel: String) {
  LIGHT("light", "lighter audit over the current unit of work and every listed criterion"),
  FULL_PER_CRITERION("full_per_criterion", "full per-criterion completeness audit"),
}

data class FeatureTaskRuntimeCeremonyScaling(
  val preplanCeremony: FeatureTaskRuntimePreplanCeremony,
  val reviewScope: FeatureTaskRuntimeReviewScope,
  val auditCeremony: FeatureTaskRuntimeAuditCeremony,
) {
  fun toBriefingLines(): List<String> =
    listOf(
      "preplan_ceremony: ${preplanCeremony.wireValue} (${preplanCeremony.promptLabel})",
      "review_scope: ${reviewScope.wireValue} (${reviewScope.promptLabel})",
      "audit_ceremony: ${auditCeremony.wireValue} (${auditCeremony.promptLabel})",
    )
}

data class FeatureTaskRuntimePhaseOutput(
  val phaseId: String,
  val iteration: Int,
  val payload: String,
  val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput? = null,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
) {
  init {
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimePhaseOutput.phaseId must be non-blank." }
    require(iteration >= 1) { "FeatureTaskRuntimePhaseOutput.iteration must be >= 1, was $iteration." }
  }
}

data class FeatureTaskRuntimeResolvedUpstreamOutputs(
  val outputsByPhaseId: Map<String, FeatureTaskRuntimePhaseOutput>,
)

data class NormalizedFeatureTaskRuntimePhaseOutput(
  val canonicalJson: String,
  internal val envelope: Map<String, Any?>,
) {
  fun envelopePayload(): Any = envelope
}

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
