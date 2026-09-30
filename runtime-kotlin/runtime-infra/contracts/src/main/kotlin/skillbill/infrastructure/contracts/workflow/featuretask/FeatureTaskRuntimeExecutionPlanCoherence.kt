package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeExecutionPlanSchemaError
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as Keys

internal fun validateExecutionPlanCoherence(instance: JsonNode) {
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
          listOf(Keys.DISPATCH_OWNERSHIP), listOf(Keys.STEP_POLICIES), listOf(Keys.RESUME_INTERPRETATIONS) ->
            values.sortedBy { it.path(Keys.STEP).asText() }
          listOf(Keys.TRAVERSAL, Keys.LOOP_ONLY_STEPS) -> values.sortedBy(JsonNode::asText)
          listOf(Keys.EFFECTIVE_POLICIES) -> values.sortedBy { it.path(Keys.ID).asText() }
          else -> values
        }
      JsonNodeFactory.instance.arrayNode().addAll(ordered)
    }
    else -> node.deepCopy<JsonNode>()
  }

private const val MAX_POLICY_IDENTITIES = 256
