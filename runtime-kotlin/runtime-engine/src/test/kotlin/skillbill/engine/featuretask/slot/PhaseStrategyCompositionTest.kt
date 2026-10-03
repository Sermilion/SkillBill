package skillbill.engine.featuretask.slot

import org.junit.jupiter.api.io.TempDir
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCodec
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCompatibility
import skillbill.engine.featuretask.lifecycle.execution.executionPolicyDigest
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.phase.core.FeatureTaskPhaseSettlementService
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PROMPT_COMPOSER_ISSUE_KEY
import skillbill.engine.featuretask.phase.prompt.compose.promptComposerBriefingFor
import skillbill.engine.featuretask.slot.PhaseStrategyCompositionTest.PolicyTrait.FILE_MUTATING
import skillbill.engine.featuretask.slot.PhaseStrategyCompositionTest.PolicyTrait.GENERATION_SCOPED
import skillbill.engine.featuretask.slot.PhaseStrategyCompositionTest.PolicyTrait.MUTATING
import skillbill.engine.featuretask.slot.PhaseStrategyCompositionTest.PolicyTrait.READ_ONLY_IDLE
import skillbill.engine.featuretask.slot.PhaseStrategyCompositionTest.PolicyTrait.SINGLE
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.codereview.DelegatedReviewStrategy
import skillbill.engine.featuretask.slot.codereview.InlineReviewStrategy
import skillbill.engine.featuretask.slot.codereview.LaneScript
import skillbill.engine.featuretask.slot.codereview.scriptedDelegatedReviewRunner
import skillbill.engine.featuretask.slot.commitpush.RuntimeCommitStrategy
import skillbill.engine.featuretask.slot.implementation.ImplementThenSimplifyStrategy
import skillbill.engine.featuretask.slot.plan.AgentPlanStrategy
import skillbill.engine.featuretask.slot.plan.GoalPlanFanOutStrategy
import skillbill.engine.featuretask.slot.preplan.AgentPreplanStrategy
import skillbill.engine.featuretask.slot.pullrequest.PrDescriptionStrategy
import skillbill.engine.featuretask.slot.pullrequest.PullRequestReadinessGate
import skillbill.engine.featuretask.slot.qualitygate.agentvalidate.AgentValidateStrategy
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStrategy
import skillbill.engine.featuretask.slot.qualitygate.packvalidation.PackValidationStrategy
import skillbill.engine.featuretask.slot.skeleton.SkeletonStrategyBindings
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.featuretask.slot.writehistory.BoundaryHistoryStrategy
import skillbill.error.featuretask.CorruptFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.InvalidPhaseStrategyCompositionError
import skillbill.error.featuretask.MissingFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.UnsupportedFeatureTaskRuntimeExecutionPlanError
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeExecutionPlanSchemaValidator
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.concurrency.SequentialBoundedWorkFanOutPort
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as Keys

class PhaseStrategyCompositionTest {
  @TempDir lateinit var home: Path

  private fun productionRegistry(): PhaseStrategyRegistry {
    val database =
      sqliteSessionFactoryForTests(
        userHome = home,
        dbPathOverride = home.resolve("metrics.db").toString(),
        environment = emptyMap(),
      )
    return PhaseStrategyRegistry(
      (
        strategies +
          listOf(
            DelegatedReviewStrategy(runner, scriptedDelegatedReviewRunner(database, home, LaneScript())),
            GoalPlanFanOutStrategy(SequentialBoundedWorkFanOutPort, 1),
          )
      ).map { PhaseStrategyRegistration(it, runner) },
    )
  }

  private val runner =
    object : PhaseRunner {
      override fun run(
        input: PhaseStepInput,
        state: PhaseLaunchState,
      ): PhaseStepOutput = error("Policy lookups must not launch a step.")
    }

  private val strategies =
    listOf(
      AgentPreplanStrategy(),
      AgentPlanStrategy(),
      ImplementThenSimplifyStrategy(),
      AcceptanceAuditStrategy(),
      InlineReviewStrategy(runner),
      PackBuildStrategy(),
      PackValidationStrategy(),
      AgentValidateStrategy(),
      BoundaryHistoryStrategy(),
      RuntimeCommitStrategy(),
      PrDescriptionStrategy(
        UnavailablePullRequestIdentityLookup,
        PullRequestReadinessGate(AbsentReadinessEvidence, NoopRuntimeDiagnostics),
        LocalPullRequestTemplateFiles,
      ),
    )

