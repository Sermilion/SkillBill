package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.identity.task.FEATURE_TASK_RUNTIME_EXECUTION_PLAN_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.task.FEATURE_TASK_RUNTIME_EXECUTION_PLAN_LEGACY_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.task.FEATURE_TASK_RUNTIME_EXECUTION_PLAN_PREVIOUS_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys
import skillbill.error.core.UnsupportedJsonValueError
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeExecutionPlanSchemaError
import skillbill.error.featuretask.UnsupportedFeatureTaskRuntimeExecutionPlanError
import skillbill.error.shellcontent.incoherentStepLaunchAssignments
import skillbill.error.shellcontent.malformedStepLaunchAssignment
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimeExecutionPlanSchemaPaths
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as Keys
import skillbill.ports.taskruntime.executionplan.FeatureTaskRuntimeExecutionPlanValidator as FeatureTaskRuntimeExecutionPlanValidatorPort

@Inject
class FeatureTaskRuntimeExecutionPlanSchemaValidator : FeatureTaskRuntimeExecutionPlanValidatorPort {
  override fun canonicalize(
    encoded: ByteArray,
    sourceLabel: String,
  ): ByteArray = JsonCodec.mapToJsonString(read(encoded, sourceLabel)).toByteArray(Charsets.UTF_8)

  fun read(
    encoded: ByteArray,
    sourceLabel: String,
  ): Map<String, Any?> {
    if (sourceLabel.isBlank()) invalidPlan("execution plan source label must be non-blank")
    requireBoundedBytes(encoded)
    val raw =
      try {
        Charsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(encoded)).toString()
      } catch (_: CharacterCodingException) {
        invalidPlan("invalid UTF-8 encoding")
      }
    val instance =
      try {
        STRICT_JSON.readTree(raw)
      } catch (_: JsonProcessingException) {
        invalidPlan("malformed or ambiguous JSON")
      }
    if (instance == null || !instance.isObject) {
      invalidPlan("execution plan must be a JSON object")
    }
    val version = instance.path(FeatureTaskRuntimeExecutionPlanKeys.CONTRACT_VERSION)
    val versionText = version.takeIf { it.isTextual }?.asText()
    val supportedHistorical =
      versionText == FEATURE_TASK_RUNTIME_EXECUTION_PLAN_PREVIOUS_CONTRACT_VERSION ||
        versionText == FEATURE_TASK_RUNTIME_EXECUTION_PLAN_LEGACY_CONTRACT_VERSION
    if (unsupportedCurrentVersion(versionText, supportedHistorical)) {
      throw UnsupportedFeatureTaskRuntimeExecutionPlanError()
    }
    validateInstance(instance, versionText)
    return JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(canonicalExecutionPlan(instance).toString()))
      ?: invalidPlan("execution plan must be a JSON object")
  }

  fun write(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ): ByteArray {
    val encoded =
      try {
        JsonCodec.mapToJsonString(payload).toByteArray(Charsets.UTF_8)
      } catch (_: UnsupportedJsonValueError) {
        throw InvalidFeatureTaskRuntimeExecutionPlanSchemaError("unsupported JSON value")
      }
    return JsonCodec.mapToJsonString(read(encoded, sourceLabel)).toByteArray(Charsets.UTF_8)
  }

  fun validate(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ) {
    write(payload, sourceLabel)
  }

  private fun validateInstance(
    instance: JsonNode,
    contractVersion: String?,
  ) {
    val errors: Set<ValidationMessage> =
      ClasspathContractSchemaLoader.validate(schemaFor(contractVersion), instance)
    if (errors.isNotEmpty()) {
      throw InvalidFeatureTaskRuntimeExecutionPlanSchemaError(
        "execution plan violates its schema",
      )
    }
    validateExecutionPlanCoherence(
      instance,
      contractVersion == FEATURE_TASK_RUNTIME_EXECUTION_PLAN_LEGACY_CONTRACT_VERSION,
    )
  }
}

private fun schema(): JsonSchema =
  compiledExecutionPlanSchema(
    FeatureTaskRuntimeExecutionPlanSchemaPaths.CLASSPATH_RESOURCE,
    FEATURE_TASK_RUNTIME_EXECUTION_PLAN_CONTRACT_VERSION,
  )

