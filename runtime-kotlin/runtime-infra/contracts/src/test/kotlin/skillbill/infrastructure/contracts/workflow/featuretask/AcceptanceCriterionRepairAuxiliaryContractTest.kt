package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_AUDIT_RESULT_INTERACTION_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_ADOPTION_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_HANDOFF_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.infrastructure.contracts.locator.AcceptanceCriterionAuditResultSchemaPaths
import skillbill.infrastructure.contracts.locator.AcceptanceCriterionRepairAdoptionSchemaPaths
import skillbill.infrastructure.contracts.locator.AcceptanceCriterionRepairHandoffSchemaPaths
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputMigration
import skillbill.ports.taskruntime.executionplan.FeatureTaskRuntimeExecutionPlanValidator
import skillbill.ports.taskruntime.model.FeatureTaskRuntimePhaseOutputMigrationResult
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.upgradeAuditResultFromPrevious
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionCompletedMutation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionMutationDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProgressAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProgressContext
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionResolvedObligation
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionProgressPayloadKeys as ProgressKeys
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimePhasePayloadKeys as PhaseKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as PlanKeys

class AcceptanceCriterionRepairAuxiliaryContractTest {
  @Test
  fun `all auxiliary artifacts package strict versioned canonical schemas`() {
    listOf(
      Triple(
        AcceptanceCriterionAuditResultSchemaPaths.CLASSPATH_RESOURCE,
        AcceptanceCriterionAuditResultSchemaPaths.EXPECTED_SCHEMA_ID,
        ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION,
      ),
      Triple(
        AcceptanceCriterionRepairHandoffSchemaPaths.CLASSPATH_RESOURCE,
        AcceptanceCriterionRepairHandoffSchemaPaths.EXPECTED_SCHEMA_ID,
        ACCEPTANCE_CRITERION_REPAIR_HANDOFF_CONTRACT_VERSION,
      ),
      Triple(
        AcceptanceCriterionRepairAdoptionSchemaPaths.CLASSPATH_RESOURCE,
        AcceptanceCriterionRepairAdoptionSchemaPaths.EXPECTED_SCHEMA_ID,
        ACCEPTANCE_CRITERION_REPAIR_ADOPTION_CONTRACT_VERSION,
      ),
    ).forEach { (resource, id, version) ->
      val stream = assertNotNull(javaClass.classLoader.getResourceAsStream(resource))
      val schema = stream.use { YAMLMapper().readTree(it) }
      assertEquals(id, schema.path("\$id").asText())
      assertEquals(version, schema.path("properties").path(Keys.CONTRACT_VERSION).path("const").asText())
      assertFalse(schema.path("additionalProperties").asBoolean(true))
    }
  }

  @Test
  fun `targeted audit refuses duplicate identities missing reservations and wrong versions`() {
    val valid = audit()
    AcceptanceCriterionRepairAuxiliarySchemaValidator.validateAuditResult(valid, "valid")
    listOf(
      valid + (Keys.CONTRACT_VERSION to "9.0"),
      valid - ProgressKeys.INDEPENDENT_PROGRESS,
      valid + (Keys.CRITERIA to listOf(criterion() + (Keys.SOURCE_DISPOSITION to "source_closed"))),
      valid - Keys.RESERVATION_ID,
      valid + (Keys.CRITERIA to listOf(criterion(), criterion())),
      valid + (Keys.CURRENT_CRITERION_REF to "AC-002"),
    ).forEach { bad ->
      assertFailsWith<SkillBillRuntimeException> {
        AcceptanceCriterionRepairAuxiliarySchemaValidator.validateAuditResult(bad, "bad")
      }
    }
  }

  @Test
  fun `interaction trace audit binds regressed refs to broken owners and rejects the 0_1 shape`() {
    val valid = traceAudit("broken", "AC-001", listOf("AC-001"))
    AcceptanceCriterionRepairAuxiliarySchemaValidator.validateAuditResult(valid, "valid")
    AcceptanceCriterionRepairAuxiliarySchemaValidator.validateAuditResult(
      traceAudit("holds", null, emptyList()),
      "holds",
    )
    listOf(
      traceAudit("broken", "AC-001", emptyList()),
      traceAudit("holds", null, listOf("AC-001")),
      valid + (Keys.CONTRACT_VERSION to "0.1"),
      valid + (Keys.INTERACTIONS to emptyList<Any>()),
      audit() + (Keys.INTERACTIONS to traceAudit("holds", null, emptyList())[Keys.INTERACTIONS]),
    ).forEach { bad ->
      assertFailsWith<SkillBillRuntimeException> {
        AcceptanceCriterionRepairAuxiliarySchemaValidator.validateAuditResult(bad, "bad")
      }
    }
  }