  @Test
  fun `strategies keep audit inspection read only and repairs mutating`() {
    val actual = strategies.flatMap { strategy -> strategy.steps.map { it to strategy.policyFor(it) } }.toMap()

    assertEquals(EXPECTED_POLICIES, actual)
  }

  @Test
  fun `every selectable step without a structured contract settles with the uniform output`() {
    val registry = PhaseStrategyRegistry(strategies)
    val selectable =
      testPhaseStrategyBindings()
        .values
        .flatMap { bindings -> bindings.flatMap { (slot, binding) -> binding.strategyIds.map { slot to it } } }
        .map { (slot, strategyId) -> registry.strategy(slot, strategyId) }
        .distinct()
        .flatMap { strategy -> strategy.steps.map { step -> strategy to step } }

    selectable.forEach { (strategy, step) ->
      val inputs =
        FeatureTaskRuntimePhasePromptComposeInputs(PROMPT_COMPOSER_ISSUE_KEY, promptComposerBriefingFor(step))
      val sections = strategy.promptSections(step, inputs)
      when {
        !sections.settles -> Unit
        sections.outputContract == null -> {
          assertTrue(FeatureTaskPhaseSettlementService.isSettleablePhase(step), "$step must settle on complete/block")
        }
        else -> assertEquals(PhaseSlot.CODE_REVIEW, strategy.slot, "$step carries a structured output contract")
      }
    }
  }

  @Test
  fun `production resolver rejects multiple matching facts independent of fact order`() {
    val registry = PhaseStrategyRegistry(strategies)
    val gateOnly = SkeletonDefinition("gate-only", listOf(PhaseSlot.QUALITY_GATE))
    listOf(PackBuildStrategy.ID, AgentValidateStrategy.ID).forEach { secondStrategy ->
      val selection =
        PhaseStrategySelection(
          registry,
          mapOf(
            gateOnly to
              mapOf(
                PhaseSlot.QUALITY_GATE to
                  PhaseStrategyBinding.ByFact(
                    mapOf(
                      FeatureTaskRuntimeQualityGateSelection.BUILD to PackBuildStrategy.ID,
                      FeatureTaskRuntimeQualityGateSelection.VALIDATE to secondStrategy,
                    ),
                  ),
              ),
          ),
        )
      val lookup = PhaseStrategyLookup(registry, selection)
      val orders =
        listOf(
          linkedSetOf(FeatureTaskRuntimeQualityGateSelection.BUILD, FeatureTaskRuntimeQualityGateSelection.VALIDATE),
          linkedSetOf(FeatureTaskRuntimeQualityGateSelection.VALIDATE, FeatureTaskRuntimeQualityGateSelection.BUILD),
        )
      val failures =
        orders.map { order ->
          assertFailsWith<InvalidPhaseStrategyCompositionError> {
            lookup.executionPlan(PhaseStrategySelectionFacts(gateOnly, order))
          }.message
        }
      assertEquals(failures.first(), failures.last())
    }
  }

  @Test
  fun `execution rejects a slot step outside the resolved plan`() {
    val selectedReview = CompositionTestStrategy(PhaseSlot.CODE_REVIEW, "review-only", listOf(PHASE_REVIEW))
    val registry = PhaseStrategyRegistry(listOf(selectedReview))
    val selection =
      PhaseStrategySelection(
        registry,
        mapOf(SkeletonDefinition.REVIEW to mapOf(PhaseSlot.CODE_REVIEW to PhaseStrategyBinding.Fixed("review-only"))),
      )
    val lookup = PhaseStrategyLookup(registry, selection)
    val plan = lookup.executionPlan(PhaseStrategySelectionFacts(SkeletonDefinition.REVIEW, emptySet()))

    assertFailsWith<InvalidPhaseStrategyCompositionError> {
      lookup.strategyFor(PHASE_VERIFY_FINDINGS, plan)
    }
  }

