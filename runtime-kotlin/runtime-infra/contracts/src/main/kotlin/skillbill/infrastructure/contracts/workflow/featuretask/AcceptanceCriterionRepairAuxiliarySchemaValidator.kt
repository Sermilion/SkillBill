package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.ValidationMessage
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as PlanKeys
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimePhasePayloadKeys as PhaseKeys
import skillbill.ports.taskruntime.FeatureTaskRuntimeExecutionPlanValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputMigration
import skillbill.ports.taskruntime.model.FeatureTaskRuntimePhaseOutputMigrationResult
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_ADOPTION_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_HANDOFF_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.invalidAcceptanceCriterionAuditResultSchema
import skillbill.error.shellcontent.invalidAcceptanceCriterionRepairAdoptionSchema
import skillbill.error.shellcontent.invalidAcceptanceCriterionRepairHandoffSchema
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.AcceptanceCriterionAuditResultSchemaPaths
import skillbill.infrastructure.contracts.locator.AcceptanceCriterionRepairAdoptionSchemaPaths
import skillbill.infrastructure.contracts.locator.AcceptanceCriterionRepairHandoffSchemaPaths
import skillbill.infrastructure.contracts.review.MAX_REPORTED_VIOLATIONS
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdoptionComposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.model.WorkflowStepStatus
import java.security.MessageDigest
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

object AcceptanceCriterionRepairAuxiliarySchemaValidator {
  fun validateAuditResult(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ) = validate(
    payload,
    sourceLabel,
    SerialRepairSchema(
      AcceptanceCriterionAuditResultSchemaPaths.CLASSPATH_RESOURCE,
      AcceptanceCriterionAuditResultSchemaPaths.EXPECTED_SCHEMA_ID,
      ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION,
      FeatureTaskRuntimeHandoffProjectionBudget.PLANNING_PROJECTION.maxUtf8Bytes,
      ::invalidAcceptanceCriterionAuditResultSchema,
      ::auditResultCoherenceViolation,
    ),
  )

  fun validateRepairHandoff(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ) = validate(
    payload,
    sourceLabel,
    SerialRepairSchema(
      AcceptanceCriterionRepairHandoffSchemaPaths.CLASSPATH_RESOURCE,
      AcceptanceCriterionRepairHandoffSchemaPaths.EXPECTED_SCHEMA_ID,
      ACCEPTANCE_CRITERION_REPAIR_HANDOFF_CONTRACT_VERSION,
      FeatureTaskRuntimeHandoffProjectionBudget.PHASE_RECEIPT.maxUtf8Bytes,
      ::invalidAcceptanceCriterionRepairHandoffSchema,
      ::repairHandoffCoherenceViolation,
    ),
  )

  fun validateRepairAdoption(
    payload: Map<String, Any?>,
    sourceLabel: String,
    executionPlans: FeatureTaskRuntimeExecutionPlanValidator,
    phaseOutputs: FeatureTaskRuntimePhaseOutputMigration,
  ) = validate(
    payload,
    sourceLabel,
    SerialRepairSchema(
      AcceptanceCriterionRepairAdoptionSchemaPaths.CLASSPATH_RESOURCE,
      AcceptanceCriterionRepairAdoptionSchemaPaths.EXPECTED_SCHEMA_ID,
      ACCEPTANCE_CRITERION_REPAIR_ADOPTION_CONTRACT_VERSION,
      AcceptanceCriterionRepairState.MAX_DURABLE_UTF8_BYTES,
      ::invalidAcceptanceCriterionRepairAdoptionSchema,
      { value -> repairAdoptionCoherenceViolation(value, executionPlans, phaseOutputs) },
    ),
  )

  private data class SerialRepairSchema(
    val resource: String,
    val schemaId: String,
    val version: String,
    val maximumBytes: Int,
    val failure: (String, String, Throwable?) -> SkillBillRuntimeException,
    val coherenceViolation: (Map<String, Any?>) -> String?,
  )

  private fun validate(
    payload: Map<String, Any?>,
    sourceLabel: String,
    contract: SerialRepairSchema,
  ) = with(contract) {
    if (JsonCodec.valueToJsonString(payload).toByteArray(Charsets.UTF_8).size > maximumBytes) {
      throw failure(sourceLabel, "Serial artifact exceeds its UTF-8 capacity.", null)
    }
    val schema =
      ClasspathContractSchemaLoader.compiledSchema(
        CompiledSchemaRequest(
          cacheKey = resource,
          classLoader = AcceptanceCriterionRepairAuxiliarySchemaValidator::class.java.classLoader,
          classpathResource = resource,
          missingResource = { failure(resource, "Canonical schema resource is missing.", null) },
          processingFailure = { cause -> failure(resource, cause.message.orEmpty(), cause) },
          loadFailureLogger = {},
          expectedSchemaId = schemaId,
          expectedContractVersion = version,
          identityFailure = { reason -> failure(resource, reason, null) },
        ),
      )
    val instance: JsonNode = ClasspathContractSchemaLoader.valueToTree(payload)
    val errors: Set<ValidationMessage> = ClasspathContractSchemaLoader.validate(schema, instance)
    val reason = if (errors.isNotEmpty()) formatReason(errors) else coherenceViolation(payload)
    reason?.let { throw failure(sourceLabel, it, null) }
  }

