package skillbill.infrastructure.contracts.workflow.featuretask.preservation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.serialization.json.JsonObject
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_MIGRATION_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionProgressPayloadKeys
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.migration.AcceptanceCriterionMigrationFailureCode
import skillbill.error.migration.invalidAcceptanceCriterionMigration
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.workflow.featuretask.AcceptanceCriterionRepairAuxiliarySchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.AcceptanceCriterionRepairStateSchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeImplementationProgressSchemaValidator
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.historical.asMigrationWireMap
import skillbill.workflow.taskruntime.artifact.historical.convertAcceptanceCriterionArtifact
import skillbill.workflow.taskruntime.artifact.historical.criterionVersions
import skillbill.workflow.taskruntime.artifact.historical.migrationContexts
import skillbill.workflow.taskruntime.artifact.historical.targets
import skillbill.workflow.taskruntime.artifact.historical.toMigrationProvenance
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionArtifactConversion
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionArtifactTarget
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionMigrationContext
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionMigrationProvenance
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionMigrationStep
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionMigrationTarget
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import java.security.MessageDigest
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionMigrationKeys as Keys

object AcceptanceCriterionMigrationCodec {
  const val RESOURCE = "skillbill/infrastructure/contracts/acceptance-criterion-migration-schema.yaml"
  const val SCHEMA_ID = "https://skill-bill.dev/contracts/acceptance-criterion-migration.yaml"
  val families: List<DurableWorkflowArtifactFamily> =
    DurableWorkflowArtifactFamily.entries.filter { it.criterionVersions() != null }
  private val provenanceFamily = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_CRITERION_MIGRATION

  fun prepare(
    workflowId: String,
    original: String,
    target: AcceptanceCriterionMigrationTarget,
  ): String = prepare(workflowId, original, target.targets())

  fun prepare(
    workflowId: String,
    original: String,
    targets: List<AcceptanceCriterionArtifactTarget>,
  ): String {
    require(targets.map { it.family }.distinct().size == targets.size) { "Duplicate family conversion targets" }
    targets.forEach { selected ->
      if (selected.version !in selected.family.criterionVersions().orEmpty()) {
        refuse(workflowId, selected.family, selected.version)
      }
    }
    val artifacts = objectPayload(original, workflowId)
    val previous = validateRetained(workflowId, artifacts)
    validateCoupledIdentity(workflowId, artifacts)
    val changed = artifacts.toMutableMap()
    val steps = mutableListOf<AcceptanceCriterionMigrationStep>()
    val contexts = mutableListOf<AcceptanceCriterionMigrationContext>()
    prepareFamilies(workflowId, artifacts, previous, targets, changes = PreparedFamilies(changed, steps, contexts))
    if (steps.isEmpty() && (previous != null || families.none { it.contains(artifacts) })) return original
    if (steps.isEmpty() && contexts.isEmpty()) return original
    val provenance =
      AcceptanceCriterionMigrationProvenance(
        workflowId,
        original,
        digest(original),
        steps,
        mergeContexts(contexts, previous),
      )
    val wire = provenance.asMigrationWireMap()
    validateSchema(RESOURCE, SCHEMA_ID, ACCEPTANCE_CRITERION_MIGRATION_CONTRACT_VERSION, wire, workflowId)
    provenanceFamily.putInto(changed, wire)
    val preserved = (JsonCodec.json.parseToJsonElement(original) as JsonObject).toMutableMap()
    (families.filter { family -> steps.any { it.family == family.label() } } + provenanceFamily).forEach { family ->
      if (family.contains(changed)) preserved[family.label()] = JsonCodec.valueToJsonElement(family.value(changed))
    }
    return JsonCodec.json.encodeToString(JsonObject.serializer(), JsonObject(preserved))
  }

  private data class PreparedFamilies(
    val artifacts: MutableMap<String, Any?>,
    val steps: MutableList<AcceptanceCriterionMigrationStep>,
    val contexts: MutableList<AcceptanceCriterionMigrationContext>,
  )

