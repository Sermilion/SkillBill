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
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.phase.prompt.compose.promptComposerBriefingFor
import skillbill.engine.featuretask.qa.execution.testQaExecutionLifecycle
import skillbill.engine.featuretask.qa.unusedQaOutcomeRecorder
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeStepVerdictRule
import skillbill.engine.featuretask.slot.PhaseStrategyCompositionTest.PolicyTrait.FILE_MUTATING
import skillbill.engine.featuretask.slot.PhaseStrategyCompositionTest.PolicyTrait.GENERATION_SCOPED
import skillbill.engine.featuretask.slot.PhaseStrategyCompositionTest.PolicyTrait.MUTATING
import skillbill.engine.featuretask.slot.PhaseStrategyCompositionTest.PolicyTrait.READ_ONLY_IDLE
import skillbill.engine.featuretask.slot.PhaseStrategyCompositionTest.PolicyTrait.SINGLE
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.audit.opus.AcceptanceAuditOpus55Strategy
import skillbill.engine.featuretask.slot.codereview.DelegatedReviewStrategy
import skillbill.engine.featuretask.slot.codereview.InlineReviewStrategy
import skillbill.engine.featuretask.slot.codereview.LaneScript
import skillbill.engine.featuretask.slot.codereview.opus.DelegatedReviewOpus55Strategy
import skillbill.engine.featuretask.slot.codereview.opus.InlineReviewOpus55Strategy
import skillbill.engine.featuretask.slot.codereview.scriptedDelegatedReviewRunner
import skillbill.engine.featuretask.slot.commitpush.RuntimeCommitStrategy
import skillbill.engine.featuretask.slot.implementation.ImplementThenSimplifyOpus55Strategy
import skillbill.engine.featuretask.slot.implementation.ImplementThenSimplifyStrategy
import skillbill.engine.featuretask.slot.implementation.ImplementationPromptSections
import skillbill.engine.featuretask.slot.intake.RuntimeIntakeStrategy
import skillbill.engine.featuretask.slot.monitor.MonitorOpus55Strategy
import skillbill.engine.featuretask.slot.monitor.MonitorStrategy
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorOpus55Strategy
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorPublicationStrategy
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorStrategy
import skillbill.engine.featuretask.slot.plan.AgentPlanOpus55Strategy
import skillbill.engine.featuretask.slot.plan.AgentPlanStrategy
import skillbill.engine.featuretask.slot.plan.PlanFanOutOpus55Strategy
import skillbill.engine.featuretask.slot.plan.PlanFanOutStrategy
import skillbill.engine.featuretask.slot.preplan.AgentPreplanOpus55Strategy
import skillbill.engine.featuretask.slot.preplan.AgentPreplanStrategy
import skillbill.engine.featuretask.slot.pullrequest.PrDescriptionOpus55Strategy
import skillbill.engine.featuretask.slot.pullrequest.PrDescriptionStrategy
import skillbill.engine.featuretask.slot.qa.QaStrategy
import skillbill.engine.featuretask.slot.qualitygate.agentvalidate.AgentValidateOpus55Strategy
import skillbill.engine.featuretask.slot.qualitygate.agentvalidate.AgentValidateStrategy
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildOpus55Strategy
import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStrategy
import skillbill.engine.featuretask.slot.skeleton.SkeletonStrategyBindings
import skillbill.engine.featuretask.slot.standalonereview.DelegatedStandaloneReviewOpus55Strategy
import skillbill.engine.featuretask.slot.standalonereview.DelegatedStandaloneReviewStrategy
import skillbill.engine.featuretask.slot.standalonereview.InlineStandaloneReviewOpus55Strategy
import skillbill.engine.featuretask.slot.standalonereview.InlineStandaloneReviewStrategy
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.featuretask.slot.writehistory.BoundaryHistoryOpus55Strategy
import skillbill.engine.featuretask.slot.writehistory.BoundaryHistoryStrategy
import skillbill.engine.goalrunner.intake.testPlanIntake
import skillbill.error.featuretask.CorruptFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.InvalidPhaseStrategyCompositionError
import skillbill.error.featuretask.MissingFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.UnsupportedFeatureTaskRuntimeExecutionPlanError
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeExecutionPlanSchemaValidator
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.concurrency.SequentialBoundedWorkFanOutPort
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.OPUS_55_STRATEGY_ID_SUFFIX
import skillbill.workflow.taskruntime.model.skeleton.PhaseModelProfile
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
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_INTAKE
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR_FIX
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PRESENT_FINDINGS
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
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

  private fun productionRegistry(): PhaseStrategyRegistry =
    PhaseStrategyRegistry(productionStrategies().map { PhaseStrategyRegistration(it, runner) })

  private fun productionStrategies(): List<PhaseStrategy> {
    val database =
      sqliteSessionFactoryForTests(
        userHome = home,
        dbPathOverride = home.resolve("metrics.db").toString(),
        environment = emptyMap(),
      )
    val fanOut = PlanFanOutStrategy(SequentialBoundedWorkFanOutPort, 1)
    val standaloneMonitor = StandaloneMonitorStrategy(UnavailableStandaloneMonitorService.service)
    return strategies +
      listOf(
        standaloneMonitor,
        StandaloneMonitorOpus55Strategy(standaloneMonitor),
        StandaloneMonitorPublicationStrategy(UnavailableStandaloneMonitorService.service),
        DelegatedReviewStrategy(runner, scriptedDelegatedReviewRunner(database, home, LaneScript())),
        DelegatedReviewOpus55Strategy(runner, scriptedDelegatedReviewRunner(database, home, LaneScript())),
        DelegatedStandaloneReviewStrategy(runner, scriptedDelegatedReviewRunner(database, home, LaneScript())),
        DelegatedStandaloneReviewOpus55Strategy(runner, scriptedDelegatedReviewRunner(database, home, LaneScript())),
        fanOut,
        PlanFanOutOpus55Strategy(fanOut),
        QaStrategy(
          unavailableQaReportSettlement(),
          testQaExecutionLifecycle(NoopWorkflowGitOperations),
          unusedQaOutcomeRecorder(),
        ),
      )
  }

  private val runner =
    object : PhaseRunner {
      override fun run(
        input: PhaseStepInput,
        state: PhaseLaunchState,
      ): PhaseStepOutput = error("Policy lookups must not launch a step.")
    }

  private val prDescriptionStrategy =
    PrDescriptionStrategy(
      UnavailablePullRequestIdentityLookup,
      LocalPullRequestTemplateFiles,
    )

  private val monitorStrategy =
    MonitorStrategy(
      OpenPullRequestIdentityLookup(NoopWorkflowGitOperations),
      PassingPullRequestChecksLookup,
    )

  private val strategies =
    listOf(
      RuntimeIntakeStrategy { testPlanIntake() },
      AgentPreplanStrategy(),
      AgentPreplanOpus55Strategy(),
      AgentPlanStrategy(),
      AgentPlanOpus55Strategy(),
      ImplementThenSimplifyStrategy(),
      ImplementThenSimplifyOpus55Strategy(),
      AcceptanceAuditStrategy(),
      AcceptanceAuditOpus55Strategy(),
      InlineReviewStrategy(runner),
      InlineReviewOpus55Strategy(runner),
      InlineStandaloneReviewStrategy(runner),
      InlineStandaloneReviewOpus55Strategy(runner),
      PackBuildStrategy(),
      PackBuildOpus55Strategy(),
      AgentValidateStrategy(),
      AgentValidateOpus55Strategy(),
      BoundaryHistoryStrategy(),
      BoundaryHistoryOpus55Strategy(),
      RuntimeCommitStrategy(),
      prDescriptionStrategy,
      PrDescriptionOpus55Strategy(prDescriptionStrategy),
      monitorStrategy,
      MonitorOpus55Strategy(monitorStrategy),
    )

  @Test
  fun `strategies keep audit inspection read only and repairs mutating`() {
    val actual = strategies.flatMap { strategy -> strategy.steps.map { it to strategy.policyFor(it) } }.toMap()

    assertEquals(EXPECTED_POLICIES, actual)
  }

  @Test
  fun `every implement strategy tells implement how to return a no-change claim`() {
    listOf(ImplementThenSimplifyStrategy(), ImplementThenSimplifyOpus55Strategy()).forEach { strategy ->
      val implement =
        strategy.promptSections(
          "implement",
          FeatureTaskRuntimePhasePromptComposeInputs(PROMPT_COMPOSER_ISSUE_KEY, promptComposerBriefingFor("implement")),
        )
      val simplify =
        strategy.promptSections(
          "simplify",
          FeatureTaskRuntimePhasePromptComposeInputs(PROMPT_COMPOSER_ISSUE_KEY, promptComposerBriefingFor("simplify")),
        )

      assertTrue(
        implement.taskDirective.contains(ImplementationPromptSections.NO_CHANGE_CLAIM_DIRECTIVE),
        "${strategy.strategyId} implement must carry the no-change claim directive",
      )
      assertTrue(
        !simplify.taskDirective.contains("`no_change` argument"),
        "${strategy.strategyId} simplify must not offer a no-change claim",
      )
    }
  }

  @Test
  fun `every selectable step without a structured contract settles with the uniform output`() {
    val registry = productionRegistry()
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
    val definition = SkeletonDefinition("code-review", listOf(PhaseSlot.CODE_REVIEW))
    val selectedReview = CompositionTestStrategy(PhaseSlot.CODE_REVIEW, "review-only", listOf(PHASE_REVIEW))
    val registry = PhaseStrategyRegistry(listOf(selectedReview))
    val selection =
      PhaseStrategySelection(
        registry,
        mapOf(definition to mapOf(PhaseSlot.CODE_REVIEW to PhaseStrategyBinding.Fixed("review-only"))),
      )
    val lookup = PhaseStrategyLookup(registry, selection)
    val plan = lookup.executionPlan(PhaseStrategySelectionFacts(definition, emptySet()))

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
        if (definition.slots.any {
            it == PhaseSlot.CODE_REVIEW || it == PhaseSlot.STANDALONE_REVIEW
          }
        ) {
          CodeReviewExecutionMode.entries.map { it }
        } else {
          listOf(null)
        }
      val qualityGates: List<FeatureTaskRuntimeQualityGateSelection?> =
        if (definition.id == SkeletonDefinition.GOAL_CHILD.id) {
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
                if (PhaseSlot.QA in definition.slots) add(FeatureTaskRuntimeQaSelection.AUTO)
              },
            )
          assertSelectedComposition(registry, lookup, facts)
        }
      }
    }
  }

  @Test
  fun `the internal standalone monitor binds its own strategies and leaves the legacy monitor bindings alone`() {
    val registry = productionRegistry()
    val lookup = PhaseStrategyLookup(registry, PhaseStrategySelection(registry, SkeletonStrategyBindings.bindings))
    val standalone =
      lookup.executionPlan(PhaseStrategySelectionFacts(SkeletonDefinition.STANDALONE_MONITOR, emptySet()))
    val legacy = lookup.executionPlan(PhaseStrategySelectionFacts(SkeletonDefinition.MONITOR, emptySet()))

    assertEquals(StandaloneMonitorStrategy.ID, lookup.strategyFor(PHASE_MONITOR, standalone).strategyId)
    assertEquals(StandaloneMonitorStrategy.ID, lookup.strategyFor(PHASE_MONITOR_FIX, standalone).strategyId)
    assertEquals(StandaloneMonitorPublicationStrategy.ID, lookup.strategyFor(PHASE_COMMIT_PUSH, standalone).strategyId)
    assertEquals(MonitorStrategy.ID, lookup.strategyFor(PHASE_MONITOR, legacy).strategyId)
    assertEquals(RuntimeCommitStrategy.ID, lookup.strategyFor(PHASE_COMMIT_PUSH, legacy).strategyId)
    assertEquals(
      setOf(StandaloneMonitorStrategy.ID, StandaloneMonitorOpus55Strategy.ID),
      SkeletonStrategyBindings.bindings
        .getValue(SkeletonDefinition.STANDALONE_MONITOR)
        .getValue(PhaseSlot.MONITOR)
        .strategyIds,
    )
    assertEquals(
      setOf(MonitorStrategy.ID, MonitorOpus55Strategy.ID),
      SkeletonStrategyBindings.bindings.getValue(SkeletonDefinition.MONITOR).getValue(PhaseSlot.MONITOR).strategyIds,
    )
  }

  @Test
  fun `resolved compositions round trip through the production codec without losing traversal or policy semantics`() {
    val registry = productionRegistry()
    val lookup = PhaseStrategyLookup(registry, PhaseStrategySelection(registry, SkeletonStrategyBindings.bindings))
    val codec = FeatureTaskRuntimeExecutionPlanCodec(FeatureTaskRuntimeExecutionPlanSchemaValidator())
    val compatibility = FeatureTaskRuntimeExecutionPlanCompatibility(codec, lookup)
    SkeletonDefinition.entries.forEach { definition ->
      val gates =
        if (definition.id == SkeletonDefinition.GOAL_CHILD.id) {
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
                if (definition.slots.any {
                    it == PhaseSlot.CODE_REVIEW || it == PhaseSlot.STANDALONE_REVIEW
                  }
                ) {
                  add(CodeReviewExecutionMode.INLINE)
                }
                gate?.let(::add)
                if (PhaseSlot.QA in definition.slots) add(FeatureTaskRuntimeQaSelection.AUTO)
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
          setOf(
            CodeReviewExecutionMode.INLINE,
            FeatureTaskRuntimeQualityGateSelection.BUILD,
          ),
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
    val replacement = AgentValidateStrategy()
    val freshRegistry = PhaseStrategyRegistry(listOf(PhaseStrategyRegistration(replacement, runner)))
    val freshLookup =
      PhaseStrategyLookup(
        freshRegistry,
        PhaseStrategySelection(
          freshRegistry,
          mapOf(
            SkeletonDefinition.VALIDATION to
              mapOf(
                PhaseSlot.QUALITY_GATE to PhaseStrategyBinding.Fixed(AgentValidateStrategy.ID),
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
        setOf(
          CodeReviewExecutionMode.INLINE,
          FeatureTaskRuntimeQualityGateSelection.VALIDATE,
        ),
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

  @Test
  fun `every opus wrapper forwards each open strategy member to its canonical instance`() {
    val wrappers = productionStrategies().filter { it.strategyId.endsWith(OPUS_55_STRATEGY_ID_SUFFIX) }
    val members = openStrategyMembers()
    assertTrue(wrappers.isNotEmpty())
    assertEquals(
      FORWARDING_RULES.keys,
      members.map { it.memberName() }.toSet(),
      "Every open PhaseStrategy member needs exactly one forwarding classification.",
    )
    wrappers.forEach { wrapper ->
      val canonical = composedCanonical(wrapper) ?: return@forEach
      members
        .filter { it.declaringClass.isInstance(wrapper) && it.declaringClass.isInstance(canonical) }
        .forEach { assertForwarded(wrapper, canonical, it) }
    }
  }

  private fun openStrategyMembers(): List<Method> =
    listOf(PhaseStrategy::class.java, PhaseStrategyStatusProjection::class.java)
      .flatMap { it.declaredMethods.asList() }
      .filter {
        !it.isSynthetic && !Modifier.isStatic(it.modifiers) && !Modifier.isPrivate(it.modifiers) &&
          !Modifier.isFinal(it.modifiers)
      }

  private fun composedCanonical(wrapper: PhaseStrategy): PhaseStrategy? {
    val fields = wrapper.javaClass.declaredFields.filter { PhaseStrategy::class.java.isAssignableFrom(it.type) }
    if (fields.isEmpty()) {
      val parent = wrapper.javaClass.superclass
      assertTrue(
        parent != PhaseStrategy::class.java && parent != PhaseStrategyStatusProjection::class.java &&
          !Modifier.isAbstract(parent.modifiers),
        "${wrapper.strategyId} has neither a composed canonical instance nor a concrete canonical parent.",
      )
      return null
    }
    val field = fields.single()
    field.isAccessible = true
    return field.get(wrapper) as PhaseStrategy
  }

  private fun assertForwarded(
    wrapper: PhaseStrategy,
    canonical: PhaseStrategy,
    member: Method,
  ) {
    member.isAccessible = true
    val steps = (canonical.steps + wrapper.steps).distinct()
    val label = "${wrapper.strategyId}.${member.memberName()}"
    when (FORWARDING_RULES.getValue(member.memberName())) {
      Forwarding.EQUAL -> assertEquals(member.call(canonical), member.call(wrapper), label)
      Forwarding.SAME -> assertSameOutcome(member.call(canonical), member.call(wrapper), label)
      Forwarding.STEP_EQUAL ->
        steps.forEach { assertEquals(member.call(canonical, it), member.call(wrapper, it), "$label($it)") }
      Forwarding.STEP_SAME ->
        steps.forEach { assertSameOutcome(member.call(canonical, it), member.call(wrapper, it), "$label($it)") }
      Forwarding.RECORDED_IDENTITY -> assertRecordedIdentity(wrapper, canonical, member, steps)
      Forwarding.PROMPT -> steps.forEach { assertPromptSupplement(wrapper, canonical, it) }
      Forwarding.VERDICT -> steps.forEach { assertVerdictEquivalent(wrapper, canonical, member, it) }
      Forwarding.OVERRIDDEN ->
        if (declaresMember(canonical, member)) {
          assertTrue(declaresMember(wrapper, member), "$label must forward to its canonical instance.")
        }
    }
  }

  private fun assertRecordedIdentity(
    wrapper: PhaseStrategy,
    canonical: PhaseStrategy,
    member: Method,
    steps: List<String>,
  ) {
    val recorded = canonical.strategyId + OPUS_55_STRATEGY_ID_SUFFIX
    when (member.memberName()) {
      "getStrategyId" -> assertEquals(recorded, wrapper.strategyId)
      "stepPolicyIdentity" ->
        steps.forEach {
          assertEquals(
            canonical.policyFor(it).semanticIdentity(recorded, canonical.semanticRevision, it),
            wrapper.stepPolicyIdentity(it),
          )
        }
      "resumeInterpretationIdentity" ->
        steps.forEach {
          assertEquals(
            canonical.resumeInterpretationIdentity(it).replace(canonical.strategyId, recorded),
            wrapper.resumeInterpretationIdentity(it),
          )
        }
      else ->
        assertEquals(
          canonical.acceptsAttemptStrategy(canonical.strategyId),
          wrapper.acceptsAttemptStrategy(recorded),
        )
    }
  }

  private fun assertPromptSupplement(
    wrapper: PhaseStrategy,
    canonical: PhaseStrategy,
    step: String,
  ) {
    val canonicalInputs =
      FeatureTaskRuntimePhasePromptComposeInputs(PROMPT_COMPOSER_ISSUE_KEY, promptComposerBriefingFor(step))
    val opusInputs = canonicalInputs.copy(stepProfile = PhaseModelProfile.OPUS_5_5)
    val base = attempt { canonical.promptSections(step, opusInputs) }
    if (base !is PhaseStepPromptSections) {
      assertEquals(base, attempt { wrapper.promptSections(step, opusInputs) })
      return
    }
    assertEquals(
      canonical.promptSections(step, canonicalInputs).withoutRewrite(),
      wrapper.promptSections(step, canonicalInputs).withoutRewrite(),
    )
    val opus = wrapper.promptSections(step, opusInputs)
    if (wrapper.strategyId in UNSUPPLEMENTED_PROMPT_WRAPPERS) {
      assertEquals(base.withoutRewrite(), opus.withoutRewrite(), "${wrapper.strategyId}($step) adds no supplement")
      return
    }
    assertTrue(opus.taskDirective.startsWith(base.taskDirective), "${wrapper.strategyId}($step) keeps canonical text")
    assertTrue(opus.taskDirective.length > base.taskDirective.length, "${wrapper.strategyId}($step) adds a supplement")
    val withoutSupplement =
      opus.copy(
        taskDirective = base.taskDirective,
        retryFocus = base.retryFocus,
        outputContract = base.outputContract,
      )
    assertEquals(base.withoutRewrite(), withoutSupplement.withoutRewrite())
  }

  private fun assertVerdictEquivalent(
    wrapper: PhaseStrategy,
    canonical: PhaseStrategy,
    member: Method,
    step: String,
  ) {
    val canonicalDiagnostics = RecordingDiagnostics()
    val wrapperDiagnostics = RecordingDiagnostics()
    val expected = member.call(canonical, step, canonicalDiagnostics)
    val actual = member.call(wrapper, step, wrapperDiagnostics)
    if (expected !is FeatureTaskRuntimeStepVerdictRule || actual !is FeatureTaskRuntimeStepVerdictRule) {
      assertEquals(expected, actual)
      return
    }
    if (member.call(canonical, step, canonicalDiagnostics) === expected) {
      assertSame(expected, actual)
      return
    }
    VERDICT_INPUTS.forEach { (verdict, output) ->
      assertEquals(attempt { expected.verdictFor(verdict, output) }, attempt { actual.verdictFor(verdict, output) })
    }
    assertEquals(canonicalDiagnostics.events, wrapperDiagnostics.events)
  }

  private fun declaresMember(
    strategy: PhaseStrategy,
    member: Method,
  ): Boolean = strategy.javaClass.getMethod(member.name, *member.parameterTypes).declaringClass != member.declaringClass

  private fun assertSameOutcome(
    expected: Any?,
    actual: Any?,
    label: String,
  ) {
    if (expected is Thrown) assertEquals(expected, actual, label) else assertSame(expected, actual, label)
  }

  private fun Method.memberName(): String = name.substringBefore('$')

  private fun Method.call(
    target: Any,
    vararg args: Any?,
  ): Any? =
    try {
      invoke(target, *args)
    } catch (failure: InvocationTargetException) {
      Thrown(failure.targetException.javaClass, failure.targetException.message)
    }

  private fun attempt(block: () -> Any?): Any? =
    try {
      block()
    } catch (failure: RuntimeException) {
      Thrown(failure.javaClass, failure.message)
    }

  private fun PhaseStepPromptSections.withoutRewrite(): PhaseStepPromptSections = copy(briefingRewrite = null)

  private data class Thrown(
    val type: Class<*>,
    val message: String?,
  )

  private class RecordingDiagnostics : RuntimeDiagnostics {
    val events = mutableListOf<String>()

    override fun warning(
      message: String,
      error: Throwable?,
    ) {
      events += "warning:$message:${error?.javaClass}"
    }

    override fun error(
      message: String,
      error: Throwable?,
    ) {
      events += "error:$message:${error?.javaClass}"
    }

    override fun info(message: String) {
      events += "info:$message"
    }
  }

  private enum class Forwarding {
    EQUAL,
    SAME,
    STEP_EQUAL,
    STEP_SAME,
    RECORDED_IDENTITY,
    PROMPT,
    VERDICT,
    OVERRIDDEN,
  }

  private companion object {
    val UNSUPPLEMENTED_PROMPT_WRAPPERS = setOf(MonitorOpus55Strategy.ID, StandaloneMonitorOpus55Strategy.ID)

    val FORWARDING_RULES =
      mapOf(
        "getSlot" to Forwarding.EQUAL,
        "getSemanticRevision" to Forwarding.EQUAL,
        "getPlansInFanOut" to Forwarding.EQUAL,
        "getAcceptanceRepairSteps" to Forwarding.EQUAL,
        "getMonitorRepairSteps" to Forwarding.EQUAL,
        "getQualityGateOperation" to Forwarding.EQUAL,
        "getSteps" to Forwarding.EQUAL,
        "getOptionalSteps" to Forwarding.EQUAL,
        "getEntryStep" to Forwarding.EQUAL,
        "getLoopRules" to Forwarding.SAME,
        "policyFor" to Forwarding.STEP_EQUAL,
        "directiveFor" to Forwarding.STEP_EQUAL,
        "executionBindingKind" to Forwarding.STEP_EQUAL,
        "briefingInvariantFields" to Forwarding.STEP_EQUAL,
        "reportedGate" to Forwarding.STEP_EQUAL,
        "stepHooks" to Forwarding.STEP_SAME,
        "resumeRules" to Forwarding.STEP_SAME,
        "getStrategyId" to Forwarding.RECORDED_IDENTITY,
        "stepPolicyIdentity" to Forwarding.RECORDED_IDENTITY,
        "resumeInterpretationIdentity" to Forwarding.RECORDED_IDENTITY,
        "acceptsAttemptStrategy" to Forwarding.RECORDED_IDENTITY,
        "promptSections" to Forwarding.PROMPT,
        "verdictRule" to Forwarding.VERDICT,
        "runStep" to Forwarding.OVERRIDDEN,
        "withPreparedRun" to Forwarding.OVERRIDDEN,
        "mapRecordedExecutionPlan" to Forwarding.OVERRIDDEN,
        "currentExecution" to Forwarding.OVERRIDDEN,
      )

    val VERDICT_INPUTS: List<Pair<FeatureTaskRuntimeVerdict?, FeatureTaskRuntimeWorkflowArtifactMap?>> =
      listOf(
        null,
        FeatureTaskRuntimeVerdict.SATISFIED,
        FeatureTaskRuntimeVerdict.NO_CHANGE_CONFIRMED,
        FeatureTaskRuntimeVerdict.NO_CHANGE_REJECTED,
        FeatureTaskRuntimeVerdict.UNSATISFIED,
        FeatureTaskRuntimeVerdict.APPROVED,
        FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      ).flatMap { verdict ->
        listOf(null, FeatureTaskRuntimeWorkflowArtifactMap.from(emptyMap<String, Any?>())).map { verdict to it }
      }

    val EXPECTED_POLICIES =
      mapOf(
        PHASE_INTAKE to policy(),
        PHASE_PREPLAN to policy(),
        PHASE_PLAN to policy(),
        PHASE_IMPLEMENT to policy(MUTATING, FILE_MUTATING).extendingInventory(),
        PHASE_SIMPLIFY to
          policy(MUTATING, SINGLE, FILE_MUTATING).extendingInventory(),
        PHASE_AUDIT to policy(SINGLE, READ_ONLY_IDLE),
        PHASE_AUDIT_PLAN_FIX to policy(SINGLE, READ_ONLY_IDLE),
        PHASE_AUDIT_IMPLEMENT_FIX to policy(MUTATING, FILE_MUTATING).extendingInventory(),
        PHASE_REVIEW to policy(FILE_MUTATING, GENERATION_SCOPED),
        PHASE_PRESENT_FINDINGS to policy(SINGLE, READ_ONLY_IDLE),
        PHASE_VERIFY_FINDINGS to policy(READ_ONLY_IDLE, FILE_MUTATING),
        PHASE_IMPLEMENT_FIX to
          policy(MUTATING, FILE_MUTATING, GENERATION_SCOPED).extendingInventory(),
        PHASE_BUILD to policy(FILE_MUTATING),
        PHASE_VALIDATE to
          policy(SINGLE, FILE_MUTATING).extendingInventory(),
        PHASE_WRITE_HISTORY to policy(FILE_MUTATING).extendingInventory(),
        PHASE_COMMIT_PUSH to policy(FILE_MUTATING),
        PHASE_PR to policy(FILE_MUTATING),
        PHASE_MONITOR to policy(),
        PHASE_MONITOR_FIX to policy(MUTATING, FILE_MUTATING).extendingInventory(),
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
      when {
        qualityGate == FeatureTaskRuntimeQualityGateSelection.BUILD -> PHASE_VALIDATE
        definition == SkeletonDefinition.GOAL_QA -> null
        else -> PHASE_BUILD
      }
    val expectedSteps = definition.stepIds - setOfNotNull(excludedGate)
    assertEquals(expectedSteps.toSet(), plan.selectedStepIds, definition.id)
    assertEquals(expectedSteps, plan.traversal.forwardPhaseIds, definition.id)
    assertEquals(definition.slots, plan.selectedSlots, definition.id)
    if (definition == SkeletonDefinition.STANDALONE || definition.id == SkeletonDefinition.GOAL_CHILD.id) {
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
        lookup.strategyFor(PHASE_PRESENT_FINDINGS, plan).strategyId,
      )
    }
    if (PhaseSlot.PLAN in definition.slots) {
      assertEquals(PlanFanOutStrategy.ID, lookup.strategyFor(PHASE_PLAN, plan).strategyId)
      assertEquals(PlanFanOutStrategy.ID, lookup.strategyFor(PHASE_PLAN_SUBTASKS, plan).strategyId)
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
      definition == SkeletonDefinition.VALIDATION -> AgentValidateStrategy.ID
      definition == SkeletonDefinition.GOAL_QA -> PackBuildStrategy.ID
      qualityGate == FeatureTaskRuntimeQualityGateSelection.BUILD -> PackBuildStrategy.ID
      else -> AgentValidateStrategy.ID
    }
}