  @Test
  fun `production resolver accepts every shipped definition and supported typed selection`() {
    val registry = productionRegistry()
    val selection = PhaseStrategySelection(registry, SkeletonStrategyBindings.bindings)
    val lookup = PhaseStrategyLookup(registry, selection)

    SkeletonDefinition.entries.forEach { definition ->
      val reviewModes: List<CodeReviewExecutionMode?> =
        if (PhaseSlot.CODE_REVIEW in definition.slots) CodeReviewExecutionMode.entries.map { it } else listOf(null)
      val qualityGates: List<FeatureTaskRuntimeQualityGateSelection?> =
        if (definition == SkeletonDefinition.GOAL_CHILD) {
          FeatureTaskRuntimeQualityGateSelection.entries.map { it }
        } else {
          listOf(null)
        }
      reviewModes.forEach { reviewMode ->
        qualityGates.forEach { qualityGate ->
          val facts =
            PhaseStrategySelectionFacts(
              definition,
              buildSet {
                reviewMode?.let(::add)
                qualityGate?.let(::add)
              },
            )
          assertSelectedComposition(registry, lookup, facts)
        }
      }
    }
  }

  @Test
  fun `resolved compositions round trip through the production codec without losing traversal or policy semantics`() {
    val registry = productionRegistry()
    val lookup = PhaseStrategyLookup(registry, PhaseStrategySelection(registry, SkeletonStrategyBindings.bindings))
    val codec = FeatureTaskRuntimeExecutionPlanCodec(FeatureTaskRuntimeExecutionPlanSchemaValidator())
    val compatibility = FeatureTaskRuntimeExecutionPlanCompatibility(codec, lookup)
    SkeletonDefinition.entries.forEach { definition ->
      val gates =
        if (definition == SkeletonDefinition.GOAL_CHILD) {
          FeatureTaskRuntimeQualityGateSelection.entries.map { it }
        } else {
          listOf(null)
        }
      gates.forEach { gate ->
        val original =
          lookup.executionPlan(
            PhaseStrategySelectionFacts(
              definition,
              buildSet {
                if (PhaseSlot.CODE_REVIEW in definition.slots) add(CodeReviewExecutionMode.INLINE)
                gate?.let(::add)
              },
            ),
          )
        val encoded =
          codec.encodeExecution(
            original,
            EffectiveGatePolicyInputs(
              if (gate == FeatureTaskRuntimeQualityGateSelection.BUILD) {
                ValidationGateCommandFamily.BUILD
              } else {
                ValidationGateCommandFamily.VALIDATION
              },
              null,
              null,
              null,
              ValidationDepth.FULL,
              null,
            ),
          )
        val restored = compatibility.requireSupportedComposition(encoded)

        assertEquals(original.selectedStrategies, restored.selectedStrategies)
        assertEquals(original.traversal, restored.traversal)
        assertEquals(original.dispatchStrategyByStep, restored.dispatchStrategyByStep)
        assertEquals(original.stepPolicyIdentities, restored.stepPolicyIdentities)
        assertEquals(original.resumeInterpretationIdentities, restored.resumeInterpretationIdentities)
        assertEquals(original.reviewSelection, restored.reviewSelection)
        assertEquals(original.qualityGateSelection, restored.qualityGateSelection)
        assertContentEquals(encoded, codec.encode(restored))
        restored.selectedStepIds.forEach { step ->
          assertSame(lookup.strategyFor(step, original), lookup.strategyFor(step, restored))
        }
      }
    }
  }