  private fun prepareFamilies(
    sourceId: String,
    originalArtifacts: Map<String, Any?>,
    previous: AcceptanceCriterionMigrationProvenance?,
    targets: List<AcceptanceCriterionArtifactTarget>,
    changes: PreparedFamilies,
  ) {
    families.filter { it.contains(originalArtifacts) }.forEach { family ->
      val source = FeatureTaskRuntimeWorkflowArtifactMap.from(family.value(originalArtifacts))
      validateSource(family, source, sourceId, previous)
      val targetVersion =
        targets.singleOrNull { it.family == family }?.version
          ?: AcceptanceCriterionMigrationTarget.COMPATIBLE.targets().single { it.family == family }.version
      val conversion = convertAcceptanceCriterionArtifact(family, source, targetVersion)
      when (conversion) {
        is AcceptanceCriterionArtifactConversion.Current -> {
          if (source[Keys.CONTRACT_VERSION] != family.criterionVersions()?.last()) {
            changes.contexts += source.migrationContexts(family)
          }
        }
        is AcceptanceCriterionArtifactConversion.Converted -> {
          conversion.steps.forEach { step ->
            val intermediate =
              convertAcceptanceCriterionArtifact(
                family,
                source,
                step.targetVersion,
              ) as AcceptanceCriterionArtifactConversion.Converted
            validateTarget(family, intermediate.payload, sourceId)
          }
          family.putInto(changes.artifacts, conversion.payload)
          changes.steps += conversion.steps
          changes.contexts += conversion.contexts
        }
        is AcceptanceCriterionArtifactConversion.Unsupported -> refuse(sourceId, family, conversion.version)
      }
    }
  }

  fun validateRetained(
    workflowId: String,
    artifacts: Map<String, Any?>,
  ): AcceptanceCriterionMigrationProvenance? = validateRetained(workflowId, artifacts, 0)

  private fun validateRetained(
    workflowId: String,
    artifacts: Map<String, Any?>,
    depth: Int,
  ): AcceptanceCriterionMigrationProvenance? {
    if (!provenanceFamily.contains(artifacts)) return null
    if (depth >= MAX_RETAINED_GENERATIONS) {
      throw invalidAcceptanceCriterionMigration(
        workflowId,
        "Retained source nesting exceeds capacity",
      )
    }
    val wire = FeatureTaskRuntimeWorkflowArtifactMap.from(provenanceFamily.value(artifacts))
    validateSchema(RESOURCE, SCHEMA_ID, ACCEPTANCE_CRITERION_MIGRATION_CONTRACT_VERSION, wire, workflowId)
    val provenance = wire.toMigrationProvenance(workflowId)
    validateSourceIdentity(workflowId, provenance)
    val original = objectPayload(provenance.originalArtifactsJson, workflowId)
    val earlier = validateRetained(workflowId, original, depth + 1)
    families.filter { it.contains(original) }.forEach { family ->
      validateSource(family, FeatureTaskRuntimeWorkflowArtifactMap.from(family.value(original)), workflowId, earlier)
    }
    val expectedContexts =
      families.filter { it.contains(original) }.flatMap { family ->
        val source = FeatureTaskRuntimeWorkflowArtifactMap.from(family.value(original))
        if (source[Keys.CONTRACT_VERSION] == family.criterionVersions()?.last()) {
          emptyList()
        } else {
          source.migrationContexts(family)
        }
      }
    if (mergeContexts(
        expectedContexts,
        earlier,
      ) != provenance.contexts
    ) {
      throw invalidAcceptanceCriterionMigration(
        workflowId,
        "Historical context differs from retained source",
      )
    }
    validateRetainedSteps(workflowId, original, provenance.steps)
    return provenance
  }

  private fun validateSourceIdentity(
    workflowId: String,
    provenance: AcceptanceCriterionMigrationProvenance,
  ) {
    if (provenance.workflowId != workflowId || digest(provenance.originalArtifactsJson) != provenance.sourceDigest) {
      throw invalidAcceptanceCriterionMigration(workflowId, "Source digest or workflow identity differs")
    }
  }

  private fun validateRetainedSteps(
    workflowId: String,
    original: Map<String, Any?>,
    steps: List<AcceptanceCriterionMigrationStep>,
  ) {
    steps.groupBy { it.family }.forEach { (label, steps) ->
      val family =
        families.singleOrNull { it.label() == label }
          ?: throw invalidAcceptanceCriterionMigration(workflowId, "Unknown conversion family")
      val source = FeatureTaskRuntimeWorkflowArtifactMap.from(family.value(original))
      val converted =
        convertAcceptanceCriterionArtifact(
          family,
          source,
          steps.last().targetVersion,
        )
      if (converted !is AcceptanceCriterionArtifactConversion.Converted || converted.steps != steps) {
        throw invalidAcceptanceCriterionMigration(workflowId, "Conversion chain or steps differ from retained source")
      }
    }
  }

