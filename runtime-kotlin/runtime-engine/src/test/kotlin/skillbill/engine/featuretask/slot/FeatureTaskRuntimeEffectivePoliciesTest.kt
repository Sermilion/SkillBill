package skillbill.engine.featuretask.slot

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeEffectivePolicies
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCodec
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCompatibility
import skillbill.engine.featuretask.lifecycle.execution.effectivePolicyDigest
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.slot.qualitygate.packvalidation.PackValidationStrategy
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimeExecutionPlanAdmissionCode
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeExecutionPlanSchemaValidator
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsFormat
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsLocator
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.scaffold.model.ValidationGateExecutedWorkFormat
import skillbill.scaffold.model.ValidationGateExecutedWorkSignal
import skillbill.scaffold.model.ValidationGateFindingsFormat
import skillbill.scaffold.model.ValidationGateFindingsLocator
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedExecutionPolicy
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as Keys

class FeatureTaskRuntimeEffectivePoliciesTest {
  @Test
  fun `durable producer refuses coherent traversal overrides without a supported semantic mapping`() {
    val fixture = ExecutionPlanAdmissionFixture()
    val plan = fixture.plan
    val changed =
      plan.withTraversal(
        plan.traversal.copy(
          backwardEdges =
            plan.traversal.backwardEdges.mapIndexed { index, edge ->
              if (index == 0) edge.copy(perEdgeCap = (edge.perEdgeCap ?: 1) + 1) else edge
            },
        ),
      )
    assertNotEquals(plan.traversal, changed.traversal)
    assertFailsWith<SkillBillRuntimeException> {
      fixture.codec.encodeExecution(changed, fixture.inputs)
    }.also { assertEquals(FeatureTaskRuntimeExecutionPlanAdmissionCode.INCOMPATIBLE_DESCRIPTOR, it.code) }
    val encoded =
      fixture.codec.encode(
        changed.withEffectivePolicies(
          FeatureTaskRuntimeEffectivePolicies.resolve(changed, fixture.inputs),
        ),
      )
    assertFailsWith<SkillBillRuntimeException> {
      fixture.compatibility.requireSupportedExecution(encoded, fixture.inputs)
    }.also { assertEquals(FeatureTaskRuntimeExecutionPlanAdmissionCode.INCOMPATIBLE_DESCRIPTOR, it.code) }
    assertContentEquals(fixture.encoded, fixture.codec.encodeExecution(plan, fixture.inputs))
  }

  @Test
  fun `supported execution preserves every effective policy and rejects omitted changed or unsupported policies`() {
    val original = codec.encodeExecution(plan, inputs)
    val restored = compatibility.requireSupportedExecution(original, inputs)
    assertEquals(
      FeatureTaskRuntimeEffectivePolicies.resolve(plan, inputs).sortedBy { it.id },
      restored.effectivePolicies,
    )
    assertEquals(plan.traversal, restored.traversal)
    assertEquals(plan.dispatchStrategyByStep, restored.dispatchStrategyByStep)
    assertContentEquals(original, codec.encode(restored))
    assertFailsWith<SkillBillRuntimeException> {
      compatibility.requireSupportedExecution(codec.encode(plan), inputs)
    }.also { assertEquals(FeatureTaskRuntimeExecutionPlanAdmissionCode.UNSUPPORTED_DESCRIPTOR, it.code) }
    assertFailsWith<SkillBillRuntimeException> {
      compatibility.requireSupportedExecution(
        codec.encode(
          restored.withEffectivePolicies(
            restored.effectivePolicies + ResolvedExecutionPolicy("unknown-policy", 1, "a".repeat(64)),
          ),
        ),
        inputs,
      )
    }.also { assertEquals(FeatureTaskRuntimeExecutionPlanAdmissionCode.UNSUPPORTED_DESCRIPTOR, it.code) }
    restored.effectivePolicies.forEach { selected ->
      val changed =
        restored.effectivePolicies.map {
          if (it == selected) {
            it.copy(
              semanticDigest = "0".repeat(64),
            )
          } else {
            it
          }
        }
      assertFailsWith<SkillBillRuntimeException> {
        compatibility.requireSupportedExecution(codec.encode(restored.withEffectivePolicies(changed)), inputs)
      }.also { assertEquals(FeatureTaskRuntimeExecutionPlanAdmissionCode.INCOMPATIBLE_DESCRIPTOR, it.code) }
      val revised = restored.effectivePolicies.map { if (it == selected) it.copy(semanticRevision = 2) else it }
      assertFailsWith<SkillBillRuntimeException> {
        compatibility.requireSupportedExecution(codec.encode(restored.withEffectivePolicies(revised)), inputs)
      }.also { assertEquals(FeatureTaskRuntimeExecutionPlanAdmissionCode.UNSUPPORTED_DESCRIPTOR, it.code) }
      assertFailsWith<SkillBillRuntimeException> {
        compatibility.requireSupportedExecution(
          codec.encode(
            restored.withEffectivePolicies(
              restored.effectivePolicies - selected,
            ),
          ),
          inputs,
        )
      }.also { assertEquals(FeatureTaskRuntimeExecutionPlanAdmissionCode.UNSUPPORTED_DESCRIPTOR, it.code) }
    }
    assertContentEquals(original, codec.encode(restored))
  }