private fun historicalSchema(): JsonSchema =
  compiledExecutionPlanSchema(
    FeatureTaskRuntimeExecutionPlanSchemaPaths.HISTORICAL_0_1,
    FEATURE_TASK_RUNTIME_EXECUTION_PLAN_LEGACY_CONTRACT_VERSION,
  )

private fun previousExecutionPlanSchema(): JsonSchema =
  compiledExecutionPlanSchema(
    FeatureTaskRuntimeExecutionPlanSchemaPaths.HISTORICAL_0_2,
    FEATURE_TASK_RUNTIME_EXECUTION_PLAN_PREVIOUS_CONTRACT_VERSION,
  )

private fun schemaFor(contractVersion: String?): JsonSchema =
  when (contractVersion) {
    FEATURE_TASK_RUNTIME_EXECUTION_PLAN_LEGACY_CONTRACT_VERSION -> historicalSchema()
    FEATURE_TASK_RUNTIME_EXECUTION_PLAN_PREVIOUS_CONTRACT_VERSION -> previousExecutionPlanSchema()
    FEATURE_TASK_RUNTIME_EXECUTION_PLAN_CONTRACT_VERSION -> schema()
    else -> schema()
  }

private fun compiledExecutionPlanSchema(
  resource: String,
  expectedVersion: String,
): JsonSchema =
  ClasspathContractSchemaLoader.compiledSchema(
    CompiledSchemaRequest(
      cacheKey = resource,
      classLoader = FeatureTaskRuntimeExecutionPlanSchemaValidator::class.java.classLoader,
      classpathResource = resource,
      missingResource = {
        InvalidFeatureTaskRuntimeExecutionPlanSchemaError(
          "Canonical schema is missing: $resource",
        )
      },
      processingFailure = { cause ->
        InvalidFeatureTaskRuntimeExecutionPlanSchemaError(cause.message ?: cause::class.simpleName.orEmpty())
      },
      loadFailureLogger = {},
      expectedSchemaId = FeatureTaskRuntimeExecutionPlanSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = expectedVersion,
      identityFailure = ::InvalidFeatureTaskRuntimeExecutionPlanSchemaError,
    ),
  )

private const val MAXIMUM_ENCODED_BYTES: Int = 65536

private val STRICT_JSON =
  ObjectMapper()
    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

private fun requireBoundedBytes(encoded: ByteArray) {
  if (encoded.size > MAXIMUM_ENCODED_BYTES) {
    throw InvalidFeatureTaskRuntimeExecutionPlanSchemaError("execution plan exceeds $MAXIMUM_ENCODED_BYTES UTF-8 bytes")
  }
}

private fun invalidPlan(reason: String): Nothing = throw InvalidFeatureTaskRuntimeExecutionPlanSchemaError(reason)

private fun unsupportedCurrentVersion(
  versionText: String?,
  historical: Boolean,
): Boolean {
  if (versionText == null || historical) return false
  if (versionText == FEATURE_TASK_RUNTIME_EXECUTION_PLAN_CONTRACT_VERSION) return false
  return EXECUTION_PLAN_VERSION.matches(versionText)
}

private val EXECUTION_PLAN_VERSION: Regex = Regex("[0-9]{1,8}\\.[0-9]{1,8}")