  @Test
  fun `reader distinguishes descriptor failures before attaching runners`() {
    val registry = productionRegistry()
    val lookup = PhaseStrategyLookup(registry, PhaseStrategySelection(registry, SkeletonStrategyBindings.bindings))
    val validator = FeatureTaskRuntimeExecutionPlanSchemaValidator()
    val codec = FeatureTaskRuntimeExecutionPlanCodec(validator)
    val compatibility = FeatureTaskRuntimeExecutionPlanCompatibility(codec, lookup)
    val plan = lookup.executionPlan(PhaseStrategySelectionFacts(SkeletonDefinition.VALIDATION, emptySet()))
    val encoded =
      codec.encodeExecution(
        plan,
        EffectiveGatePolicyInputs(ValidationGateCommandFamily.VALIDATION, null, null, null, ValidationDepth.FULL, null),
      )
    val original = validator.read(encoded, "original")

    assertFailsWith<MissingFeatureTaskRuntimeExecutionPlanError> { compatibility.requireSupportedComposition(null) }
    assertFailsWith<CorruptFeatureTaskRuntimeExecutionPlanError> {
      compatibility.requireSupportedComposition("{".toByteArray())
    }
    assertFailsWith<UnsupportedFeatureTaskRuntimeExecutionPlanError> {
      compatibility.requireSupportedComposition(
        JsonCodec.mapToJsonString(original + (Keys.CONTRACT_VERSION to "9.9")).toByteArray(),
      )
    }
    assertUnsupportedSelections(original, compatibility, validator)
    listOf(Keys.STEP_POLICIES, Keys.RESUME_INTERPRETATIONS).forEach { field ->
      val rows = requireNotNull(original[field] as? List<*>)
      val first = requireNotNull(JsonCodec.anyToStringAnyMap(rows.first()))
      val changed = first + (Keys.IDENTITY to "unknown-private-policy")
      val corrupt = original + (field to listOf(changed))
      assertFailsWith<CorruptFeatureTaskRuntimeExecutionPlanError> {
        compatibility.requireSupportedComposition(validator.write(corrupt, "corrupt digest"))
      }
      val unsupportedPolicy = changed + (Keys.SEMANTIC_DIGEST to executionPolicyDigest("unknown-private-policy"))
      val error =
        assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> {
          compatibility.requireSupportedComposition(
            validator.write(original + (field to listOf(unsupportedPolicy)), "unsupported policy"),
          )
        }
      assertFalse(error.message.orEmpty().contains("unknown-private-policy"))
      assertContentEquals(
        encoded,
        codec.encodeExecution(
          plan,
          EffectiveGatePolicyInputs(
            ValidationGateCommandFamily.VALIDATION,
            null,
            null,
            null,
            ValidationDepth.FULL,
            null,
          ),
        ),
      )
    }
  }

  @Test
  fun `supported composition refuses recorded selection and traversal changes without replacing the stored plan`() {
    val registry = productionRegistry()
    val lookup = PhaseStrategyLookup(registry, PhaseStrategySelection(registry, SkeletonStrategyBindings.bindings))
    val validator = FeatureTaskRuntimeExecutionPlanSchemaValidator()
    val codec = FeatureTaskRuntimeExecutionPlanCodec(validator)
    val compatibility = FeatureTaskRuntimeExecutionPlanCompatibility(codec, lookup)
    val plan =
      lookup.executionPlan(
        PhaseStrategySelectionFacts(
          SkeletonDefinition.GOAL_CHILD,
          setOf(CodeReviewExecutionMode.INLINE, FeatureTaskRuntimeQualityGateSelection.BUILD),
        ),
      )
    val encoded =
      codec.encodeExecution(
        plan,
        EffectiveGatePolicyInputs(ValidationGateCommandFamily.BUILD, null, null, null, ValidationDepth.FULL, null),
      )
    val original = validator.read(encoded, "original")
    assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> {
      compatibility.requireSupportedComposition(
        validator.write(
          original + (Keys.QUALITY_GATE_SELECTION to FeatureTaskRuntimeQualityGateSelection.VALIDATE.wireValue),
          "changed gate selection",
        ),
      )
    }
    assertContentEquals(encoded, codec.encode(compatibility.requireSupportedComposition(encoded)))
  }

  @Test
  fun `composition compatibility retains semantics across object order and fresh executable instances`() {
    val registry = productionRegistry()
    val lookup = PhaseStrategyLookup(registry, PhaseStrategySelection(registry, SkeletonStrategyBindings.bindings))
    val validator = FeatureTaskRuntimeExecutionPlanSchemaValidator()
    val codec = FeatureTaskRuntimeExecutionPlanCodec(validator)
    val original = lookup.executionPlan(PhaseStrategySelectionFacts(SkeletonDefinition.VALIDATION, emptySet()))
    val encoded =
      codec.encodeExecution(
        original,
        EffectiveGatePolicyInputs(ValidationGateCommandFamily.VALIDATION, null, null, null, ValidationDepth.FULL, null),
      )
    val reordered = validator.read(encoded, "original").entries.reversed().associate { it.key to it.value }
    val replacement = PackValidationStrategy()
    val freshRegistry = PhaseStrategyRegistry(listOf(PhaseStrategyRegistration(replacement, runner)))
    val freshLookup =
      PhaseStrategyLookup(
        freshRegistry,
        PhaseStrategySelection(
          freshRegistry,
          mapOf(
            SkeletonDefinition.VALIDATION to
              mapOf(
                PhaseSlot.QUALITY_GATE to PhaseStrategyBinding.Fixed(PackValidationStrategy.ID),
              ),
          ),
        ),
      )
    val restored =
      FeatureTaskRuntimeExecutionPlanCompatibility(codec, freshLookup).requireSupportedComposition(
        JsonCodec.mapToJsonString(reordered).toByteArray(),
      )

    assertContentEquals(encoded, codec.encode(restored))
    assertSame(replacement, freshLookup.strategyFor(PHASE_VALIDATE, restored))
  }

