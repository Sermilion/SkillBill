package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.ValidationMessage
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
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
import skillbill.ports.taskruntime.FeatureTaskRuntimeExecutionPlanValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputMigration
import skillbill.ports.taskruntime.model.FeatureTaskRuntimePhaseOutputMigrationResult
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdoptionComposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionAuditResultMode
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionVerdict
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionRecheckVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import java.security.MessageDigest
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimePhasePayloadKeys as PhaseKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as PlanKeys

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
    val criteria = (payload[Keys.CRITERIA] as? List<*>).orEmpty()
    val refs = criteria.mapNotNull { (it as? Map<*, *>)?.get(Keys.CANONICAL_REF) as? String }
    val interactions = (payload[Keys.INTERACTIONS] as? List<*>).orEmpty()
    val rechecks = (payload[Keys.RECHECKS] as? List<*>).orEmpty()
    val tracing = payload[Keys.MODE] == AcceptanceCriterionAuditResultMode.INTERACTION_TRACE.wireValue
    return when {
      refs.size != refs.toSet().size -> "audit result contains duplicate criteria."
      !tracing && (interactions.isNotEmpty() || rechecks.isNotEmpty()) ->
        "interactions and rechecks are only allowed in interaction_trace mode."
      else -> modeCoherenceViolation(payload, criteria.size, refs, interactions, rechecks)
    }
  }

  private fun modeCoherenceViolation(
    payload: Map<String, Any?>,
    criteriaCount: Int,
    refs: List<String>,
    interactions: List<*>,
    rechecks: List<*>,
  ): String? =
    when (payload[Keys.MODE]) {
      AcceptanceCriterionRepairMode.INITIAL_DISCOVERY.wireValue -> null
      AcceptanceCriterionAuditResultMode.INTERACTION_TRACE.wireValue ->
        interactionTraceCoherenceViolation(payload, interactions, rechecks)
      AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED.wireValue -> {
        val identity = criteriaCount == 1 && refs.singleOrNull() == payload[Keys.CURRENT_CRITERION_REF]
        val reservation = payload[Keys.RESERVATION_ID] is String && payload[Keys.EXPECTED_REVISION] is Number
        if (!identity || !reservation) {
          "targeted audit result does not bind exactly one current criterion and reservation."
        } else {
          null
        }
      }
      else -> "audit result mode is unsupported."
    }

  private fun interactionTraceCoherenceViolation(
    payload: Map<String, Any?>,
    interactions: List<*>,
    rechecks: List<*>,
  ): String? {
    val broken =
      interactions.mapNotNull { it as? Map<*, *> }
        .filter { it[Keys.VERDICT] == AcceptanceCriterionInteractionVerdict.BROKEN.wireValue }
        .mapNotNull { it[Keys.OWNER_REF] as? String }
    val regressed =
      rechecks.mapNotNull {
        it as? Map<*, *>
      }.filter { it[Keys.VERDICT] == AcceptanceCriterionRecheckVerdict.REGRESSED.wireValue }
        .mapNotNull { it[Keys.CRITERION_REF] as? String }
    val declared = (payload[Keys.REGRESSED_REFS] as? List<*>).orEmpty().toSet()
    val empty = interactions.isEmpty() && rechecks.isEmpty()
    val unbound = declared != (broken + regressed).toSet()
    val edges = (payload[Keys.EDGE_CHANGES] as? List<*>).orEmpty()
    return if (empty || unbound || edges.isNotEmpty()) {
      "interaction trace result does not bind its interactions, regressed refs and empty edge changes."
    } else {
      null
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
    val original = adoptionOriginal(payload) ?: return "adoption original history is invalid."
    val canonical =
      adoptionCanonical(payload, original, executionPlans)
        ?: return "adoption original descriptor is invalid."
    return adoptionPolicyViolation(payload, canonical)
      ?: adoptionCompositionViolation(payload, canonical)
      ?: adoptionVersionViolation(payload, canonical)
      ?: adoptionDiscoveryViolation(payload, original, canonical, phaseOutputs)
  }

  private fun adoptionOriginal(payload: Map<String, Any?>): FeatureTaskRuntimeWorkflowArtifactMap? {
    val original = FeatureTaskRuntimeWorkflowArtifactMap.from(payload[Keys.ORIGINAL_ARTIFACTS])
    val hasSerialState =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR.contains(original) ||
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR_ADOPTION.contains(original)
    return original.takeIf {
      it.isObject && digest(it) == payload[Keys.PRISTINE_HISTORY_DIGEST] && !hasSerialState &&
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(it) != null
    }
  }

  private fun adoptionCanonical(
    payload: Map<String, Any?>,
    original: FeatureTaskRuntimeWorkflowArtifactMap,
    executionPlans: FeatureTaskRuntimeExecutionPlanValidator,
  ): FeatureTaskRuntimeWorkflowArtifactMap? {
    val descriptor = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(original) ?: return null
    if (digest(descriptor) != payload[Keys.DESCRIPTOR_DIGEST]) return null
    val bytes = JsonCodec.valueToJsonString(descriptor).toByteArray(Charsets.UTF_8)
    val canonicalBytes =
      try {
        executionPlans.canonicalize(bytes, sourceLabel = "acceptance-criterion-repair-adoption")
      } catch (_: SkillBillRuntimeException) {
        return null
      }
    return if (digestBytes(canonicalBytes) == payload[Keys.SKELETON_DIGEST]) {
      FeatureTaskRuntimeWorkflowArtifactMap.from(JsonCodec.parseValue(canonicalBytes.toString(Charsets.UTF_8)))
    } else {
      null
    }
  }

  private fun adoptionPolicyViolation(
    payload: Map<String, Any?>,
    canonical: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? {
    val policyDigest =
      canonicalPolicyDigest(canonical)
        ?: return "adoption descriptor policies are invalid."
    return if (policyDigest == payload[Keys.POLICY_DIGEST]) null else "adoption policy digest changed."
  }

  private fun canonicalPolicyDigest(canonical: FeatureTaskRuntimeWorkflowArtifactMap): String? {
    val policies = canonical[PlanKeys.STEP_POLICIES] as? List<*> ?: return null
    val entries =
      policies.mapNotNull { entry ->
        val row = entry as? Map<*, *> ?: return@mapNotNull null
        val step = row[PlanKeys.STEP] as? String ?: return@mapNotNull null
        val identity = row[PlanKeys.IDENTITY] as? String ?: return@mapNotNull null
        step to identity
      }
    if (entries.size != policies.size || entries.map { it.first }.toSet().size != entries.size) return null
    return digest(entries.toMap())
  }

  private fun adoptionCompositionViolation(
    payload: Map<String, Any?>,
    canonical: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? {
    val composition =
      canonicalComposition(canonical)
        ?: return "adoption descriptor composition is unsupported."
    val revision =
      canonicalAuditRevision(canonical)
        ?: return "adoption descriptor audit revision is invalid."
    return if (payload[Keys.COMPOSITION] == composition &&
      (payload[Keys.SEMANTIC_REVISION] as? Number)?.toInt() == revision
    ) {
      null
    } else {
      "adoption composition or semantic revision disagrees with its descriptor."
    }
  }

  private fun canonicalComposition(canonical: FeatureTaskRuntimeWorkflowArtifactMap): String? {
    val definition = canonical[PlanKeys.DEFINITION] as? Map<*, *> ?: return null
    val definitionId = definition[PlanKeys.ID] as? String ?: return null
    val gate = canonical[PlanKeys.QUALITY_GATE_SELECTION] as? String
    return when {
      definitionId == "standalone" && gate == null -> AcceptanceCriterionRepairAdoptionComposition.STANDALONE.wireValue
      definitionId == "goal-child" && gate == FeatureTaskRuntimeQualityGateSelection.BUILD.wireValue ->
        AcceptanceCriterionRepairAdoptionComposition.GOAL_CHILD_BUILD.wireValue
      definitionId == "goal-child" && gate == FeatureTaskRuntimeQualityGateSelection.VALIDATE.wireValue ->
        AcceptanceCriterionRepairAdoptionComposition.GOAL_CHILD_VALIDATE.wireValue
      else -> null
    }
  }

  private fun canonicalAuditRevision(canonical: FeatureTaskRuntimeWorkflowArtifactMap): Int? =
    canonicalAudit(canonical)?.get(PlanKeys.SEMANTIC_REVISION).let { it as? Number }?.toInt()

  private fun canonicalAudit(canonical: FeatureTaskRuntimeWorkflowArtifactMap): Map<*, *>? =
    (canonical[PlanKeys.SELECTED_STRATEGIES] as? List<*>)
      ?.mapNotNull { it as? Map<*, *> }
      ?.singleOrNull { it[PlanKeys.SLOT] == PhaseSlot.AUDIT.wireValue }

  private fun adoptionVersionViolation(
    payload: Map<String, Any?>,
    canonical: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? {
    val versions =
      payload[Keys.DECLARED_CONTRACT_VERSIONS] as? Map<*, *>
        ?: return "adoption declared contract versions are missing."
    return if (versions[Keys.CONTRACT_VERSION] == canonical[PlanKeys.CONTRACT_VERSION]) {
      null
    } else {
      "adoption execution-plan contract version changed."
    }
  }

  private fun adoptionDiscoveryViolation(
    payload: Map<String, Any?>,
    original: FeatureTaskRuntimeWorkflowArtifactMap,
    canonical: FeatureTaskRuntimeWorkflowArtifactMap,
    phaseOutputs: FeatureTaskRuntimePhaseOutputMigration,
  ): String? {
    val discovery =
      adoptionDiscovery(original, canonical)
        ?: return "adoption discovery record is invalid."
    val sourceVersion =
      phaseOutputVersion(discovery.output, phaseOutputs)
        ?: return "adoption discovery output version is unsupported."
    val versions =
      payload[Keys.DECLARED_CONTRACT_VERSIONS] as? Map<*, *>
        ?: return "adoption declared contract versions are missing."
    return if (versions[Keys.PHASE_OUTPUT_CONTRACT_VERSION] == sourceVersion) {
      null
    } else {
      "adoption phase-output contract version changed."
    }
  }

  private data class AdoptionDiscovery(val output: String)

  private fun adoptionDiscovery(
    original: FeatureTaskRuntimeWorkflowArtifactMap,
    canonical: FeatureTaskRuntimeWorkflowArtifactMap,
  ): AdoptionDiscovery? {
    val entryStep = canonicalAudit(canonical)?.get(PlanKeys.ENTRY_STEP) as? String ?: return null
    val records =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.value(original) as? Map<*, *>
        ?: return null
    val discovery = records[entryStep] as? Map<*, *> ?: return null
    val completed =
      discovery[SharedPayloadKeys.PHASE_ID] == entryStep &&
        discovery[SharedPayloadKeys.STATUS] == WorkflowStepStatus.COMPLETED.wireValue
    val singleAttempt = (discovery[PhaseKeys.ATTEMPT_COUNT] as? Number)?.toInt() == 1
    val finished = !(discovery[PhaseKeys.FINISHED_AT] as? String).isNullOrBlank()
    val output = discovery[SharedPayloadKeys.OUTPUT_ARTIFACT] as? String
    val valid = completed && singleAttempt && finished
    return if (valid && output != null) AdoptionDiscovery(output) else null
  }

  private fun phaseOutputVersion(
    output: String,
    phaseOutputs: FeatureTaskRuntimePhaseOutputMigration,
  ): String? =
    when (val migrated = phaseOutputs.migrate(output)) {
      is FeatureTaskRuntimePhaseOutputMigrationResult.Current ->
        (JsonCodec.parseValue(migrated.payload) as? Map<*, *>)?.get(SharedPayloadKeys.CONTRACT_VERSION) as? String
      is FeatureTaskRuntimePhaseOutputMigrationResult.Migrated -> migrated.sourceVersion
      is FeatureTaskRuntimePhaseOutputMigrationResult.Refused -> null
    }

  private fun digest(value: Any?): String =
    MessageDigest.getInstance("SHA-256")
      .digest(JsonCodec.valueToJsonString(value).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

  private fun digestBytes(value: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

  private fun formatReason(errors: Set<ValidationMessage>): String =
    errors.sortedBy { it.instanceLocation?.toString().orEmpty() }
      .take(MAX_REPORTED_VIOLATIONS)
      .joinToString(" | ") { error ->
        "${error.instanceLocation?.toString()?.ifBlank { "<root>" } ?: "<root>"}: ${error.message.orEmpty()}"
      } + if (errors.size > MAX_REPORTED_VIOLATIONS) " (+${errors.size - MAX_REPORTED_VIOLATIONS} more)" else ""
}