  private fun validateSource(
    family: DurableWorkflowArtifactFamily,
    source: FeatureTaskRuntimeWorkflowArtifactMap,
    workflowId: String,
    provenance: AcceptanceCriterionMigrationProvenance?,
  ) {
    val version = source[Keys.CONTRACT_VERSION] as? String
    if (version !in family.criterionVersions().orEmpty()) refuse(workflowId, family, version)
    if (source.containsKey(Keys.MIGRATION_SOURCE_VERSION)) {
      validateStrictSource(family, source, workflowId, provenance)
      validateTarget(family, source, workflowId)
      return
    }
    when (family) {
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR ->
        if (version == family.criterionVersions()?.last()) {
          AcceptanceCriterionRepairStateSchemaValidator.validate(source, workflowId)
        } else {
          AcceptanceCriterionRepairStateSchemaValidator.validatePrevious(source, workflowId)
        }
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_AUDIT_RESULT ->
        AcceptanceCriterionRepairAuxiliarySchemaValidator.validateAuditResult(source, workflowId)
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR_HANDOFF ->
        AcceptanceCriterionRepairAuxiliarySchemaValidator.validateRepairHandoff(source, workflowId)
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS ->
        if (version == family.criterionVersions()?.last()) {
          FeatureTaskRuntimeImplementationProgressSchemaValidator.validate(source, workflowId)
        } else {
          FeatureTaskRuntimeImplementationProgressSchemaValidator.validatePrevious(source, workflowId)
        }
      else -> refuse(workflowId, family, version)
    }
  }