  @Test
  fun `effective command identity compares resolved wrappers while retaining ordered argv and execution settings`() {
    val original = codec.encodeExecution(plan, inputs)
    val equivalent =
      inputs.copy(
        gradleWrapper = null,
        declaration =
          declaration.copy(
            collectAllFullGateCommand = listOf("runtime/gradlew", "-p", "runtime", "check", "--continue"),
            cacheBypassingCollectAllFullGateCommand =
              listOf(
                "runtime/gradlew",
                "-p",
                "runtime",
                "check",
                "--continue",
                "--rerun-tasks",
              ),
            suppressionMarkers = declaration.suppressionMarkers.reversed(),
            findings = declaration.findings.copy(artifactGlobs = declaration.findings.artifactGlobs.reversed()),
          ),
      )
    assertContentEquals(original, codec.encodeExecution(plan, equivalent))
    assertContentEquals(original, codec.encode(compatibility.requireSupportedExecution(original, equivalent)))
    val changed =
      listOf(
        inputs.copy(gradleWrapper = "different/gradlew"),
        inputs.copy(packSlug = "custom-pack"),
        inputs.copy(phaseTimeoutMillis = null),
        inputs.copy(phaseTimeoutMillis = 0),
        inputs.copy(commandFamily = ValidationGateCommandFamily.BUILD),
        inputs.copy(declaration = null),
        inputs.copy(
          declaration = declaration.copy(collectAllFullGateCommand = listOf("./gradlew", "--continue", "check")),
        ),
        inputs.copy(
          declaration = declaration.copy(cacheBypassingCollectAllFullGateCommand = listOf("./gradlew", "check")),
        ),
        inputs.copy(declaration = declaration.copy(findings = declaration.findings.copy(executedWork = null))),
        inputs.copy(declaration = declaration.copy(suppressionMarkers = emptyList())),
      )
    changed.forEach { current ->
      val error =
        assertFailsWith<SkillBillRuntimeException> {
          compatibility.requireSupportedExecution(original, current)
        }.also { assertEquals(FeatureTaskRuntimeExecutionPlanAdmissionCode.INCOMPATIBLE_DESCRIPTOR, it.code) }
      assertFalse(error.message.orEmpty().contains("gradlew"))
    }
    assertNotEquals(effectivePolicyDigest(null), effectivePolicyDigest(emptyList<String>()))
    assertNotEquals(effectivePolicyDigest(listOf("a b")), effectivePolicyDigest(listOf("a", "b")))
  }

