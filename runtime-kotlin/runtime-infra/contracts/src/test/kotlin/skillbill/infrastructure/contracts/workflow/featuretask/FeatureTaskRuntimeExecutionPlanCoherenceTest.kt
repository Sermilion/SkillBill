package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.identity.task.FEATURE_TASK_RUNTIME_EXECUTION_PLAN_CONTRACT_VERSION
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeExecutionPlanSchemaError
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as Keys

class FeatureTaskRuntimeExecutionPlanCoherenceTest {
  @Test
  fun `coherence refuses conflicting owners references and unreachable remediation without echoing input`() {
    val cases =
      linkedMapOf<String, (ObjectNode) -> Unit>(
        "duplicate slot with distinct strategy" to { plan ->
          plan.rows(Keys.SELECTED_STRATEGIES).objectAt(1).put(Keys.SLOT, "implementation")
        },
        "entry owned by another strategy" to { plan ->
          plan.rows(Keys.SELECTED_STRATEGIES).objectAt(0).put(Keys.ENTRY_STEP, "review")
        },
        "duplicate selected ownership" to { plan ->
          plan.rows(Keys.SELECTED_STRATEGIES).objectAt(1).rows(Keys.SELECTED_STEPS).add("implement")
        },
        "missing dispatch" to { plan -> plan.rows(Keys.DISPATCH_OWNERSHIP).remove(0) },
        "duplicate dispatch" to { plan ->
          plan.rows(Keys.DISPATCH_OWNERSHIP).add(plan.rows(Keys.DISPATCH_OWNERSHIP)[0].deepCopy<JsonNode>())
        },
        "wrong dispatch revision" to { plan ->
          plan.rows(Keys.DISPATCH_OWNERSHIP).objectAt(0).put(Keys.SEMANTIC_REVISION, 2)
        },
        "wrong dispatch owner" to { plan ->
          plan.rows(Keys.DISPATCH_OWNERSHIP).objectAt(0).put(Keys.STRATEGY_ID, "private-payload")
        },
        "missing policy" to { plan -> plan.rows(Keys.STEP_POLICIES).remove(0) },
        "duplicate resume policy" to { plan ->
          plan.rows(Keys.RESUME_INTERPRETATIONS).add(plan.rows(Keys.RESUME_INTERPRETATIONS)[0].deepCopy<JsonNode>())
        },
        "duplicate forward step" to { plan -> plan.traversal().rows(Keys.FORWARD_STEPS).add("review") },
        "dangling backward edge" to { plan ->
          plan.traversal().rows(Keys.BACKWARD_EDGES).objectAt(0).put(Keys.DESTINATION_STEP, "private-payload")
        },
        "dangling gate" to { plan ->
          plan.traversal().rows(Keys.ENTRY_GATES).objectAt(0).put(Keys.REQUIRED_STEP, "private-payload")
        },
        "gate requires itself" to { plan ->
          plan.traversal().rows(Keys.ENTRY_GATES).objectAt(0).put(Keys.REQUIRED_STEP, "review")
        },
        "gate requires later step" to { plan ->
          plan.traversal().rows(Keys.ENTRY_GATES).objectAt(0).put(Keys.REQUIRED_STEP, "implement_fix")
        },
        "duplicate gate" to { plan ->
          val gates = plan.traversal().rows(Keys.ENTRY_GATES)
          gates.add(gates[0].deepCopy<JsonNode>())
        },
        "unreachable remediation cycle" to { plan ->
          plan.traversal().rows(Keys.BACKWARD_EDGES).objectAt(0).put(Keys.FROM_STEP, "verify_findings")
        },
        "loop successor outside loop" to { plan ->
          plan.traversal().rows(Keys.LOOP_ONLY_SUCCESSORS).objectAt(0).put(Keys.SUCCESSOR, "review")
        },
        "loop successor points backward" to { plan ->
          plan.traversal().rows(Keys.LOOP_ONLY_SUCCESSORS).objectAt(0)
            .put(Keys.STEP, "verify_findings").put(Keys.SUCCESSOR, "implement_fix")
        },
        "duplicate successor source" to { plan ->
          val successors = plan.traversal().rows(Keys.LOOP_ONLY_SUCCESSORS)
          successors.add(successors[0].deepCopy<JsonNode>())
        },
      )
    cases.forEach { (name, corrupt) ->
      val plan = descriptor()
      corrupt(plan)
      val error =
        assertFailsWith<InvalidFeatureTaskRuntimeExecutionPlanSchemaError>(name) {
          validate(plan)
        }
      assertFalse(error.message.orEmpty().contains("private-payload"), name)
    }
  }