  private fun validateStrictSource(
    family: DurableWorkflowArtifactFamily,
    source: FeatureTaskRuntimeWorkflowArtifactMap,
    workflowId: String,
    provenance: AcceptanceCriterionMigrationProvenance?,
  ) {
    val retained =
      provenance ?: throw invalidAcceptanceCriterionMigration(
        workflowId,
        "Strict migrated artifact lacks retained source",
      )
    val old =
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        family.value(
          objectPayload(
            retained.originalArtifactsJson,
            workflowId,
          ),
        ),
      )
    val result = convertAcceptanceCriterionArtifact(family, old, family.criterionVersions().orEmpty().last())
    val expected =
      when (result) {
        is AcceptanceCriterionArtifactConversion.Current -> result.payload
        is AcceptanceCriterionArtifactConversion.Converted -> result.payload
        is AcceptanceCriterionArtifactConversion.Unsupported -> refuse(workflowId, family, result.version)
      }
    if (JsonCodec.valueToJsonElement(source) != JsonCodec.valueToJsonElement(expected)) {
      throw invalidAcceptanceCriterionMigration(
        workflowId,
        "Unreconciled strict artifact differs from retained conversion",
      )
    }
  }

  private fun validateTarget(
    family: DurableWorkflowArtifactFamily,
    payload: FeatureTaskRuntimeWorkflowArtifactMap,
    source: String,
  ) {
    if (!payload.containsKey(Keys.MIGRATION_SOURCE_VERSION)) {
      validateSource(family, payload, source, null)
      return
    }
    val stem =
      when (family) {
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR ->
          "acceptance-criterion-repair-state"
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_AUDIT_RESULT ->
          "acceptance-criterion-audit-result"
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR_HANDOFF ->
          "acceptance-criterion-repair-handoff"
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS ->
          "feature-task-runtime-implementation-progress"
        else -> refuse(source, family, null)
      }
    val suffix =
      if (family == DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS) {
        "-schema"
      } else {
        ""
      }
    validateSchema(
      "skillbill/infrastructure/contracts/$stem-schema.yaml",
      "https://skill-bill.dev/contracts/$stem$suffix.yaml",
      family.criterionVersions().orEmpty().last(),
      payload,
      source,
    )
  }

  private fun validateSchema(
    resource: String,
    id: String,
    version: String,
    payload: Map<String, Any?>,
    source: String,
  ) {
    val schema =
      ClasspathContractSchemaLoader.compiledSchema(
        CompiledSchemaRequest(
          cacheKey = resource, classLoader = javaClass.classLoader, classpathResource = resource,
          expectedSchemaId = id, expectedContractVersion = version,
          missingResource = { invalidAcceptanceCriterionMigration(source, "Missing canonical schema $resource") },
          processingFailure = { invalidAcceptanceCriterionMigration(source, "Cannot compile $resource", it) },
          identityFailure = { invalidAcceptanceCriterionMigration(source, it) }, loadFailureLogger = {},
          prepareSchemaDocument = { node ->
            if (resource.endsWith("acceptance-criterion-repair-handoff-schema.yaml")) {
              val audit =
                ClasspathContractSchemaLoader.readClasspathYamlNode(
                  javaClass.classLoader,
                  "skillbill/infrastructure/contracts/acceptance-criterion-audit-result-schema.yaml",
                  { invalidAcceptanceCriterionMigration(source, "Missing comparison schema") },
                )
              (node.path("properties") as ObjectNode).set<JsonNode>(
                AcceptanceCriterionProgressPayloadKeys.INDEPENDENT_PROGRESS,
                audit.path("\$defs").path(AcceptanceCriterionProgressPayloadKeys.INDEPENDENT_PROGRESS),
              )
            }
          },
        ),
      )
    val errors = ClasspathContractSchemaLoader.validate(schema, ClasspathContractSchemaLoader.valueToTree(payload))
    if (errors.isNotEmpty()) {
      throw invalidAcceptanceCriterionMigration(
        source,
        errors.take(MAX_REPORTED_ERRORS).joinToString(" | ") { it.message },
      )
    }
  }

  private fun mergeContexts(
    facts: List<AcceptanceCriterionMigrationContext>,
    previous: AcceptanceCriterionMigrationProvenance?,
  ): List<AcceptanceCriterionMigrationContext> {
    val contexts = previous?.contexts.orEmpty().associateBy { it.family to it.criterionRef }.toMutableMap()
    facts.forEach { fact ->
      val key = fact.family to fact.criterionRef
      val retained = contexts[key]
      contexts[key] =
        fact.copy(
          attributionUnknown = fact.attributionUnknown || retained?.attributionUnknown == true,
          escalationOwed = fact.escalationOwed || retained?.escalationOwed == true,
        )
    }
    return contexts.values.toList()
  }

  private fun validateCoupledIdentity(
    source: String,
    artifacts: Map<String, Any?>,
  ) {
    val keys = AcceptanceCriterionRepairPayloadKeys
    val identified =
      families.filter { it.contains(artifacts) }.map {
        FeatureTaskRuntimeWorkflowArtifactMap.from(it.value(artifacts))
      }.filter { it.containsKey(keys.CATALOG_ID) }
    val identities =
      identified.map {
        listOf(
          it[keys.CATALOG_ID],
          it[keys.SPEC_DIGEST],
          it[keys.EXECUTION_ID],
        )
      }.distinct()
    if (identities.size > 1) {
      throw invalidAcceptanceCriterionMigration(
        source,
        "Coupled catalog, spec or execution identity conflicts",
      )
    }
  }

  private fun objectPayload(
    text: String,
    source: String,
  ): Map<String, Any?> {
    val payload = parseEnvelope(text, source)
    if (!payload.isObject) throw invalidAcceptanceCriterionMigration(source, "Artifact envelope is not an object")
    return payload
  }

  private fun parseEnvelope(
    text: String,
    source: String,
  ): FeatureTaskRuntimeWorkflowArtifactMap =
    try {
      if (JsonCodec.duplicateObjectKeyPath(text) != null) {
        throw invalidAcceptanceCriterionMigration(
          source,
          "Duplicate artifact-envelope field",
        )
      }
      FeatureTaskRuntimeWorkflowArtifactMap.from(JsonCodec.parseValue(text))
    } catch (cause: SkillBillRuntimeException) {
      throw invalidAcceptanceCriterionMigration(source, "Cannot decode retained artifact envelope", cause)
    }

  private fun digest(text: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

  private fun refuse(
    workflowId: String,
    family: DurableWorkflowArtifactFamily,
    version: String?,
  ): Nothing =
    throw SkillBillRuntimeException(
      AcceptanceCriterionMigrationFailureCode.ARTIFACT_MIGRATION_REFUSED,
      "Workflow '$workflowId', ${family.label()}, version '${version ?: "absent"}' cannot migrate. " +
        "Original artifacts_json is retained. Use a runtime supporting this version or repair the retained source, " +
        "then retry readiness.",
    )

  private const val MAX_REPORTED_ERRORS = 3
  private const val MAX_RETAINED_GENERATIONS = 16
}
