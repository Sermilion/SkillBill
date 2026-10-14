package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidAcceptanceCriterionRepairStateSchema
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.AcceptanceCriterionRepairStateSchemaPaths
import skillbill.infrastructure.contracts.review.MAX_REPORTED_VIOLATIONS
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition

object AcceptanceCriterionRepairStateSchemaValidator {
  fun validate(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ) {
    val instance: JsonNode = ClasspathContractSchemaLoader.valueToTree(payload)
    val errors: Set<ValidationMessage> = ClasspathContractSchemaLoader.validate(schema(), instance)
    if (errors.isNotEmpty()) {
      throw invalidAcceptanceCriterionRepairStateSchema(sourceLabel, formatReason(errors))
    }
    coherenceViolation(payload)?.let { reason ->
      throw invalidAcceptanceCriterionRepairStateSchema(sourceLabel, reason)
    }
  }

  private fun coherenceViolation(payload: Map<String, Any?>): String? {
    val criteria = payload.objectList(Keys.CRITERIA) ?: return "criteria must be an array of objects."
    val dependencies = payload.objectList(Keys.DEPENDENCIES) ?: return "dependencies must be an array of objects."
    val refs = criteria.mapNotNull { it[Keys.CANONICAL_REF] as? String }
    if (refs.size != refs.toSet().size) return "criteria contain duplicate canonical references."
    val ordinalValues = criteria.mapNotNull { (it[Keys.ORDINAL] as? Number)?.toInt() }
    if (ordinalValues.size != ordinalValues.toSet().size) return "criteria contain duplicate ordinals."
    val byRef = criteria.associateBy { it[Keys.CANONICAL_REF] as String }
    val edges = dependencies.map { edge ->
      val dependent = edge[Keys.DEPENDENT_REF] as String
      val prerequisite = edge[Keys.PREREQUISITE_REF] as String
      if (dependent !in byRef || prerequisite !in byRef) return "dependency references an unknown criterion."
      if (dependent == prerequisite) return "dependency contains a self-edge."
      dependent to prerequisite
    }
    if (edges.toSet().size != edges.size) return "dependencies contain a duplicate edge."
    val order = stableTopologicalOrder(criteria, edges) ?: return "dependencies contain a cycle."
    val sourceUnresolved =
      criteria.filter {
        it[Keys.SOURCE_DISPOSITION] == AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED.wireValue
      }.map { it[Keys.CANONICAL_REF] }
    val expectedQueue = order.filter { it in sourceUnresolved }
    val queue = payload[Keys.UNRESOLVED_QUEUE] as List<*>
    if (queue != expectedQueue) return "unresolved_queue does not match stable topological order."
    if (payload[Keys.CURRENT_CRITERION_REF] != queue.firstOrNull()) return "current criterion is not the queue head."
    val reservation = payload[Keys.RESERVATION] as? Map<*, *>
    if (reservation != null && reservation[Keys.CRITERION_REF] != payload[Keys.CURRENT_CRITERION_REF]) {
      return "live reservation does not target the current criterion."
    }
    val outcomes = payload.objectList(Keys.OUTCOMES) ?: return "outcomes must be an array of objects."
    val reservationIds = outcomes.mapNotNull { it[Keys.RESERVATION_ID] as? String }
    if (reservationIds.size != reservationIds.toSet().size) return "reservation has more than one accepted outcome."
    val outcomeIds = outcomes.mapNotNull { it[Keys.OUTCOME_ID] as? String }
    if (outcomeIds.size != outcomeIds.toSet().size) return "outcome identity is duplicated."
    if (outcomes.any { it[Keys.CRITERION_REF] !in byRef }) return "outcome references an unknown criterion."
    val counted =
      outcomes.filter { it[Keys.OUTCOME_KIND] != AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS.wireValue }
        .groupingBy { it[Keys.CRITERION_REF] as String }.eachCount()
    val totals = payload[Keys.UNSUCCESSFUL_TOTALS] as Map<*, *>
    if (totals.any { (ref, count) ->
        val key = ref as? String ?: return "unsuccessful total has an invalid criterion reference."
        key !in byRef || count != (counted[key] ?: 0)
      }
    ) return "unsuccessful totals do not match accepted outcomes."
    if (counted.any { (ref, count) -> totals[ref] != count }) return "unsuccessful totals omit accepted outcomes."
    if (queue.isEmpty() && payload[Keys.FINAL_AUDIT_REQUIRED] == false &&
      payload[Keys.FINAL_AUDIT_DIGEST] == null
    ) {
      return "completed empty queue lacks final audit evidence."
    }
    return null
  }

  private fun stableTopologicalOrder(
    criteria: List<Map<String, Any?>>,
    edges: List<Pair<String, String>>,
  ): List<String>? {
    val prerequisites = criteria.associate { (it[Keys.CANONICAL_REF] as String) to mutableSetOf<String>() }
    edges.forEach { (dependent, prerequisite) -> prerequisites.getValue(dependent).add(prerequisite) }
    val result = mutableListOf<String>()
    while (result.size < criteria.size) {
      val next = criteria.asSequence()
        .filter { (it[Keys.CANONICAL_REF] as String) !in result }
        .filter { prerequisites.getValue(it[Keys.CANONICAL_REF] as String).all(result::contains) }
        .minByOrNull { (it[Keys.ORDINAL] as Number).toInt() } ?: return null
      result += next[Keys.CANONICAL_REF] as String
    }
    return result
  }

  private fun Map<String, Any?>.objectList(key: String): List<Map<String, Any?>>? =
    (this[key] as? List<*>)?.map { it as? Map<String, Any?> ?: return null }

  private fun formatReason(errors: Set<ValidationMessage>): String =
    errors.sortedBy { it.instanceLocation?.toString().orEmpty() }
      .take(MAX_REPORTED_VIOLATIONS)
      .joinToString(" | ") { error ->
        "${error.instanceLocation?.toString()?.ifBlank { "<root>" } ?: "<root>"}: ${error.message.orEmpty()}"
      } + if (errors.size > MAX_REPORTED_VIOLATIONS) " (+${errors.size - MAX_REPORTED_VIOLATIONS} more)" else ""

  private fun schema(): JsonSchema =
      ClasspathContractSchemaLoader.compiledSchema(
        CompiledSchemaRequest(
          cacheKey = AcceptanceCriterionRepairStateSchemaPaths.CLASSPATH_RESOURCE,
          classLoader = AcceptanceCriterionRepairStateSchemaValidator::class.java.classLoader,
          classpathResource = AcceptanceCriterionRepairStateSchemaPaths.CLASSPATH_RESOURCE,
          missingResource = {
            invalidAcceptanceCriterionRepairStateSchema(
              AcceptanceCriterionRepairStateSchemaPaths.CLASSPATH_RESOURCE,
              "Canonical repair-state schema resource is missing.",
            )
          },
          processingFailure = { cause ->
            invalidAcceptanceCriterionRepairStateSchema(
              AcceptanceCriterionRepairStateSchemaPaths.CLASSPATH_RESOURCE,
              cause.message ?: cause::class.simpleName.orEmpty(),
              cause,
            )
          },
          loadFailureLogger = {},
          expectedSchemaId = AcceptanceCriterionRepairStateSchemaPaths.EXPECTED_SCHEMA_ID,
          expectedContractVersion = ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION,
          identityFailure = { reason ->
            invalidAcceptanceCriterionRepairStateSchema(
              AcceptanceCriterionRepairStateSchemaPaths.CLASSPATH_RESOURCE,
              reason,
            )
          },
        ),
      )
}