  @Test
  fun `coherence accepts reachable loop only remediation and a single step definition`() {
    validate(descriptor())
    validate(descriptor(listOf("validate"), emptyList()))
  }

  @Test
  fun `canonical form ignores object and named collection order but preserves traversal and step order`() {
    val original = descriptor()
    val reordered = mapper.createObjectNode()
    original.fields().asSequence().toList().reversed().forEach { (key, value) ->
      reordered.set<JsonNode>(key, value.deepCopy<JsonNode>())
    }
    listOf(Keys.SELECTED_STRATEGIES, Keys.DISPATCH_OWNERSHIP, Keys.STEP_POLICIES, Keys.RESUME_INTERPRETATIONS)
      .forEach { reordered.reverseRows(it) }
    reordered.traversal().reverseRows(Keys.LOOP_ONLY_STEPS)

    assertEquals(canonicalExecutionPlan(original).toString(), canonicalExecutionPlan(reordered).toString())
    assertEquals(original, descriptor())

    val changedTraversal = descriptor().apply { traversal().reverseRows(Keys.FORWARD_STEPS) }
    assertNotEquals(canonicalExecutionPlan(original), canonicalExecutionPlan(changedTraversal))
    val changedSelection =
      descriptor().apply {
        rows(Keys.SELECTED_STRATEGIES).objectAt(0).reverseRows(Keys.SELECTED_STEPS)
      }
    assertNotEquals(canonicalExecutionPlan(original), canonicalExecutionPlan(changedSelection))
    val changedPolicy =
      descriptor().apply {
        rows(Keys.STEP_POLICIES).objectAt(0).put(Keys.IDENTITY, "different-policy")
      }
    assertNotEquals(canonicalExecutionPlan(original), canonicalExecutionPlan(changedPolicy))
  }

  @Test
  fun `canonicalization preserves edge order and distinguishes absent from empty and null`() {
    val first = mapper.createObjectNode().put(Keys.FROM_STEP, "review").put(Keys.DESTINATION_STEP, "implement_fix")
    val second =
      mapper.createObjectNode().put(
        Keys.FROM_STEP,
        "verify_findings",
      ).put(Keys.DESTINATION_STEP, "implement")
    val ordered = descriptor().apply { traversal().rows(Keys.BACKWARD_EDGES).removeAll().add(first).add(second) }
    val reversed = ordered.deepCopy().apply { traversal().reverseRows(Keys.BACKWARD_EDGES) }
    assertNotEquals(canonicalExecutionPlan(ordered), canonicalExecutionPlan(reversed))

    val empty = descriptor()
    val absent = empty.deepCopy().apply { remove(Keys.RESUME_INTERPRETATIONS) }
    val nullValue = absent.deepCopy().apply { putNull(Keys.RESUME_INTERPRETATIONS) }
    empty.rows(Keys.RESUME_INTERPRETATIONS).removeAll()
    assertNotEquals(canonicalExecutionPlan(empty), canonicalExecutionPlan(absent))
    assertNotEquals(canonicalExecutionPlan(absent), canonicalExecutionPlan(nullValue))
    assertNotEquals(canonicalExecutionPlan(empty), canonicalExecutionPlan(nullValue))
  }