  @Test
  fun `a validated traversal override becomes part of the resolved plan`() {
    val registry = productionRegistry()
    val selection = PhaseStrategySelection(registry, SkeletonStrategyBindings.bindings)
    val lookup = PhaseStrategyLookup(registry, selection)
    val facts =
      PhaseStrategySelectionFacts(
        SkeletonDefinition.GOAL_CHILD,
        setOf(CodeReviewExecutionMode.INLINE, FeatureTaskRuntimeQualityGateSelection.VALIDATE),
      )
    val resolved = lookup.executionPlan(facts)
    val override =
      resolved.traversal.copy(
        backwardEdges =
          resolved.traversal.backwardEdges.filter {
            it.triggeringVerdict != FeatureTaskRuntimeVerdict.RECORD_REJECTED
          },
      )
    val accepted = resolved.withTraversal(lookup.validateTraversalOverride(facts, override))

    assertEquals(override, accepted.traversal)
    assertEquals(resolved.selectedStepIds, accepted.selectedStepIds)
  }

  private companion object {
    val EXPECTED_POLICIES =
      mapOf(
        PHASE_PREPLAN to policy(),
        PHASE_PLAN to policy(),
        PHASE_IMPLEMENT to policy(MUTATING, FILE_MUTATING).extendingInventory(),
        PHASE_SIMPLIFY to
          policy(MUTATING, SINGLE, FILE_MUTATING).extendingInventory(),
        PHASE_AUDIT to policy(SINGLE, READ_ONLY_IDLE),
        PHASE_AUDIT_PLAN_FIX to policy(SINGLE, READ_ONLY_IDLE),
        PHASE_AUDIT_IMPLEMENT_FIX to policy(MUTATING, FILE_MUTATING).extendingInventory(),
        PHASE_REVIEW to policy(FILE_MUTATING, GENERATION_SCOPED),
        PHASE_VERIFY_FINDINGS to policy(READ_ONLY_IDLE, FILE_MUTATING),
        PHASE_IMPLEMENT_FIX to
          policy(MUTATING, FILE_MUTATING, GENERATION_SCOPED).extendingInventory(),
        PHASE_BUILD to policy(FILE_MUTATING),
        PHASE_VALIDATE to
          policy(SINGLE, FILE_MUTATING).extendingInventory(),
        PHASE_WRITE_HISTORY to policy(FILE_MUTATING).extendingInventory(),
        PHASE_COMMIT_PUSH to policy(FILE_MUTATING),
        PHASE_PR to policy(FILE_MUTATING),
      )

    fun policy(vararg traits: PolicyTrait): PhaseStepPolicy {
      val set = traits.toSet()
      return PhaseStepPolicy(
        mutating = MUTATING in set,
        singleAgentSession = SINGLE in set,
        readOnlyIdle = READ_ONLY_IDLE in set,
        fileMutating = FILE_MUTATING in set,
        generationScoped = GENERATION_SCOPED in set,
      )
    }

    fun PhaseStepPolicy.extendingInventory() = copy(extendsOwnedInventory = true)
  }

  private enum class PolicyTrait { MUTATING, SINGLE, READ_ONLY_IDLE, FILE_MUTATING, GENERATION_SCOPED }