  private fun auditResultCoherenceViolation(payload: Map<String, Any?>): String? {
    val criteria =
      (payload[Keys.CRITERIA] as? List<*>)?.map {
        it as? Map<*, *>
          ?: return "criteria must contain objects."
      }
        ?: return "criteria must be an array."
    val refs = criteria.mapNotNull { it[Keys.CANONICAL_REF] as? String }
    if (refs.size != refs.toSet().size) return "audit result contains duplicate criteria."
    return when (payload[Keys.MODE]) {
      AcceptanceCriterionRepairMode.INITIAL_DISCOVERY.wireValue -> null
      AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED.wireValue -> {
        val current = payload[Keys.CURRENT_CRITERION_REF]
        val identity = criteria.size == 1 && refs.singleOrNull() == current
        val reservation = payload[Keys.RESERVATION_ID] is String && payload[Keys.EXPECTED_REVISION] is Number
        if (!identity || !reservation) {
          "targeted audit result does not bind exactly one current criterion and reservation."
        } else {
          null
        }
      }
      AcceptanceCriterionRepairMode.FINAL_FULL.wireValue -> {
        val production =
          criteria.filter {
            it[Keys.SOURCE_DISPOSITION] != AcceptanceCriterionSourceDisposition.NOT_APPLICABLE.wireValue
          }
        if (production.size != refs.size) "final-full audit contains a non-production criterion." else null
      }
      else -> "audit result mode is unsupported."
    }
  }

  private fun repairHandoffCoherenceViolation(payload: Map<String, Any?>): String? {
    val used = (payload[Keys.UNSUCCESSFUL_USED] as Number).toInt()
    val remaining = (payload[Keys.UNSUCCESSFUL_REMAINING] as Number).toInt()
    val position = (payload[Keys.QUEUE_POSITION] as Number).toInt()
    val size = (payload[Keys.QUEUE_SIZE] as Number).toInt()
    if (used + remaining != AcceptanceCriterionRepairState.MAX_UNSUCCESSFUL_REPAIR_OUTCOMES) {
      return "handoff unsuccessful budget does not total three."
    }
    if (position > size) return "handoff queue position exceeds queue size."
    if (payload[Keys.STAGE] ==
      AcceptanceCriterionRepairStage
        .REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION.wireValue &&
      payload[Keys.RESERVATION_ID] !is String
    ) {
      return "awaiting-verification handoff lacks its live reservation."
    }
    return null
  }