  @Test
  fun `production reader and producer enforce collection identifier revision and digest limits`() {
    val cases = invalidSettingCases() + invalidStructureCases()
    cases.forEach { (name, corrupt) ->
      val plan = descriptor().also(corrupt)
      val payload = checkNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(plan.toString())))
      val readFailure =
        assertFailsWith<InvalidFeatureTaskRuntimeExecutionPlanSchemaError>(name) {
          validator.read(plan.toString().toByteArray(Charsets.UTF_8), name)
        }
      val writeFailure =
        assertFailsWith<InvalidFeatureTaskRuntimeExecutionPlanSchemaError>(name) {
          validator.write(payload, name)
        }
      val expected =
        if (name.startsWith("ambiguous")) {
          "execution plan has incoherent selected ownership or traversal references"
        } else {
          "execution plan violates its schema"
        }
      assertEquals(expected, readFailure.reason, name)
      assertEquals(readFailure.reason, writeFailure.reason, name)
    }
  }

  @Test
  fun `maximum identifier revision and attempt bounds remain accepted`() {
    val plan =
      descriptor().apply {
        (path(Keys.DEFINITION) as ObjectNode).put(Keys.ID, "a".repeat(128))
          .put(Keys.SEMANTIC_REVISION, Int.MAX_VALUE)
        traversal().rows(Keys.BACKWARD_EDGES).objectAt(0)
          .put(Keys.PER_EDGE_CAP, Int.MAX_VALUE).put(Keys.WARN_AFTER_ITERATIONS, Int.MAX_VALUE)
        (path(Keys.EFFECTIVE_POLICY_SETTINGS) as ObjectNode).put(Keys.PHASE_TIMEOUT_MILLIS, Long.MAX_VALUE)
      }
    validate(plan)
  }

  @Test
  fun `production canonical encoding ignores object order and preserves semantic policy changes`() {
    val original = descriptor()
    val reordered =
      mapper.createObjectNode().apply {
        original.fields().asSequence().toList().reversed().forEach { (key, value) -> set<JsonNode>(key, value) }
      }
    val first = validator.read(original.toString().toByteArray(), "first source label")
    val second = validator.read(reordered.toString().toByteArray(), "different cosmetic source label")
    assertEquals(first, second)
    assertEquals(validator.write(first, "first").toList(), validator.write(second, "second").toList())
    val changed =
      descriptor().apply {
        rows(Keys.STEP_POLICIES).objectAt(0).put(Keys.SEMANTIC_DIGEST, "c".repeat(64))
      }
    assertNotEquals(first, validator.read(changed.toString().toByteArray(), "changed semantics"))
  }

  private fun ObjectNode.repeatRow(
    key: String,
    count: Int,
  ) {
    val array = rows(key)
    val row = array[0].deepCopy<JsonNode>()
    array.removeAll()
    repeat(count) { array.add(row.deepCopy<JsonNode>()) }
  }

  private fun validate(plan: ObjectNode) {
    val bytes = plan.toString().toByteArray(Charsets.UTF_8)
    val read = validator.read(bytes, "boundary")
    val payload = checkNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(plan.toString())))
    assertEquals(read, validator.read(validator.write(payload, "boundary"), "boundary"))
  }

  private fun invalidSettingCases(): Map<String, (ObjectNode) -> Unit> =
    linkedMapOf(
      "unknown governed field" to { it.put("unrecognized", true) },
      "unknown effective setting" to {
        (
          it.path(
            Keys.EFFECTIVE_POLICY_SETTINGS,
          ) as ObjectNode
        ).put("unrecognized", true)
      },
      "missing validation depth" to {
        (
          it.path(
            Keys.EFFECTIVE_POLICY_SETTINGS,
          ) as ObjectNode
        ).remove(Keys.VALIDATION_DEPTH)
      },
      "unsupported validation depth" to {
        (it.path(Keys.EFFECTIVE_POLICY_SETTINGS) as ObjectNode).put(Keys.VALIDATION_DEPTH, "shallow")
      },
      "negative phase timeout" to {
        (it.path(Keys.EFFECTIVE_POLICY_SETTINGS) as ObjectNode).put(Keys.PHASE_TIMEOUT_MILLIS, -1)
      },
      "phase timeout overflow" to {
        (
          it.path(
            Keys.EFFECTIVE_POLICY_SETTINGS,
          ) as ObjectNode
        ).put(Keys.PHASE_TIMEOUT_MILLIS, Long.MAX_VALUE.toBigInteger().add(BigInteger.ONE))
      },
      "fractional phase timeout" to {
        (it.path(Keys.EFFECTIVE_POLICY_SETTINGS) as ObjectNode).put(Keys.PHASE_TIMEOUT_MILLIS, 1.5)
      },
      "identifier exceeds 128 ASCII characters" to {
        it.rows(Keys.SELECTED_STRATEGIES).objectAt(0).put(Keys.STRATEGY_ID, "a".repeat(129))
      },
      "non ASCII identifier" to { it.rows(Keys.SELECTED_STRATEGIES).objectAt(0).put(Keys.STRATEGY_ID, "stratégie") },
    )

  private fun invalidStructureCases(): Map<String, (ObjectNode) -> Unit> =
    linkedMapOf(
      "zero revision" to { (it.path(Keys.DEFINITION) as ObjectNode).put(Keys.SEMANTIC_REVISION, 0) },
      "overflow revision" to { (it.path(Keys.DEFINITION) as ObjectNode).put(Keys.SEMANTIC_REVISION, 2147483648L) },
      "overflow attempt cap" to {
        it.traversal().rows(
          Keys.BACKWARD_EDGES,
        ).objectAt(0).put(Keys.PER_EDGE_CAP, 2147483648L)
      },
      "overflow warning threshold" to {
        it.traversal().rows(Keys.BACKWARD_EDGES).objectAt(0).put(Keys.WARN_AFTER_ITERATIONS, 2147483648L)
      },
      "uppercase digest" to { it.rows(Keys.STEP_POLICIES).objectAt(0).put(Keys.SEMANTIC_DIGEST, "A".repeat(64)) },
      "short digest" to { it.rows(Keys.STEP_POLICIES).objectAt(0).put(Keys.SEMANTIC_DIGEST, "a".repeat(63)) },
      "too many strategies" to { it.repeatRow(Keys.SELECTED_STRATEGIES, 33) },
      "too many selected steps" to { plan ->
        val steps = plan.rows(Keys.SELECTED_STRATEGIES).objectAt(0).rows(Keys.SELECTED_STEPS)
        steps.removeAll()
        repeat(129) { steps.add("step-$it") }
      },
      "too many dispatch owners" to { it.repeatRow(Keys.DISPATCH_OWNERSHIP, 129) },
      "too many policy descriptors" to { it.repeatRow(Keys.STEP_POLICIES, 257) },
      "too many resume descriptors" to { it.repeatRow(Keys.RESUME_INTERPRETATIONS, 257) },
      "too many backward edges" to { it.traversal().repeatRow(Keys.BACKWARD_EDGES, 257) },
      "too many gates" to { it.traversal().repeatRow(Keys.ENTRY_GATES, 257) },
      "too many successors" to { it.traversal().repeatRow(Keys.LOOP_ONLY_SUCCESSORS, 257) },
      "ambiguous edge trigger" to { plan ->
        val edges = plan.traversal().rows(Keys.BACKWARD_EDGES)
        edges.add(edges.objectAt(0).deepCopy().put(Keys.LOOP_ID, "other-repair"))
      },
      "ambiguous loop identity" to { plan ->
        val edges = plan.traversal().rows(Keys.BACKWARD_EDGES)
        edges.add(edges.objectAt(0).deepCopy().put(Keys.VERDICT, "record_rejected"))
      },
    )

  private val validator = FeatureTaskRuntimeExecutionPlanSchemaValidator()

  private fun descriptor(
    implementation: List<String> = listOf("implement", "simplify"),
    review: List<String> = listOf("review", "implement_fix", "verify_findings"),
  ): ObjectNode {
    val groups = listOf("implementation" to implementation, "code_review" to review).filter { it.second.isNotEmpty() }
    val steps = groups.flatMap { it.second }
    val loopOnly = review.drop(1)
    return mapper.createObjectNode().apply {
      put(Keys.CONTRACT_VERSION, FEATURE_TASK_RUNTIME_EXECUTION_PLAN_CONTRACT_VERSION)
      putObject(Keys.DEFINITION).put(Keys.ID, "boundary").put(Keys.SEMANTIC_REVISION, 1)
      putNull(Keys.REVIEW_SELECTION)
      putNull(Keys.QUALITY_GATE_SELECTION)
      putObject(Keys.EFFECTIVE_POLICY_SETTINGS)
        .put(Keys.VALIDATION_DEPTH, "full")
        .putNull(Keys.PHASE_TIMEOUT_MILLIS)
      putArray(Keys.EFFECTIVE_POLICIES)
      writeStrategyRows(groups)
      putObject(Keys.TRAVERSAL).apply {
        putArray(Keys.FORWARD_STEPS).also { array -> steps.forEach(array::add) }
        putArray(Keys.LOOP_ONLY_STEPS).also { array -> loopOnly.forEach(array::add) }
        putArray(Keys.BACKWARD_EDGES).apply {
          if (loopOnly.isNotEmpty()) {
            addObject()
              .put(Keys.FROM_STEP, review.first()).put(Keys.DESTINATION_STEP, loopOnly.first())
              .put(Keys.VERDICT, "changes_requested").put(Keys.LOOP_ID, "repair")
              .put(Keys.PER_EDGE_CAP, 1).put(Keys.CAP_EXHAUSTION_BEHAVIOR, "BLOCK")
              .put(Keys.CAP_SCOPE, "PER_SUBTASK").putNull(Keys.WARN_AFTER_ITERATIONS)
          }
        }
        putArray(Keys.ENTRY_GATES).apply {
          if (review.isNotEmpty()) {
            addObject().put(Keys.STEP, review.first())
              .put(Keys.REQUIRED_STEP, implementation.last()).put(Keys.REQUIRED_VERDICT, "advance")
          }
        }
        putArray(Keys.LOOP_ONLY_SUCCESSORS).apply {
          loopOnly.zipWithNext().forEach { (step, successor) ->
            addObject().put(Keys.STEP, step).put(Keys.SUCCESSOR, successor)
          }
        }
      }
    }
  }

  private fun ObjectNode.writeStrategyRows(groups: List<Pair<String, List<String>>>) {
    val strategies = putArray(Keys.SELECTED_STRATEGIES)
    val dispatches = putArray(Keys.DISPATCH_OWNERSHIP)
    val policies = putArray(Keys.STEP_POLICIES)
    val resumes = putArray(Keys.RESUME_INTERPRETATIONS)
    groups.forEach { (slot, owned) ->
      strategies.addObject().apply {
        put(Keys.SLOT, slot)
        put(Keys.STRATEGY_ID, slot)
        put(Keys.SEMANTIC_REVISION, 1)
        put(Keys.ENTRY_STEP, owned.first())
        putArray(Keys.SELECTED_STEPS).also { array -> owned.forEach(array::add) }
      }
      owned.forEach { step ->
        dispatches.addObject().put(Keys.STEP, step).put(Keys.SLOT, slot)
          .put(Keys.STRATEGY_ID, slot).put(Keys.SEMANTIC_REVISION, 1)
        policies.addObject().put(
          Keys.STEP,
          step,
        ).put(Keys.IDENTITY, "policy-$step").put(Keys.SEMANTIC_DIGEST, "a".repeat(64))
        resumes.addObject().put(
          Keys.STEP,
          step,
        ).put(Keys.IDENTITY, "resume-$step").put(Keys.SEMANTIC_DIGEST, "b".repeat(64))
      }
    }
  }

  private fun ObjectNode.traversal(): ObjectNode = path(Keys.TRAVERSAL) as ObjectNode

  private fun ObjectNode.rows(key: String): ArrayNode = path(key) as ArrayNode

  private fun ArrayNode.objectAt(index: Int): ObjectNode = get(index) as ObjectNode

  private fun ObjectNode.reverseRows(key: String) {
    val array = rows(key)
    val values = array.toList().reversed()
    array.removeAll().addAll(values)
  }

  private val mapper = ObjectMapper()
}
