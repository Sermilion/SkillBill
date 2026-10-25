package skillbill.engine.featuretask.persist.acceptance

import skillbill.engine.featuretask.model.audit.AcceptanceAuditAcceptedCriterion
import skillbill.engine.featuretask.model.audit.AcceptanceAuditInteractionTraceScope
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.persist.sha256Hex
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairDependency
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionAuditResultMode
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionTraceParse
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionTraceParser
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionTraceSettlement
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION as AUDIT_RESULT_VERSION
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

internal object AcceptanceCriterionInteractionTraceRound {
  private const val SCOPE_KEY_LENGTH = 16
  internal const val LINE_GRAMMAR =
    "Answer with exactly one line per listed scenario, in the form " +
      "`Interaction <ref>: holds trace <path:line citations>`, " +
      "`Interaction <ref>: broken owner AC-NNN trace <path:line citations>` or " +
      "`Interaction <ref>: unknown trace <reason>`."

  fun scopeKey(state: AcceptanceCriterionRepairState): String =
    sha256Hex(
      listOf(
        state.specDigest,
        state.interactionTrace.pendingScenarioRefs.joinToString(","),
        state.outcomes.size,
        state.proseAudits.size,
      ).joinToString("|"),
    ).take(SCOPE_KEY_LENGTH)

  fun rejection(malformed: AcceptanceCriterionInteractionTraceParse.Malformed): String =
    "${malformed.code.name}: ${malformed.reason} $LINE_GRAMMAR"

  fun active(scope: AcceptanceCriterionRepairRunState): Boolean =
    scope.snapshot?.stage == AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED

  fun promptScope(scope: AcceptanceCriterionRepairRunState): AcceptanceAuditInteractionTraceScope? {
    if (!active(scope)) return null
    val state = checkNotNull(scope.snapshot)
    val binding = checkNotNull(scope.binding) { "Interaction trace stage requires the bound scenario catalog." }
    val pendingRefs = AcceptanceCriterionInteractionTargets.pending(state, binding).map { it.scenarioRef }
    val scenarios = binding.interactionScenarios.associateBy { it.scenarioRef }
    return AcceptanceAuditInteractionTraceScope(
      pendingRefs = pendingRefs,
      pendingScenarios = pendingRefs.mapNotNull(scenarios::get),
      acceptedCriteria =
        state.criteria
          .filter { it.sourceDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED }
          .sortedBy { it.canonicalRef }
          .mapNotNull { criterion ->
            criterion.closureEvidenceRef?.let { AcceptanceAuditAcceptedCriterion(criterion.canonicalRef, it) }
          },
      sameFilePairs = AcceptanceCriterionInteractionTargets.sameFilePairs(state),
      recheckRefs = state.interactionTrace.recheckRefs,
    )
  }

  fun reaskRejection(
    scope: AcceptanceCriterionRepairRunState,
    prose: String,
  ): String? {
    val state = scope.snapshot?.takeIf { active(scope) } ?: return null
    val binding = scope.binding ?: return null
    val targets = AcceptanceCriterionInteractionTargets.pending(state, binding)
    val parsed = AcceptanceCriterionInteractionTraceParser.parse(prose, targets)
    val key = scopeKey(state)
    if (parsed !is AcceptanceCriterionInteractionTraceParse.Malformed || scope.traceReaskScope == key) return null
    scope.recordTraceReask(key)
    return rejection(parsed)
  }

  fun evidence(
    settlement: AcceptanceCriterionInteractionTraceSettlement,
    predicted: AcceptanceCriterionRepairState,
  ): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(
      mapOf(
        Keys.CONTRACT_VERSION to AUDIT_RESULT_VERSION,
        Keys.MODE to AcceptanceCriterionAuditResultMode.INTERACTION_TRACE.wireValue,
        Keys.CATALOG_ID to predicted.catalogId,
        Keys.SPEC_DIGEST to predicted.specDigest,
        Keys.EXECUTION_ID to predicted.executionId,
        Keys.TREE_DIGEST to settlement.treeDigest,
        Keys.CHECKPOINT_ID to settlement.checkpointId,
        Keys.PRODUCING_OUTPUT_ID to settlement.outputId,
        Keys.PRODUCING_OUTPUT_DIGEST to settlement.outputDigest,
        Keys.EXPECTED_REVISION to null,
        Keys.CURRENT_CRITERION_REF to null,
        Keys.RESERVATION_ID to null,
        Keys.CRITERIA to predicted.criteria.map(::criterionFact),
        Keys.DEPENDENCIES to predicted.dependencies.map(::dependencyFact),
        Keys.REGRESSED_REFS to settlement.entries.mapNotNull { it.ownerRef }.distinct(),
        Keys.EDGE_CHANGES to emptyList<Any>(),
        Keys.INTERACTIONS to
          settlement.entries.map {
            mapOf(
              Keys.SCENARIO_REF to it.scenarioRef,
              Keys.VERDICT to it.verdict.wireValue,
              Keys.OWNER_REF to it.ownerRef,
              Keys.TRACE_REF to it.traceRef,
            )
          },
        Keys.RECHECKS to emptyList<Any>(),
      ),
    )

  private fun criterionFact(criterion: AcceptanceCriterionRepairCriterion): Map<String, Any?> =
    mapOf(
      Keys.CANONICAL_REF to criterion.canonicalRef,
      Keys.SOURCE_DISPOSITION to criterion.sourceDisposition.wireValue,
      Keys.VALIDATION_OBLIGATION to criterion.validationObligation.wireValue,
      Keys.VALIDATION_OWNER_AVAILABLE to criterion.validationOwnerAvailable,
      Keys.EVIDENCE_REF to criterion.evidenceRef,
    )

  private fun dependencyFact(dependency: AcceptanceCriterionRepairDependency): Map<String, Any?> =
    mapOf(
      Keys.DEPENDENT_REF to dependency.dependentRef,
      Keys.PREREQUISITE_REF to dependency.prerequisiteRef,
      Keys.REASON to dependency.reason,
      Keys.DECLARING_OUTPUT_REF to dependency.declaringOutputRef,
      Keys.SOURCE_EVIDENCE_REF to dependency.sourceEvidenceRef,
    )
}
