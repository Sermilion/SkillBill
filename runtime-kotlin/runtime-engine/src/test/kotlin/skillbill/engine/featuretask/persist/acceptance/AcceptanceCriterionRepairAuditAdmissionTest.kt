package skillbill.engine.featuretask.persist.acceptance

import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairAuditEvidence
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairMutation
import skillbill.engine.featuretask.testing.featureTaskRuntimeWireArtifactValidatorForTest
import skillbill.error.core.SkillBillRuntimeException
import skillbill.workflow.taskruntime.artifact.asRepairWireMap
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProseAudit
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionEntry
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionTarget
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionTraceSettlement
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionVerdict
import skillbill.workflow.taskruntime.model.audit.interaction.recordInteractionTrace
import skillbill.workflow.taskruntime.model.audit.interaction.requireInteractionTrace
import skillbill.workflow.taskruntime.model.audit.recordProseAudit
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

class AcceptanceCriterionRepairAuditAdmissionTest {
  private val admission = AcceptanceCriterionRepairAuditAdmission(featureTaskRuntimeWireArtifactValidatorForTest())
  private val scenario = AcceptanceCriterionInteractionTarget("IS-001", listOf("AC-001", "AC-002"))

  @Test
  fun `trace admission takes regressed refs and refuses edge changes while discovery refuses regressed refs`() {
    val accepted = acceptedState()
    val brokenSettlement =
      AcceptanceCriterionInteractionTraceSettlement(
        "trace:1",
        "e".repeat(64),
        "f".repeat(64),
        "checkpoint:1",
        listOf(
          AcceptanceCriterionInteractionEntry(
            "IS-001",
            AcceptanceCriterionInteractionVerdict.BROKEN,
            "AC-002",
            "src/Flow.kt:10",
          ),
        ),
      )
    val required = accepted.requireInteractionTrace(listOf(scenario))
    val reopened = assertNotNull(required.recordInteractionTrace(brokenSettlement))
    val trace = AcceptanceCriterionRepairMutation.InteractionTrace(brokenSettlement)
    val traceWire = AcceptanceCriterionInteractionTraceRound.evidence(brokenSettlement, reopened)

    assertTrue(admission.matches(reopened, trace, AcceptanceCriterionRepairAuditEvidence(traceWire)))
    val edge =
      mapOf(
        Keys.DEPENDENT_REF to "AC-002",
        Keys.PREREQUISITE_REF to "AC-001",
        Keys.REASON to "AC-002 reads the AC-001 flow.",
        Keys.DECLARING_OUTPUT_REF to "trace:1",
        Keys.SOURCE_EVIDENCE_REF to "src/Flow.kt:10",
      )
    val withEdges = traceWire.with(Keys.EDGE_CHANGES to listOf(edge))
    assertFailsWith<SkillBillRuntimeException> {
      admission.matches(reopened, trace, AcceptanceCriterionRepairAuditEvidence(withEdges))
    }

    val discovery = AcceptanceCriterionRepairMutation.Discover(admissionOf(accepted))
    val discoveryWire = discoveryEvidence(accepted)
    assertTrue(admission.matches(accepted, discovery, AcceptanceCriterionRepairAuditEvidence(discoveryWire)))
    val withRegressed = discoveryWire.with(Keys.REGRESSED_REFS to listOf("AC-002"))
    assertFalse(admission.matches(accepted, discovery, AcceptanceCriterionRepairAuditEvidence(withRegressed)))
  }

  private fun discoveryEvidence(state: AcceptanceCriterionRepairState): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(
      mapOf(
        Keys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION,
        Keys.MODE to AcceptanceCriterionRepairMode.INITIAL_DISCOVERY.wireValue,
        Keys.CATALOG_ID to state.catalogId,
        Keys.SPEC_DIGEST to state.specDigest,
        Keys.EXECUTION_ID to state.executionId,
        Keys.TREE_DIGEST to "d".repeat(64),
        Keys.CHECKPOINT_ID to "checkpoint:discovery",
        Keys.PRODUCING_OUTPUT_ID to "discovery",
        Keys.PRODUCING_OUTPUT_DIGEST to "e".repeat(64),
        Keys.EXPECTED_REVISION to null,
        Keys.CURRENT_CRITERION_REF to null,
        Keys.RESERVATION_ID to null,
        Keys.DEPENDENCIES to state.asRepairWireMap()[Keys.DEPENDENCIES],
        Keys.REGRESSED_REFS to emptyList<Any>(),
        Keys.EDGE_CHANGES to emptyList<Any>(),
        Keys.INTERACTIONS to emptyList<Any>(),
        Keys.RECHECKS to emptyList<Any>(),
        Keys.CRITERIA to
          state.criteria.map {
            mapOf(
              Keys.CANONICAL_REF to it.canonicalRef,
              Keys.SOURCE_DISPOSITION to it.sourceDisposition.wireValue,
              Keys.VALIDATION_OBLIGATION to it.validationObligation.wireValue,
              Keys.VALIDATION_OWNER_AVAILABLE to it.validationOwnerAvailable,
              Keys.EVIDENCE_REF to it.evidenceRef,
            )
          },
      ),
    )

  private fun FeatureTaskRuntimeWorkflowArtifactMap.with(vararg changes: Pair<String, Any?>) =
    FeatureTaskRuntimeWorkflowArtifactMap.from(toMap() + changes)

  private fun admissionOf(state: AcceptanceCriterionRepairState) =
    AcceptanceCriterionRepairAdmission(
      state.catalogId,
      state.executionId,
      state.specDigest,
      state.criteria.map { it.originalText },
      state.criteria,
      emptyList(),
    )

  private fun acceptedState(): AcceptanceCriterionRepairState {
    val initial =
      assertNotNull(
        AcceptanceCriterionRepairState.admit(
          AcceptanceCriterionRepairAdmission(
            "catalog",
            "execution",
            "a".repeat(64),
            listOf("first", "second"),
            listOf(criterion("AC-001", 1, "first"), criterion("AC-002", 2, "second")),
            emptyList(),
          ),
        ),
      )
    val discovery =
      AcceptanceCriterionProseAudit(
        "full:1",
        initial.revision + 1,
        null,
        false,
        "b".repeat(64),
        "AC-001 and AC-002 need a focused audit.",
        "c".repeat(64),
        "source:1",
      )
    var state = assertNotNull(initial.recordProseAudit(discovery))
    listOf("AC-001", "AC-002").forEach { ref ->
      state =
        assertNotNull(
          state.recordProseAudit(
            discovery.copy(
              id = "focused:$ref",
              revision = state.revision + 1,
              criterionRef = ref,
              satisfied = true,
              prose = "$ref met at src/Flow.kt:10",
            ),
          ),
        )
    }
    return state
  }

  private fun criterion(
    ref: String,
    ordinal: Int,
    text: String,
  ) = AcceptanceCriterionRepairCriterion(
    canonicalRef = ref,
    originalAlias = null,
    originalText = text,
    ordinal = ordinal,
    sourceDisposition = AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
    validationObligation = AcceptanceCriterionValidationObligation.REQUIRED,
    validationOwnerAvailable = true,
    evidenceRef = "audit:$ref",
  )
}