  private fun repairAdoptionCoherenceViolation(
    payload: Map<String, Any?>,
    executionPlans: FeatureTaskRuntimeExecutionPlanValidator,
    phaseOutputs: FeatureTaskRuntimePhaseOutputMigration,
  ): String? {
    val original = FeatureTaskRuntimeWorkflowArtifactMap.from(payload[Keys.ORIGINAL_ARTIFACTS])
    if (!original.isObject) return "adoption lacks its original artifacts."
    val descriptor = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(original)
    val serial =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR.contains(original) ||
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR_ADOPTION.contains(original)
    if (digest(original) != payload[Keys.PRISTINE_HISTORY_DIGEST]) return "adoption original history digest changed."
    if (descriptor == null || digest(descriptor) != payload[Keys.DESCRIPTOR_DIGEST]) {
      return "adoption original descriptor is missing or changed."
    }
    if (serial) return "adoption original history contains serial state."
    val descriptorBytes = JsonCodec.valueToJsonString(descriptor).toByteArray(Charsets.UTF_8)
    val canonicalBytes = try {
      executionPlans.canonicalize(descriptorBytes, sourceLabel = "acceptance-criterion-repair-adoption")
    } catch (_: SkillBillRuntimeException) {
      return "adoption original descriptor is invalid."
    }
    val canonical =
      FeatureTaskRuntimeWorkflowArtifactMap.from(JsonCodec.parseValue(canonicalBytes.toString(Charsets.UTF_8)))
    val policies = canonical[PlanKeys.STEP_POLICIES] as? List<*> ?: return "adoption descriptor policies are missing."
    val policyMap = linkedMapOf<String, String>()
    for (entry in policies) {
      val row = entry as? Map<*, *> ?: return "adoption descriptor policy entry is invalid."
      val step = row[PlanKeys.STEP] as? String ?: return "adoption descriptor policy step is invalid."
      val identity = row[PlanKeys.IDENTITY] as? String ?: return "adoption descriptor policy identity is invalid."
      if (policyMap.put(step, identity) != null) return "adoption descriptor has duplicate policy steps."
    }
    if (digest(policyMap) != payload[Keys.POLICY_DIGEST]) return "adoption policy digest changed."
    if (digest(canonicalBytes.toString(Charsets.UTF_8)) != payload[Keys.SKELETON_DIGEST]) {
      return "adoption skeleton digest changed."
    }
    val definition = canonical[PlanKeys.DEFINITION] as? Map<*, *> ?: return "adoption descriptor definition is missing."
    val definitionId = definition[PlanKeys.ID] as? String ?: return "adoption descriptor definition is invalid."
    val gate = canonical[PlanKeys.QUALITY_GATE_SELECTION] as? String
    val composition = when {
      definitionId == "standalone" && gate == null -> AcceptanceCriterionRepairAdoptionComposition.STANDALONE.wireValue
      definitionId == "goal-child" && gate == FeatureTaskRuntimeQualityGateSelection.BUILD.wireValue ->
        AcceptanceCriterionRepairAdoptionComposition.GOAL_CHILD_BUILD.wireValue
      definitionId == "goal-child" && gate == FeatureTaskRuntimeQualityGateSelection.VALIDATE.wireValue ->
        AcceptanceCriterionRepairAdoptionComposition.GOAL_CHILD_VALIDATE.wireValue
      else -> return "adoption descriptor composition is unsupported."
    }
    val strategies = canonical[PlanKeys.SELECTED_STRATEGIES] as? List<*>
      ?: return "adoption descriptor strategies are missing."
    val audits = strategies.mapNotNull { it as? Map<*, *> }.filter { it[PlanKeys.SLOT] == PhaseSlot.AUDIT.wireValue }
    val revision = audits.singleOrNull()?.get(PlanKeys.SEMANTIC_REVISION) as? Number
      ?: return "adoption descriptor audit revision is invalid."
    if (payload[Keys.COMPOSITION] != composition ||
      (payload[Keys.SEMANTIC_REVISION] as? Number)?.toInt() != revision.toInt()
    ) {
      return "adoption composition or semantic revision disagrees with its descriptor."
    }
    val versions = payload[Keys.DECLARED_CONTRACT_VERSIONS] as? Map<*, *>
      ?: return "adoption declared contract versions are missing."
    if (versions[Keys.CONTRACT_VERSION] != canonical[PlanKeys.CONTRACT_VERSION]) {
      return "adoption execution-plan contract version changed."
    }
    val phaseOutput = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.value(original)
      as? Map<*, *> ?: return "adoption discovery phase records are missing."
    val entryStep = audits.single()[PlanKeys.ENTRY_STEP] as? String
      ?: return "adoption discovery entry step is invalid."
    val discovery = phaseOutput[entryStep] as? Map<*, *> ?: return "adoption discovery record is missing."
    if (discovery[SharedPayloadKeys.PHASE_ID] != entryStep ||
      discovery[SharedPayloadKeys.STATUS] != WorkflowStepStatus.COMPLETED.wireValue ||
      (discovery[PhaseKeys.ATTEMPT_COUNT] as? Number)?.toInt() != 1 ||
      (discovery[PhaseKeys.FINISHED_AT] as? String).isNullOrBlank()
    ) return "adoption discovery record is not a single completed audit entry."
    val output = discovery[SharedPayloadKeys.OUTPUT_ARTIFACT] as? String
      ?: return "adoption discovery output is missing."
    val sourceVersion = when (val migrated = phaseOutputs.migrate(output)) {
      is FeatureTaskRuntimePhaseOutputMigrationResult.Current -> {
        val source = JsonCodec.parseValue(migrated.payload) as? Map<*, *>
          ?: return "adoption discovery output is invalid."
        source[SharedPayloadKeys.CONTRACT_VERSION] as? String
      }
      is FeatureTaskRuntimePhaseOutputMigrationResult.Migrated -> migrated.sourceVersion
      is FeatureTaskRuntimePhaseOutputMigrationResult.Refused -> null
    } ?: return "adoption discovery output version is unsupported."
    if (versions[Keys.PHASE_OUTPUT_CONTRACT_VERSION] != sourceVersion) {
      return "adoption phase-output contract version changed."
    }
    return null
  }

  private fun digest(value: Any?): String =
    MessageDigest.getInstance("SHA-256")
      .digest(JsonCodec.valueToJsonString(value).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

  private fun formatReason(errors: Set<ValidationMessage>): String =
    errors.sortedBy { it.instanceLocation?.toString().orEmpty() }
      .take(MAX_REPORTED_VIOLATIONS)
      .joinToString(" | ") { error ->
        "${error.instanceLocation?.toString()?.ifBlank { "<root>" } ?: "<root>"}: ${error.message.orEmpty()}"
      } + if (errors.size > MAX_REPORTED_VIOLATIONS) " (+${errors.size - MAX_REPORTED_VIOLATIONS} more)" else ""
}
