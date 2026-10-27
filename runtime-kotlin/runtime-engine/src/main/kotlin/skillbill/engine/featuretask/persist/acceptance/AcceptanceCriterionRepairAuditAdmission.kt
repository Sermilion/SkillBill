package skillbill.engine.featuretask.persist.acceptance

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairAuditEvidence
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairMutation
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.workflow.taskruntime.artifact.asRepairWireMap
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionAuditResultMode
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionTraceSettlement
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

@Inject
class AcceptanceCriterionRepairAuditAdmission(private val validator: FeatureTaskRuntimeWireArtifactValidator) {
  internal fun matches(
    next: AcceptanceCriterionRepairState,
    mutation: AcceptanceCriterionRepairMutation,
    evidence: AcceptanceCriterionRepairAuditEvidence?,
  ): Boolean =
    if (evidence == null) {
      !requiresEvidence(mutation)
    } else {
      acceptsEvidence(mutation) && evidenceMatches(next, mutation, evidence.payload)
    }

  private fun evidenceMatches(
    next: AcceptanceCriterionRepairState,
    mutation: AcceptanceCriterionRepairMutation,
    wire: FeatureTaskRuntimeWorkflowArtifactMap,
  ): Boolean {
    val kind = FeatureTaskRuntimeWireArtifactKind.ACCEPTANCE_CRITERION_AUDIT_RESULT
    if (validator.violation(kind, wire, "serial-audit-admission") != null) return false
    val identity =
      wire[Keys.CATALOG_ID] == next.catalogId && wire[Keys.SPEC_DIGEST] == next.specDigest &&
        wire[Keys.EXECUTION_ID] == next.executionId
    if (!identity || wire[Keys.DEPENDENCIES] != next.asRepairWireMap()[Keys.DEPENDENCIES]) return false
    return when (mutation) {
      is AcceptanceCriterionRepairMutation.Discover -> discoveryMatches(wire, next)
      is AcceptanceCriterionRepairMutation.Outcome -> targetedMatches(wire, next, mutation)
      is AcceptanceCriterionRepairMutation.InteractionTrace -> interactionTraceMatches(wire, next, mutation)
      else -> false
    }
  }

  private fun requiresEvidence(mutation: AcceptanceCriterionRepairMutation): Boolean =
    mutation is AcceptanceCriterionRepairMutation.InteractionTrace

  private fun acceptsEvidence(mutation: AcceptanceCriterionRepairMutation): Boolean =
    requiresEvidence(mutation) || mutation is AcceptanceCriterionRepairMutation.Discover ||
      mutation is AcceptanceCriterionRepairMutation.Outcome && mutation.outcome.kind in
      setOf(
        AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS,
        AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE,
      )

  private fun discoveryMatches(
    wire: FeatureTaskRuntimeWorkflowArtifactMap,
    next: AcceptanceCriterionRepairState,
  ): Boolean {
    val noReservation =
      wire[Keys.EXPECTED_REVISION] == null && wire[Keys.CURRENT_CRITERION_REF] == null &&
        wire[Keys.RESERVATION_ID] == null
    val facts = facts(wire)
    val catalog =
      facts.size == next.criteria.size &&
        facts.all { fact ->
          val criterion = next.criteria.singleOrNull { it.canonicalRef == fact[Keys.CANONICAL_REF] }
          criterion != null && criterionMatches(fact, criterion) && fact[Keys.EVIDENCE_REF] == criterion.evidenceRef
        }
    return wire[Keys.MODE] == AcceptanceCriterionRepairMode.INITIAL_DISCOVERY.wireValue && noReservation && catalog &&
      noGraphChanges(
        wire,
      )
  }

  private fun targetedMatches(
    wire: FeatureTaskRuntimeWorkflowArtifactMap,
    next: AcceptanceCriterionRepairState,
    mutation: AcceptanceCriterionRepairMutation.Outcome,
  ): Boolean {
    val outcome = next.outcomes.single { it.reservationId == mutation.outcome.reservationId }
    val criterion = next.criteria.single { it.canonicalRef == outcome.criterionRef }
    val fact = facts(wire).singleOrNull() ?: return false
    val reservation =
      wire[Keys.CURRENT_CRITERION_REF] == outcome.criterionRef && wire[Keys.RESERVATION_ID] == outcome.reservationId &&
        (wire[Keys.EXPECTED_REVISION] as? Number)?.toLong() == outcome.verifiedRevision
    val output =
      wire[Keys.PRODUCING_OUTPUT_DIGEST] == outcome.digest &&
        wire[Keys.PRODUCING_OUTPUT_ID] == outcome.outcomeId
    val source = wire[Keys.TREE_DIGEST] == outcome.treeDigest && wire[Keys.CHECKPOINT_ID] == outcome.checkpointId
    val criterionFact =
      fact[Keys.CANONICAL_REF] == outcome.criterionRef && fact[Keys.EVIDENCE_REF] == outcome.evidenceRef &&
        criterionMatches(
          fact,
          criterion,
        )
    val bound = reservation && output && source && criterionFact
    return wire[Keys.MODE] == AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED.wireValue && bound &&
      noGraphChanges(
        wire,
      )
  }