internal fun validateExecutionPlanCoherence(
  instance: JsonNode,
  historical: Boolean = false,
) {
  val effective = instance.path(Keys.EFFECTIVE_POLICIES).toList()
  val effectiveIds = effective.map { it.path(Keys.ID).asText() }
  val policyIdentities =
    listOf(Keys.STEP_POLICIES, Keys.RESUME_INTERPRETATIONS).flatMap { family ->
      instance.path(
        family,
      ).map { Triple(family, it.path(Keys.IDENTITY).asText(), it.path(Keys.SEMANTIC_DIGEST).asText()) }
    }.distinct()
  val policyCount = policyIdentities.size + effective.size
  if (effectiveIds.distinct().size != effectiveIds.size || policyCount > MAX_POLICY_IDENTITIES) {
    incoherentPlan()
  }
  val strategies = instance.path(Keys.SELECTED_STRATEGIES).toList()
  val selectedSteps = strategies.flatMap { it.path(Keys.SELECTED_STEPS).map(JsonNode::asText) }
  val selected = selectedSteps.toSet()
  val slots = strategies.map { it.path(Keys.SLOT).asText() }
  val owners = strategies.associateBy(::dispatchIdentity)
  val invalidEntry =
    strategies.any { strategy ->
      strategy.path(Keys.ENTRY_STEP).asText() !in strategy.path(Keys.SELECTED_STEPS).map(JsonNode::asText)
    }
  val dispatches = instance.path(Keys.DISPATCH_OWNERSHIP).toList()
  val invalidDispatch =
    dispatches.any { dispatch ->
      val owner = owners[dispatchIdentity(dispatch)]
      owner == null || dispatch.path(Keys.STEP).asText() !in owner.path(Keys.SELECTED_STEPS).map(JsonNode::asText)
    }
  val declarations = listOf(Keys.DISPATCH_OWNERSHIP, Keys.STEP_POLICIES, Keys.RESUME_INTERPRETATIONS)
  val invalidCoverage =
    declarations.any { field ->
      val steps = instance.path(field).map { it.path(Keys.STEP).asText() }
      steps.size != steps.toSet().size || steps.toSet() != selected
    }
  if (selectedSteps.size != selected.size || slots.size != slots.toSet().size) incoherentPlan()
  if (invalidEntry || invalidDispatch || invalidCoverage) incoherentPlan()
  validateExecutionPlanTraversal(instance.path(Keys.TRAVERSAL), selected)
  if (!historical) validateStepLaunchAssignments(instance, selected)
}

private fun validateStepLaunchAssignments(
  instance: JsonNode,
  selected: Set<String>,
) {
  val rows = stepLaunchAssignmentRows(instance) ?: return
  val stepIds = rows.map { it.path(Keys.STEP_ID).asText() }
  if (stepIds.size != stepIds.toSet().size) {
    throw incoherentStepLaunchAssignments("duplicate step_id")
  }
  if (stepIds.any { it.isBlank() || it !in selected }) {
    throw incoherentStepLaunchAssignments("unknown or blank step_id")
  }
}

private fun stepLaunchAssignmentRows(instance: JsonNode): List<JsonNode>? {
  val assignments = instance.path(Keys.STEP_LAUNCH_ASSIGNMENTS)
  if (assignments.isMissingNode || assignments.isNull) return null
  if (!assignments.isArray) {
    throw malformedStepLaunchAssignment("step_launch_assignments must be an array")
  }
  return assignments.toList()
}

private fun validateExecutionPlanTraversal(
  traversal: JsonNode,
  selected: Set<String>,
) {
  val forward = traversal.path(Keys.FORWARD_STEPS).map(JsonNode::asText)
  val loopOnly = traversal.path(Keys.LOOP_ONLY_STEPS).map(JsonNode::asText)
  val edges = traversal.path(Keys.BACKWARD_EDGES).toList()
  val gates = traversal.path(Keys.ENTRY_GATES).toList()
  val successors = traversal.path(Keys.LOOP_ONLY_SUCCESSORS).toList()
  validateTraversalReferences(traversal, selected)
  validateTraversalIdentities(edges, gates, successors)
  if (forward.toSet() != selected || forward.size != selected.size) incoherentPlan()
  if (loopOnly.size != loopOnly.toSet().size || !selected.containsAll(loopOnly)) incoherentPlan()
  validateTraversalReachability(forward, loopOnly, edges, successors, selected)
}

private fun validateTraversalReferences(
  traversal: JsonNode,
  selected: Set<String>,
) {
  val forward = traversal.path(Keys.FORWARD_STEPS).map(JsonNode::asText)
  val loopOnly = traversal.path(Keys.LOOP_ONLY_STEPS).map(JsonNode::asText)
  val edges = traversal.path(Keys.BACKWARD_EDGES).toList()
  val gates = traversal.path(Keys.ENTRY_GATES).toList()
  val successors = traversal.path(Keys.LOOP_ONLY_SUCCESSORS).toList()
  val invalidEdge =
    edges.any {
      it.path(Keys.FROM_STEP).asText() !in selected || it.path(Keys.DESTINATION_STEP).asText() !in selected
    }
  val invalidGate =
    gates.any {
      val step = it.path(Keys.STEP).asText()
      val required = it.path(Keys.REQUIRED_STEP).asText()
      step !in selected || required !in selected || forward.indexOf(required) >= forward.indexOf(step)
    }
  val invalidSuccessor =
    successors.any {
      val step = it.path(Keys.STEP).asText()
      val successor = it.path(Keys.SUCCESSOR).asText()
      step !in loopOnly || successor !in loopOnly || forward.indexOf(step) >= forward.indexOf(successor)
    }
  if (invalidEdge || invalidGate || invalidSuccessor) incoherentPlan()
}