  private fun assertSelectedComposition(
    registry: PhaseStrategyRegistry,
    lookup: PhaseStrategyLookup,
    facts: PhaseStrategySelectionFacts,
  ) {
    val definition = facts.definition
    val reviewMode = facts.values.filterIsInstance<CodeReviewExecutionMode>().singleOrNull()
    val qualityGate = facts.values.filterIsInstance<FeatureTaskRuntimeQualityGateSelection>().singleOrNull()
    val plan = lookup.executionPlan(facts)

    val excludedGate =
      if (qualityGate == FeatureTaskRuntimeQualityGateSelection.BUILD) PHASE_VALIDATE else PHASE_BUILD
    val expectedSteps = definition.stepIds - excludedGate
    assertEquals(expectedSteps.toSet(), plan.selectedStepIds, definition.id)
    assertEquals(expectedSteps, plan.traversal.forwardPhaseIds, definition.id)
    assertEquals(definition.slots, plan.selectedSlots, definition.id)
    if (definition == SkeletonDefinition.STANDALONE || definition == SkeletonDefinition.GOAL_CHILD) {
      val recoveryEdges =
        plan.traversal.backwardEdges.filter {
          it.triggeringVerdict == FeatureTaskRuntimeVerdict.RECORD_REJECTED
        }
      assertEquals(1, recoveryEdges.size, definition.id)
      assertEquals(
        if (excludedGate == PHASE_BUILD) PHASE_VALIDATE else PHASE_BUILD,
        recoveryEdges.single().destinationPhaseId,
      )
      assertEquals(2, recoveryEdges.single().perEdgeCap)
    }
    val expectedGate = expectedQualityGate(definition, qualityGate)
    if (PhaseSlot.QUALITY_GATE in definition.slots) {
      val selectedGate = if (excludedGate == PHASE_BUILD) PHASE_VALIDATE else PHASE_BUILD
      assertEquals(expectedGate, lookup.strategyFor(selectedGate, plan).strategyId)
    }
    if (definition == SkeletonDefinition.REVIEW) {
      assertEquals(
        if (reviewMode == CodeReviewExecutionMode.DELEGATED) {
          DelegatedReviewStrategy.ID
        } else {
          InlineReviewStrategy.ID
        },
        lookup.strategyFor(PHASE_REVIEW, plan).strategyId,
      )
    }
    if (definition == SkeletonDefinition.GOAL_PLANNING) {
      assertEquals(GoalPlanFanOutStrategy.ID, lookup.strategyFor(PHASE_PLAN, plan).strategyId)
    }
    plan.selectedStrategies.forEach { identity ->
      identity.steps.forEach { step ->
        assertSame(registry.strategy(identity.slot, identity.strategyId), lookup.strategyFor(step, plan))
      }
    }
  }

  private fun assertUnsupportedSelections(
    original: Map<String, Any?>,
    compatibility: FeatureTaskRuntimeExecutionPlanCompatibility,
    validator: FeatureTaskRuntimeExecutionPlanSchemaValidator,
  ) {
    val definition = requireNotNull(JsonCodec.anyToStringAnyMap(original[Keys.DEFINITION]))
    assertFailsWith<UnsupportedFeatureTaskRuntimeExecutionPlanError> {
      compatibility.requireSupportedComposition(
        validator.write(
          original + (Keys.DEFINITION to (definition + (Keys.SEMANTIC_REVISION to 2))),
          "unsupported revision",
        ),
      )
    }
    val changedStrategyRevision = original.toMutableMap()
    listOf(Keys.SELECTED_STRATEGIES, Keys.DISPATCH_OWNERSHIP).forEach { field ->
      changedStrategyRevision[field] =
        requireNotNull(original[field] as? List<*>).map {
          requireNotNull(JsonCodec.anyToStringAnyMap(it)) + (Keys.SEMANTIC_REVISION to 2)
        }
    }
    assertFailsWith<UnsupportedFeatureTaskRuntimeExecutionPlanError> {
      compatibility.requireSupportedComposition(
        validator.write(changedStrategyRevision, "unsupported strategy revision"),
      )
    }
  }

  private fun expectedQualityGate(
    definition: SkeletonDefinition,
    qualityGate: FeatureTaskRuntimeQualityGateSelection?,
  ): String =
    when {
      definition == SkeletonDefinition.VALIDATION -> PackValidationStrategy.ID
      qualityGate == FeatureTaskRuntimeQualityGateSelection.BUILD -> PackBuildStrategy.ID
      else -> AgentValidateStrategy.ID
    }
}