  private fun interactionTraceMatches(
    wire: FeatureTaskRuntimeWorkflowArtifactMap,
    next: AcceptanceCriterionRepairState,
    mutation: AcceptanceCriterionRepairMutation.InteractionTrace,
  ): Boolean {
    val settlement = mutation.settlement
    val unbound = wire[Keys.EXPECTED_REVISION] == null && wire[Keys.RESERVATION_ID] == null
    val output =
      wire[Keys.PRODUCING_OUTPUT_ID] == settlement.outputId &&
        wire[Keys.PRODUCING_OUTPUT_DIGEST] == settlement.outputDigest
    val source = wire[Keys.TREE_DIGEST] == settlement.treeDigest && wire[Keys.CHECKPOINT_ID] == settlement.checkpointId
    val regressed = (wire[Keys.REGRESSED_REFS] as List<*>).toSet() == settlement.reopenedRefs.toSet()
    val noEdges = (wire[Keys.EDGE_CHANGES] as List<*>).isEmpty()
    val traced = wire[Keys.MODE] == AcceptanceCriterionAuditResultMode.INTERACTION_TRACE.wireValue
    return traced && unbound && output && source && regressed && noEdges &&
      interactionsMatch(wire, settlement) && recheckEntriesMatch(wire, settlement) && criteriaMatch(wire, next)
  }

  private fun recheckEntriesMatch(
    wire: FeatureTaskRuntimeWorkflowArtifactMap,
    settlement: AcceptanceCriterionInteractionTraceSettlement,
  ): Boolean {
    val rechecks = (wire[Keys.RECHECKS] as List<*>).map { FeatureTaskRuntimeWorkflowArtifactMap.from(it) }
    return rechecks.size == settlement.rechecks.size &&
      settlement.rechecks.all { entry ->
        rechecks.any {
          it[Keys.CRITERION_REF] == entry.criterionRef && it[Keys.VERDICT] == entry.verdict.wireValue &&
            it[Keys.EVIDENCE_REF] == entry.evidenceRef
        }
      }
  }

  private fun interactionsMatch(
    wire: FeatureTaskRuntimeWorkflowArtifactMap,
    settlement: AcceptanceCriterionInteractionTraceSettlement,
  ): Boolean {
    val interactions = (wire[Keys.INTERACTIONS] as List<*>).map { FeatureTaskRuntimeWorkflowArtifactMap.from(it) }
    return interactions.size == settlement.entries.size &&
      settlement.entries.all { entry ->
        interactions.any {
          it[Keys.SCENARIO_REF] == entry.scenarioRef && it[Keys.VERDICT] == entry.verdict.wireValue &&
            it[Keys.OWNER_REF] == entry.ownerRef && it[Keys.TRACE_REF] == entry.traceRef
        }
      }
  }

  private fun criteriaMatch(
    wire: FeatureTaskRuntimeWorkflowArtifactMap,
    next: AcceptanceCriterionRepairState,
  ): Boolean {
    val facts = facts(wire)
    return facts.size == next.criteria.size &&
      facts.all { fact ->
        val criterion = next.criteria.singleOrNull { it.canonicalRef == fact[Keys.CANONICAL_REF] }
        criterion != null && criterionMatches(fact, criterion)
      }
  }

  private fun noGraphChanges(wire: FeatureTaskRuntimeWorkflowArtifactMap): Boolean =
    (wire[Keys.EDGE_CHANGES] as List<*>).isEmpty() && (wire[Keys.REGRESSED_REFS] as List<*>).isEmpty()

  private fun criterionMatches(
    fact: FeatureTaskRuntimeWorkflowArtifactMap,
    criterion: AcceptanceCriterionRepairCriterion,
  ): Boolean =
    fact[Keys.SOURCE_DISPOSITION] == criterion.sourceDisposition.wireValue &&
      fact[Keys.VALIDATION_OBLIGATION] == criterion.validationObligation.wireValue &&
      fact[Keys.VALIDATION_OWNER_AVAILABLE] == criterion.validationOwnerAvailable

  private fun facts(wire: FeatureTaskRuntimeWorkflowArtifactMap): List<FeatureTaskRuntimeWorkflowArtifactMap> =
    (wire[Keys.CRITERIA] as List<*>).map { FeatureTaskRuntimeWorkflowArtifactMap.from(it) }
}