private fun validateTraversalIdentities(
  edges: List<JsonNode>,
  gates: List<JsonNode>,
  successors: List<JsonNode>,
) {
  val gateIdentities = gates.map { it.path(Keys.STEP).asText() to it.path(Keys.REQUIRED_STEP).asText() }
  val loopIdentities = edges.map { it.path(Keys.LOOP_ID).asText() }
  val edgeTriggers = edges.map { it.path(Keys.FROM_STEP).asText() to it.path(Keys.VERDICT).asText() }
  val successorSources = successors.map { it.path(Keys.STEP).asText() }
  if (gateIdentities.size != gateIdentities.toSet().size) incoherentPlan()
  if (loopIdentities.size != loopIdentities.toSet().size || edgeTriggers.size != edgeTriggers.toSet().size) {
    incoherentPlan()
  }
  if (successorSources.size != successorSources.toSet().size) incoherentPlan()
}

private fun validateTraversalReachability(
  forward: List<String>,
  loopOnly: List<String>,
  edges: List<JsonNode>,
  successors: List<JsonNode>,
  selected: Set<String>,
) {
  val reachable = (forward - loopOnly.toSet()).toMutableSet()
  do {
    val previousSize = reachable.size
    edges.filter { it.path(Keys.FROM_STEP).asText() in reachable }
      .forEach { reachable.add(it.path(Keys.DESTINATION_STEP).asText()) }
    successors.filter { it.path(Keys.STEP).asText() in reachable }
      .forEach { reachable.add(it.path(Keys.SUCCESSOR).asText()) }
  } while (previousSize != reachable.size)
  if (reachable != selected) incoherentPlan()
}

private fun dispatchIdentity(node: JsonNode): Triple<String, String, Int> =
  Triple(
    node.path(Keys.SLOT).asText(),
    node.path(Keys.STRATEGY_ID).asText(),
    node.path(Keys.SEMANTIC_REVISION).asInt(),
  )

private fun incoherentPlan(): Nothing =
  throw InvalidFeatureTaskRuntimeExecutionPlanSchemaError(
    "execution plan has incoherent selected ownership or traversal references",
  )

internal fun canonicalExecutionPlan(instance: JsonNode): JsonNode = canonicalPlanValue(instance, emptyList())

private fun canonicalPlanValue(
  node: JsonNode,
  path: List<String>,
): JsonNode =
  when {
    node.isObject ->
      JsonNodeFactory.instance.objectNode().apply {
        node.fieldNames().asSequence().sorted().forEach { key ->
          set<JsonNode>(key, canonicalPlanValue(node.path(key), path + key))
        }
      }
    node.isArray -> {
      val values = node.map { canonicalPlanValue(it, path) }
      val ordered =
        when (path) {
          listOf(Keys.SELECTED_STRATEGIES) ->
            values.sortedWith(
              compareBy({
                it.path(Keys.SLOT).asText()
              }, { it.path(Keys.STRATEGY_ID).asText() }, { it.path(Keys.SEMANTIC_REVISION).asInt() }),
            )
          listOf(Keys.DISPATCH_OWNERSHIP), listOf(Keys.STEP_POLICIES), listOf(Keys.RESUME_INTERPRETATIONS),
          listOf(Keys.STEP_LAUNCH_ASSIGNMENTS),
          ->
            values.sortedBy { it.path(Keys.STEP).asText().ifEmpty { it.path(Keys.STEP_ID).asText() } }
          listOf(Keys.TRAVERSAL, Keys.LOOP_ONLY_STEPS) -> values.sortedBy(JsonNode::asText)
          listOf(Keys.EFFECTIVE_POLICIES) -> values.sortedBy { it.path(Keys.ID).asText() }
          else -> values
        }
      JsonNodeFactory.instance.arrayNode().addAll(ordered)
    }
    else -> node.deepCopy<JsonNode>()
  }

private const val MAX_POLICY_IDENTITIES = 256
