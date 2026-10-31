package skillbill.engine.featuretask.persist.acceptance

import skillbill.engine.featuretask.model.audit.AcceptanceAuditAcceptedCriterion
import skillbill.engine.featuretask.model.audit.AcceptanceAuditInteractionTraceScope
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairBinding
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairWriteResult
import skillbill.engine.featuretask.model.audit.UnknownInteractionScenarioContext
import skillbill.engine.featuretask.persist.UnitTestPathClassifier
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
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionRecheckUpdate
import skillbill.workflow.taskruntime.model.audit.interaction.recheckUpdateAfter
import skillbill.workflow.taskruntime.model.audit.interaction.unknownScenarios
import skillbill.workflow.taskruntime.model.audit.interaction.withRecheckUpdate
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION as AUDIT_RESULT_VERSION
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

public object AcceptanceCriterionInteractionTraceRound {
  private const val SCOPE_KEY_LENGTH = 16
  public const val LINE_GRAMMAR: String =
    "Answer with exactly one line per listed scenario, in the form " +
      "`Interaction <ref>: holds trace <path:line citations>`, " +
      "`Interaction <ref>: broken owner AC-NNN trace <path:line citations>` or " +
      "`Interaction <ref>: unknown trace <reason>`."

  internal const val RECHECK_GRAMMAR =
    "For each listed re-check, answer with exactly one line of the form " +
      "`Recheck AC-NNN: holds evidence <path:line, path:line>` or " +
      "`Recheck AC-NNN: regressed evidence <path:line, path:line>`."

  public fun scopeKey(state: AcceptanceCriterionRepairState): String =
    sha256Hex(
      listOf(
        state.specDigest,
        state.interactionTrace.pendingScenarioRefs.joinToString(","),
        state.interactionTrace.pendingRechecks.joinToString(","),
        state.outcomes.size,
        state.proseAudits.size,
      ).joinToString("|"),
    ).take(SCOPE_KEY_LENGTH)

  public fun grammar(
    scenarios: Boolean,
    rechecks: Boolean,
  ): String = listOfNotNull(LINE_GRAMMAR.takeIf { scenarios }, RECHECK_GRAMMAR.takeIf { rechecks }).joinToString(" ")

  public fun rejection(
    malformed: AcceptanceCriterionInteractionTraceParse.Malformed,
    state: AcceptanceCriterionRepairState,
  ): String =
    "${malformed.code.name}: ${malformed.reason} " +
      grammar(
        state.interactionTrace.pendingScenarioRefs.isNotEmpty(),
        state.interactionTrace.pendingRechecks.isNotEmpty(),
      )

  public fun active(scope: AcceptanceCriterionRepairRunState): Boolean =
    scope.snapshot?.stage == AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED

  internal fun promptScope(scope: AcceptanceCriterionRepairRunState): AcceptanceAuditInteractionTraceScope? {
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
      recheckRefs = state.interactionTrace.pendingRechecks,
    )
  }

  internal fun unknownContext(scope: AcceptanceCriterionRepairRunState): List<UnknownInteractionScenarioContext> {
    val state = scope.snapshot ?: return emptyList()
    val scenarios = scope.binding?.interactionScenarios.orEmpty().associateBy { it.scenarioRef }
    return state.unknownScenarios().mapNotNull { unknown ->
      scenarios[unknown.scenarioRef]?.let {
        UnknownInteractionScenarioContext(it.scenarioRef, it.entryPoint, it.outcome)
      }
    }
  }

  internal fun reaskRejection(
    scope: AcceptanceCriterionRepairRunState,
    prose: String,
  ): String? {
    val state = scope.snapshot?.takeIf { active(scope) } ?: return null
    val binding = scope.binding ?: return null
    val targets = AcceptanceCriterionInteractionTargets.pending(state, binding)
    val parsed = AcceptanceCriterionInteractionTraceParser.parse(prose, targets, state.interactionTrace.pendingRechecks)
    val key = scopeKey(state)
    if (parsed !is AcceptanceCriterionInteractionTraceParse.Malformed || scope.traceReaskScope == key) return null
    scope.recordTraceReask(key)
    return rejection(parsed, state)
  }

  internal fun recheckUpdate(
    previous: AcceptanceCriterionRepairState?,
    raw: AcceptanceCriterionRepairState?,
    changedPaths: List<String>,
  ): AcceptanceCriterionRecheckUpdate? {
    if (changedPaths.isEmpty()) return null
    if (previous == null || raw == null || raw == previous) return null
    return previous.recheckUpdateAfter(changedPaths, UnitTestPathClassifier::isUnitTest)
  }

  internal fun scheduleRechecks(
    raw: AcceptanceCriterionRepairState?,
    update: AcceptanceCriterionRecheckUpdate?,
    binding: AcceptanceCriterionRepairBinding,
  ): AcceptanceCriterionRepairState? {
    val scheduled = update?.let { raw?.withRecheckUpdate(it) } ?: raw
    return scheduled?.let { AcceptanceCriterionInteractionTargets.prepare(it, binding) }
  }

  internal fun annotateRechecks(
    result: AcceptanceCriterionRepairWriteResult,
    update: AcceptanceCriterionRecheckUpdate?,
  ): AcceptanceCriterionRepairWriteResult =
    if (result is AcceptanceCriterionRepairWriteResult.Committed && !result.replayed && update != null) {
      result.copy(newAnyChangeRechecks = update.newAnyChangeRefs, droppedRechecks = update.droppedRefs)
    } else {
      result
    }

  public fun evidence(
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
        Keys.REGRESSED_REFS to settlement.reopenedRefs,
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
        Keys.RECHECKS to
          settlement.rechecks.map {
            mapOf(
              Keys.CRITERION_REF to it.criterionRef,
              Keys.VERDICT to it.verdict.wireValue,
              Keys.EVIDENCE_REF to it.evidenceRef,
            )
          },
      ),
    )

  public fun criterionFact(criterion: AcceptanceCriterionRepairCriterion): Map<String, Any?> =
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