  @Test
  fun `handoff refuses invented remaining budget and awaiting verification without reservation`() {
    val valid =
      identity() +
        mapOf(
          Keys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_REPAIR_HANDOFF_CONTRACT_VERSION,
          ProgressKeys.EVIDENCE_LIFECYCLE to "completed_awaiting_independent_check",
          ProgressKeys.COMPLETED_MUTATION to completedMutation(),
          Keys.REVISION to 2, Keys.CRITERION_REF to "AC-001",
          Keys
            .STAGE to "repair_completed_awaiting_targeted_verification",
          Keys.RESERVATION_ID to "reservation", Keys.UNSUCCESSFUL_USED to 1, Keys.UNSUCCESSFUL_REMAINING to 2,
          Keys.QUEUE_POSITION to 1, Keys.QUEUE_SIZE to 1, Keys.ACTIONABLE_GAP to "repair missing transaction fence",
          Keys.EVIDENCE_REF to
            "Source evidence for the acceptance criterion. ".repeat(
              32,
            ),
          Keys.DEPENDENCIES to emptyList<Any>(),
          Keys
            .PREREQUISITE_FACTS to emptyList<Any>(),
        )
    AcceptanceCriterionRepairAuxiliarySchemaValidator.validateRepairHandoff(valid, "valid")
    listOf(
      valid + (Keys.CONTRACT_VERSION to "9.0"),
      valid + (Keys.UNSUCCESSFUL_REMAINING to 3),
      valid + (Keys.RESERVATION_ID to null),
    ).forEach { bad ->
      assertFailsWith<SkillBillRuntimeException> {
        AcceptanceCriterionRepairAuxiliarySchemaValidator
          .validateRepairHandoff(bad, "bad")
      }
    }
  }