  @Test
  fun `effective policy serialization is canonical immutable bounded and refuses duplicate ownership`() {
    val policies = FeatureTaskRuntimeEffectivePolicies.resolve(plan, inputs).toMutableList()
    val resolved = plan.withEffectivePolicies(policies)
    val original = codec.encode(resolved)
    policies.clear()
    assertContentEquals(original, codec.encode(resolved))
    assertContentEquals(original, codec.encode(resolved.withEffectivePolicies(resolved.effectivePolicies.reversed())))
    assertFailsWith<UnsupportedOperationException> {
      (resolved.effectivePolicies as MutableList<ResolvedExecutionPolicy>).clear()
    }
    val payload = validator.read(original, "original")
    val rows = requireNotNull(payload[Keys.EFFECTIVE_POLICIES] as? List<*>)
    val duplicate = payload + (Keys.EFFECTIVE_POLICIES to (rows + rows.first()))
    assertFailsWith<SkillBillRuntimeException> {
      validator.read(JsonCodec.mapToJsonString(duplicate).toByteArray(), "duplicate policy")
    }.also { assertEquals(FeatureTaskRuntimeFailureCode.INVALID_EXECUTION_PLAN_SCHEMA, it.code) }
    assertFailsWith<SkillBillRuntimeException> {
      validator.write(duplicate, "duplicate policy")
    }.also { assertEquals(FeatureTaskRuntimeFailureCode.INVALID_EXECUTION_PLAN_SCHEMA, it.code) }
    val maximum =
      resolved.withEffectivePolicies(
        (1..254).map { ResolvedExecutionPolicy("policy-$it", 1, "a".repeat(64)) },
      )
    assertEquals(254, codec.decode(codec.encode(maximum)).effectivePolicies.size)
    val excessive =
      maximum.withEffectivePolicies(
        maximum.effectivePolicies + ResolvedExecutionPolicy("policy-255", 1, "a".repeat(64)),
      )
    assertFailsWith<SkillBillRuntimeException> {
      codec.encode(excessive)
    }.also { assertEquals(FeatureTaskRuntimeFailureCode.INVALID_EXECUTION_PLAN_SCHEMA, it.code) }
    val oversized = inputs.copy(declaration = declaration.copy(suppressionMarkers = listOf("x".repeat(65536))))
    assertFailsWith<SkillBillRuntimeException> {
      codec.encodeExecution(plan, oversized)
    }.also { assertEquals(FeatureTaskRuntimeFailureCode.INVALID_EXECUTION_PLAN_SCHEMA, it.code) }
    val malformed = rows.map { requireNotNull(JsonCodec.anyToStringAnyMap(it)) + (Keys.SEMANTIC_REVISION to 0) }
    assertFailsWith<SkillBillRuntimeException> {
      validator.write(payload + (Keys.EFFECTIVE_POLICIES to malformed), "invalid policy revision")
    }.also { assertEquals(FeatureTaskRuntimeFailureCode.INVALID_EXECUTION_PLAN_SCHEMA, it.code) }
  }

  private val validator = FeatureTaskRuntimeExecutionPlanSchemaValidator()
  private val codec = FeatureTaskRuntimeExecutionPlanCodec(validator)
  private val strategy = PackValidationStrategy()
  private val registry = PhaseStrategyRegistry(listOf(strategy))
  private val lookup =
    PhaseStrategyLookup(
      registry,
      PhaseStrategySelection(
        registry,
        mapOf(
          SkeletonDefinition.VALIDATION to
            mapOf(
              PhaseSlot.QUALITY_GATE to PhaseStrategyBinding.Fixed(strategy.strategyId),
            ),
        ),
      ),
    )
  private val plan = lookup.executionPlan(PhaseStrategySelectionFacts(SkeletonDefinition.VALIDATION, emptySet()))
  private val compatibility = FeatureTaskRuntimeExecutionPlanCompatibility(codec, lookup)
  private val declaration =
    ValidationGateDeclaration(
      fullGateCommand = listOf("./gradlew", "check"),
      cacheBypassingFullGateCommand = listOf("./gradlew", "check", "--rerun-tasks"),
      collectAllFullGateCommand = listOf("./gradlew", "check", "--continue"),
      cacheBypassingCollectAllFullGateCommand = listOf("./gradlew", "check", "--continue", "--rerun-tasks"),
      findings =
        ValidationGateFindingsLocator(
          ValidationGateFindingsFormat.JUNIT_XML,
          listOf("**/test-results/*.xml", "**/reports/*.xml"),
          ValidationGateCompilerDiagnosticsLocator(
            format = ValidationGateCompilerDiagnosticsFormat.GRADLE_KOTLIN_COMPILER_STDOUT,
          ),
          ValidationGateExecutedWorkSignal(ValidationGateExecutedWorkFormat.GRADLE_ACTIONABLE_SUMMARY),
        ),
      suppressionMarkers = listOf("@Suppress", "noinspection"),
    )
  private val inputs =
    EffectiveGatePolicyInputs(
      ValidationGateCommandFamily.VALIDATION,
      "kotlin",
      declaration,
      "runtime/gradlew",
      ValidationDepth.FULL,
      60000,
    )
}