  @Test
  fun `adoption refuses altered retained history and unsupported historical revisions`() {
    val descriptor =
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        JsonCodec.parseValue(
          assertNotNull(javaClass.getResourceAsStream("/featuretask/serial-adoption/standalone-r3.json"))
            .use { it.readBytes().toString(Charsets.UTF_8) },
        ),
      )
    val phaseOutput = JsonCodec.valueToJsonString(mapOf(Keys.CONTRACT_VERSION to "0.7"))
    val original =
      mapOf(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.entry(descriptor),
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
          mapOf(
            "audit" to
              mapOf(
                SharedPayloadKeys.PHASE_ID to "audit",
                SharedPayloadKeys.STATUS to "completed",
                PhaseKeys.ATTEMPT_COUNT to 1,
                PhaseKeys.FINISHED_AT to "2026-10-15T00:00:00Z",
                SharedPayloadKeys.OUTPUT_ARTIFACT to phaseOutput,
              ),
          ),
        ),
      )
    val canonicalDescriptor = JsonCodec.valueToJsonString(descriptor)
    val policies = assertNotNull(descriptor[PlanKeys.STEP_POLICIES] as? List<*>)
    val policyIdentities =
      policies.associate {
        val policy = FeatureTaskRuntimeWorkflowArtifactMap.from(it)
        policy[PlanKeys.STEP] as String to (policy[PlanKeys.IDENTITY] as String)
      }
    val valid =
      identity() +
        mapOf(
          Keys.COMPOSITION to "standalone", Keys.SEMANTIC_REVISION to 3, Keys.DESCRIPTOR_DIGEST to digest(descriptor),
          Keys.POLICY_DIGEST to
            digest(policyIdentities),
          Keys.SKELETON_DIGEST to digestText(canonicalDescriptor),
          Keys.DECLARED_CONTRACT_VERSIONS to
            mapOf(
              Keys.CONTRACT_VERSION to descriptor[PlanKeys.CONTRACT_VERSION],
              Keys.PHASE_OUTPUT_CONTRACT_VERSION to "0.7",
            ),
          Keys.STOPPED_OWNER_GENERATION to 0,
          Keys.STOPPED_OWNER_EVIDENCE_REF to
            "ownership_absent:proof".repeat(
              32,
            ),
          Keys.PRISTINE_HISTORY_DIGEST to digest(original),
          Keys.RETAINED_EVIDENCE_REFS to listOf("original:proof".repeat(64)), Keys.PROSPECTIVE_BUDGET to true,
          Keys.STRUCTURED_DISCOVERY_REQUIRED to true, Keys.ORIGINAL_ARTIFACTS to original,
        )
    AcceptanceCriterionRepairAuxiliarySchemaValidator.validateRepairAdoption(
      valid,
      "valid",
      executionPlanValidator(),
      phaseOutputMigration(),
    )
    assertRejectedAdoption(valid)
  }

  private fun assertRejectedAdoption(valid: Map<String, Any?>) {
    listOf(
      valid + (Keys.CONTRACT_VERSION to "9.0"),
      valid + (Keys.SEMANTIC_REVISION to 4),
      valid + (Keys.PRISTINE_HISTORY_DIGEST to "f".repeat(64)),
    ).forEach { bad ->
      assertFailsWith<SkillBillRuntimeException> {
        AcceptanceCriterionRepairAuxiliarySchemaValidator
          .validateRepairAdoption(
            bad,
            "bad",
            executionPlanValidator(),
            phaseOutputMigration(),
          )
      }
    }
  }

  @Test
  fun `comparison definitions resolve locally and bind audit and handoff snapshots`() {
    val progress = comparison()
    val valid = audit() + (ProgressKeys.INDEPENDENT_PROGRESS to progress)
    AcceptanceCriterionRepairAuxiliarySchemaValidator.validateAuditResult(valid, "comparison")
    val resolved =
      mapOf(
        ProgressKeys.OBLIGATION_ID to "fence",
        ProgressKeys.OBLIGATION to "Production writes check the transaction fence",
        ProgressKeys.BASELINE_CITATIONS to listOf("before:src/Store.kt:5"),
        ProgressKeys.CURRENT_CITATIONS to listOf("after:src/Store.kt:5"),
      )
    val partial =
      progress +
        mapOf(
          ProgressKeys.RESOLVED_OBLIGATIONS to listOf(resolved),
          ProgressKeys.CLASSIFICATION to "verified_partial_progress",
        )
    AcceptanceCriterionRepairAuxiliarySchemaValidator.validateAuditResult(
      valid + (ProgressKeys.INDEPENDENT_PROGRESS to partial),
      "partial",
    )
    val satisfied =
      partial +
        mapOf(
          ProgressKeys.CLASSIFICATION to "satisfied",
          ProgressKeys.REMAINING_GAPS to emptyList<String>(),
        )
    AcceptanceCriterionRepairAuxiliarySchemaValidator.validateAuditResult(
      valid +
        mapOf(
          ProgressKeys.INDEPENDENT_PROGRESS to satisfied,
          Keys.CRITERIA to listOf(criterion() + (Keys.SOURCE_DISPOSITION to "source_closed")),
        ),
      "satisfied",
    )
    assertFailsWith<SkillBillRuntimeException> {
      val missingCitation =
        partial + (
          ProgressKeys.RESOLVED_OBLIGATIONS to
            listOf(
              resolved - ProgressKeys.BASELINE_CITATIONS,
            )
        )
      AcceptanceCriterionRepairAuxiliarySchemaValidator.validateAuditResult(
        valid + (ProgressKeys.INDEPENDENT_PROGRESS to missingCitation),
        "missing-citation",
      )
    }
    listOf(
      progress - Keys.AUDIT_BINDING_DIGEST,
      progress + (Keys.CRITERION_REF to "AC-002"),
      progress + (ProgressKeys.REPAIR_ATTEMPT_ID to "other"),
      progress + (ProgressKeys.CURRENT_TREE_DIGEST to "0".repeat(64)),
      progress + (ProgressKeys.CURRENT_CHECKPOINT_ID to "stale"),
      progress + (ProgressKeys.VERIFICATION_REVISION to 3),
      progress + (ProgressKeys.PRESERVED_OBLIGATIONS to listOf(mapOf(ProgressKeys.OBLIGATION_ID to "unknown"))),
      progress + (ProgressKeys.VERIFICATION_REVISION to Long.MAX_VALUE.toBigInteger().add(BigInteger.ONE)),
      progress + (ProgressKeys.CLASSIFICATION to "verified_partial_progress"),
    ).forEach { bad ->
      assertFailsWith<SkillBillRuntimeException> {
        AcceptanceCriterionRepairAuxiliarySchemaValidator.validateAuditResult(
          valid + (ProgressKeys.INDEPENDENT_PROGRESS to bad),
          "bad",
        )
      }
    }
  }

  @Test
  fun `retained comparison binds handoff identity snapshot and lifecycle`() {
    val progress = comparison()
    val handoff =
      identity() +
        mapOf(
          Keys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_REPAIR_HANDOFF_CONTRACT_VERSION,
          Keys.REVISION to 2,
          Keys.CRITERION_REF to "AC-001",
          Keys.STAGE to "repair_completed_awaiting_targeted_verification",
          Keys.RESERVATION_ID to "reservation", Keys.UNSUCCESSFUL_USED to 1, Keys.UNSUCCESSFUL_REMAINING to 2,
          Keys.QUEUE_POSITION to 1,
          Keys.QUEUE_SIZE to 1,
          Keys.ACTIONABLE_GAP to "Fence missing",
          Keys.EVIDENCE_REF to "src/Store.kt:5",
          Keys.DEPENDENCIES to emptyList<Any>(),
          Keys.PREREQUISITE_FACTS to emptyList<Any>(),
          ProgressKeys.INDEPENDENT_PROGRESS to progress,
          ProgressKeys.COMPLETED_MUTATION to completedMutation(),
          ProgressKeys.EVIDENCE_LIFECYCLE to "checked_evidence_retained",
        )
    AcceptanceCriterionRepairAuxiliarySchemaValidator.validateRepairHandoff(handoff, "comparison")
    assertFailsWith<SkillBillRuntimeException> {
      AcceptanceCriterionRepairAuxiliarySchemaValidator.validateRepairHandoff(
        handoff + (Keys.CHECKPOINT_ID to "stale"),
        "bad",
      )
    }
  }

  @Test
  fun `historical records remain readable without acquiring comparison authority`() {
    val previous =
      (audit() - ProgressKeys.INDEPENDENT_PROGRESS) +
        (Keys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_AUDIT_RESULT_INTERACTION_CONTRACT_VERSION)
    val oldest =
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        (previous - Keys.INTERACTIONS - Keys.RECHECKS) + (Keys.CONTRACT_VERSION to "0.1"),
      )
    val upgraded = assertNotNull(oldest.upgradeAuditResultFromPrevious())
    assertEquals(previous, upgraded)
    AcceptanceCriterionRepairAuxiliarySchemaValidator.validateAuditResult(upgraded, "upgraded-previous")
    val original = JsonCodec.valueToJsonString(previous)
    AcceptanceCriterionRepairAuxiliarySchemaValidator.validateAuditResult(previous, "previous")
    AcceptanceCriterionRepairAuxiliarySchemaValidator.validateAuditResult(previous, "previous-again")
    assertEquals(original, JsonCodec.valueToJsonString(previous))
    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        AcceptanceCriterionRepairAuxiliarySchemaValidator.validateAuditResult(
          previous + (ProgressKeys.INDEPENDENT_PROGRESS to comparison()),
          "forged-historical-progress",
        )
      }
    assertEquals(FeatureTaskRuntimeFailureCode.INVALID_ACCEPTANCE_CRITERION_AUDIT_RESULT_SCHEMA, failure.code)
    val previousHandoff =
      identity() +
        mapOf(
          Keys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_REPAIR_HANDOFF_PREVIOUS_CONTRACT_VERSION,
          Keys.REVISION to 2, Keys.CRITERION_REF to "AC-001", Keys.STAGE to "discovery_admitted",
          Keys.RESERVATION_ID to null, Keys.UNSUCCESSFUL_USED to 1, Keys.UNSUCCESSFUL_REMAINING to 2,
          Keys.QUEUE_POSITION to 1, Keys.QUEUE_SIZE to 1, Keys.ACTIONABLE_GAP to "Fence missing",
          Keys.EVIDENCE_REF to "src/Store.kt:5", Keys.DEPENDENCIES to emptyList<Any>(),
          Keys.PREREQUISITE_FACTS to emptyList<Any>(),
        )
    AcceptanceCriterionRepairAuxiliarySchemaValidator.validateRepairHandoff(previousHandoff, "previous-handoff")
    assertFailsWith<SkillBillRuntimeException> {
      AcceptanceCriterionRepairAuxiliarySchemaValidator.validateRepairHandoff(
        previousHandoff + (ProgressKeys.INDEPENDENT_PROGRESS to comparison()),
        "forged-historical-handoff",
      )
    }
  }

  @Test
  fun `only runtime captured independent evidence admits production progress`() {
    val resolved =
      AcceptanceCriterionResolvedObligation(
        "fence",
        "Production writes check the transaction fence",
        listOf("before:src/Store.kt:5"),
        listOf("after:src/Store.kt:5"),
      )
    val partial =
      comparison() +
        mapOf(
          ProgressKeys.CLASSIFICATION to "verified_partial_progress",
          ProgressKeys.RESOLVED_OBLIGATIONS to
            listOf(
              mapOf(
                ProgressKeys.OBLIGATION_ID to resolved.obligationId,
                ProgressKeys.OBLIGATION to resolved.obligation,
                ProgressKeys.BASELINE_CITATIONS to resolved.baselineCitations,
                ProgressKeys.CURRENT_CITATIONS to resolved.currentCitations,
              ),
            ),
        )
    val payload = audit() + (ProgressKeys.INDEPENDENT_PROGRESS to partial)
    val context = capturedContext(resolved)
    assertIs<AcceptanceCriterionProgressAdmission.ClarificationRequired>(
      AcceptanceCriterionRepairAuxiliarySchemaValidator.validateAuditResult(
        payload,
        "uncaptured",
      ),
    )
    assertIs<AcceptanceCriterionProgressAdmission.Admitted>(
      AcceptanceCriterionRepairAuxiliarySchemaValidator.validateAuditResult(
        payload,
        "captured",
        context,
      ),
    )
    listOf(
      context.copy(checkerOutputId = "worker-output"),
      context.copy(checkerOutputDigest = "f".repeat(64)),
      context.copy(catalogId = "other-catalog"),
      context.copy(specDigest = "0".repeat(64)),
      context.copy(executionId = "other-execution"),
      context.copy(checkerExecutionId = "worker"),
      context.copy(sourceUnchangedDuringCheck = false),
      context.copy(currentCitations = emptySet()),
      context.copy(baselineTreeDigest = "0".repeat(64)),
    ).forEach { counterfeit ->
      val failure =
        assertFailsWith<SkillBillRuntimeException> {
          AcceptanceCriterionRepairAuxiliarySchemaValidator.validateAuditResult(
            payload,
            "counterfeit",
            counterfeit,
          )
        }
      assertEquals(
        FeatureTaskRuntimeFailureCode.INVALID_ACCEPTANCE_CRITERION_AUDIT_RESULT_SCHEMA,
        failure.code,
      )
    }
  }

  private fun capturedContext(resolved: AcceptanceCriterionResolvedObligation): AcceptanceCriterionProgressContext =
    AcceptanceCriterionProgressContext(
      "catalog",
      "a".repeat(64),
      "execution",
      "AC-001",
      "reservation",
      "worker",
      "f".repeat(64),
      "c".repeat(64),
      "before",
      "d".repeat(64),
      "checkpoint",
      2,
      "e".repeat(64),
      "checker",
      "output:1",
      "b".repeat(64),
      true,
      resolved.baselineCitations.toSet(),
      resolved.currentCitations.toSet(),
      emptyList(),
      listOf(resolved),
      emptyList(),
      AcceptanceCriterionCompletedMutation(
        "catalog", "a".repeat(64), "execution", "AC-001", "reservation", "worker", "worker-output", "f".repeat(64),
        "c".repeat(64), "before", "d".repeat(64), "checkpoint", emptyList(),
        terminalDisposition = AcceptanceCriterionMutationDisposition.COMPLETE,
      ),
    )

  private fun completedMutation(): Map<String, Any?> =
    mapOf(
      Keys.CATALOG_ID to "catalog", Keys.SPEC_DIGEST to "a".repeat(64), Keys.EXECUTION_ID to "execution",
      Keys.CRITERION_REF to "AC-001", ProgressKeys.REPAIR_ATTEMPT_ID to "reservation",
      ProgressKeys.WORKER_EXECUTION_ID to "worker", ProgressKeys.WORKER_OUTPUT_ID to "worker-output",
      ProgressKeys.WORKER_OUTPUT_DIGEST to "f".repeat(64), ProgressKeys.BASELINE_TREE_DIGEST to "c".repeat(64),
      ProgressKeys.TERMINAL_DISPOSITION to AcceptanceCriterionMutationDisposition.COMPLETE.wireValue,
      ProgressKeys.LAUNCH_ATTRIBUTION to null,
      ProgressKeys.BASELINE_CHECKPOINT_ID to "before", ProgressKeys.CURRENT_TREE_DIGEST to "d".repeat(64),
      ProgressKeys.CURRENT_CHECKPOINT_ID to "checkpoint", ProgressKeys.ESTABLISHED_OBLIGATIONS to emptyList<Any>(),
    )

  private fun comparison(): Map<String, Any?> =
    mapOf(
      Keys.CRITERION_REF to "AC-001", ProgressKeys.REPAIR_ATTEMPT_ID to "reservation",
      ProgressKeys.BASELINE_TREE_DIGEST to "c".repeat(64), ProgressKeys.CURRENT_TREE_DIGEST to "d".repeat(64),
      ProgressKeys.BASELINE_CHECKPOINT_ID to "before", ProgressKeys.CURRENT_CHECKPOINT_ID to "checkpoint",
      ProgressKeys.VERIFICATION_REVISION to 2, Keys.AUDIT_BINDING_DIGEST to "e".repeat(64),
      ProgressKeys.RESOLVED_OBLIGATIONS to emptyList<Any>(), ProgressKeys.PRESERVED_OBLIGATIONS to emptyList<Any>(),
      ProgressKeys.REMAINING_GAPS to
        listOf(
          "Production fence is still missing",
        ),
      ProgressKeys.CLASSIFICATION to "no_progress",
    )

  private fun audit(): Map<String, Any?> =
    identity() +
      mapOf(
        Keys.MODE to "current_criterion_targeted", Keys.CURRENT_CRITERION_REF to "AC-001",
        Keys
          .RESERVATION_ID to "reservation",
        Keys.EXPECTED_REVISION to 2, Keys.PRODUCING_OUTPUT_ID to "output:1",
        Keys.PRODUCING_OUTPUT_DIGEST to
          "b".repeat(
            64,
          ),
        Keys.CRITERIA to listOf(criterion()), ProgressKeys.INDEPENDENT_PROGRESS to comparison(),
        Keys.DEPENDENCIES to emptyList<Any>(),
        Keys
          .REGRESSED_REFS to emptyList<Any>(),
        Keys.EDGE_CHANGES to emptyList<Any>(),
        Keys.INTERACTIONS to emptyList<Any>(),
        Keys.RECHECKS to emptyList<Any>(),
        Keys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION,
      )

  private fun traceAudit(
    verdict: String,
    owner: String?,
    regressed: List<String>,
  ): Map<String, Any?> =
    (audit() - ProgressKeys.INDEPENDENT_PROGRESS) +
      mapOf(
        Keys.MODE to "interaction_trace",
        Keys.CURRENT_CRITERION_REF to null,
        Keys.RESERVATION_ID to null,
        Keys.EXPECTED_REVISION to null,
        Keys.REGRESSED_REFS to regressed,
        Keys.INTERACTIONS to
          listOf(
            mapOf(
              Keys.SCENARIO_REF to "IS-001",
              Keys.VERDICT to verdict,
              Keys.OWNER_REF to owner,
              Keys.TRACE_REF to "src/Flow.kt:10 then src/Store.kt:44",
            ),
          ),
      )

  private fun criterion(): Map<String, Any?> =
    mapOf(
      Keys.CANONICAL_REF to "AC-001",
      Keys.SOURCE_DISPOSITION to "source_unresolved",
      Keys
        .VALIDATION_OBLIGATION to "deferred",
      Keys.VALIDATION_OWNER_AVAILABLE to true,
      Keys.EVIDENCE_REF to "Source evidence for the acceptance criterion. ".repeat(32),
    )

  private fun identity(): Map<String, Any?> =
    mapOf(
      Keys.CONTRACT_VERSION to "0.1",
      Keys.CATALOG_ID to "catalog",
      Keys.SPEC_DIGEST to
        "a".repeat(
          64,
        ),
      Keys.EXECUTION_ID to "execution",
      Keys.TREE_DIGEST to "d".repeat(64),
      Keys.CHECKPOINT_ID to "checkpoint",
    )

  private fun digest(value: Any): String =
    MessageDigest.getInstance("SHA-256")
      .digest(JsonCodec.valueToJsonString(value).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

  private fun digestText(value: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

  private fun executionPlanValidator(): FeatureTaskRuntimeExecutionPlanValidator =
    object : FeatureTaskRuntimeExecutionPlanValidator {
      override fun canonicalize(
        encoded: ByteArray,
        sourceLabel: String,
      ): ByteArray = encoded
    }

  private fun phaseOutputMigration(): FeatureTaskRuntimePhaseOutputMigration =
    object : FeatureTaskRuntimePhaseOutputMigration {
      override fun migrate(payload: String): FeatureTaskRuntimePhaseOutputMigrationResult =
        FeatureTaskRuntimePhaseOutputMigrationResult.Current(payload)
    }
}
