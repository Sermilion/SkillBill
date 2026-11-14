package skillbill.engine.featuretask.slot

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.contracts.config.projectchecks.ProjectCheckConfig
import skillbill.contracts.workflow.identity.task.FEATURE_TASK_RUNTIME_EXECUTION_PLAN_PREVIOUS_CONTRACT_VERSION
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.engine.featuretask.model.execution.FeatureTaskRuntimeExecutionPlanCreationRequest
import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.runner.InMemoryRuntimeWorkflowRepository
import skillbill.engine.featuretask.validation.ValidationGateResolver
import skillbill.engine.featuretask.validation.kotlinPackWithoutGate
import skillbill.engine.featuretask.validation.projectchecks.committedProjectCheckConfigForTests
import skillbill.engine.featuretask.validation.projectchecks.projectCheckCommandResolverForTests
import skillbill.engine.featuretask.validation.repoLocalConfig
import skillbill.engine.featuretask.validation.reviewFallbackPackWithoutGate
import skillbill.engine.featuretask.validation.validationGateTestDeclaration
import skillbill.engine.featuretask.validation.validationGateTestRepoRoot
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.shellcontent.ManifestFailureCode
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.scaffold.model.RoutingSignals
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class FeatureTaskRuntimeExecutionPlanResolverTest {
  @Test
  fun `creation binds routed pack wrapper command family and timeout before accepting a durable descriptor`() {
    val fixture = Fixture()
    val resolver = fixture.resolver()
    val inputs =
      resolver.resolveInputs(
        root,
        FeatureTaskRuntimeQualityGateSelection.BUILD,
        ValidationDepth.FULL,
        7.minutes,
      )
    val descriptor = resolver.resolveCreation(fixture.creationRequest())
    val plan =
      fixture.execution.compatibility.requireSupportedExecution(
        fixture.execution.validator.write(descriptor.artifactValue, "created descriptor"),
        fixture.boundInputs(inputs, descriptor),
      )

    assertEquals("kotlin", inputs.packSlug)
    assertEquals("runtime/gradlew", inputs.gradleWrapper)
    assertEquals(ValidationGateCommandFamily.BUILD, inputs.commandFamily)
    assertEquals(420000L, inputs.phaseTimeoutMillis)
    assertTrue("build" in plan.selectedStepIds)
    assertFalse("validate" in plan.selectedStepIds)
    assertEquals(SkeletonDefinition.GOAL_CHILD.id, plan.definitionId)
    assertEquals(root, fixture.inventoryRoot)
    assertEquals(0, fixture.execution.launches)

    val changed =
      fixture.resolver("other/gradlew").resolveInputs(
        root,
        FeatureTaskRuntimeQualityGateSelection.BUILD,
        ValidationDepth.FULL,
        7.minutes,
      )
    fixture.execution.compatibility.requireSupportedExecution(
      fixture.execution.validator.write(descriptor.artifactValue, "created descriptor"),
      changed,
    )
    val validation =
      resolver.resolveCreation(
        FeatureTaskRuntimeExecutionPlanCreationRequest(
          root,
          SkeletonDefinition.STANDALONE,
          CodeReviewExecutionMode.INLINE,
          null,
          ValidationDepth.FULL,
          7.minutes,
          issueKey = "SKILL-384",
          invokedAgentId = "claude",
        ),
      )
    assertNotEquals(descriptor.artifactValue, validation.artifactValue)
  }

  @Test
  fun `explicit resume settings changes identify the field and preserve the recorded descriptor`() {
    val fixture = Fixture()
    val resolver = fixture.resolver()
    val descriptor = fixture.create()
    fixture.execution.seed(fixture.states, "wftr-settings", descriptor = descriptor.artifactValue)
    val before = fixture.states.getFeatureTaskWorkflow("wftr-settings")

    val error =
      assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> {
        resolver.resolveInputs(
          root,
          FeatureTaskRuntimeQualityGateSelection.BUILD,
          ValidationDepth.FULL,
          30.minutes,
          "wftr-settings",
        )
      }

    assertContains(error.message.orEmpty(), "phase_timeout_millis recorded=420000 requested=1800000")
    assertEquals(before, fixture.states.getFeatureTaskWorkflow("wftr-settings"))
    assertEquals(0, fixture.execution.launches)
  }

  @Test
  fun `creation refuses unknown routing and missing build commands before implementation`() {
    val fixture = Fixture()
    fixture.tracked = WorkflowGitNameListResult.Failed("inventory unavailable")
    assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> { fixture.create() }
    fixture.tracked = WorkflowGitNameListResult.Listed(listOf("runtime-kotlin/Main.kt", "ios/Main.swift"))
    fixture.packs = fixture.packs +
      fixture.packs.single().copy(
        slug = "ios",
        routingSignals = RoutingSignals(listOf("*.swift"), emptyList(), listOf("*.swift")),
      )
    assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> { fixture.create() }
    fixture.tracked = WorkflowGitNameListResult.Listed(listOf("runtime-kotlin/Main.kt"))
    fixture.packs = listOf(kotlinPackWithoutGate())
    assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> { fixture.create() }
    fixture.packs = fixture.packs.map { it.copy(validationGate = validationGateTestDeclaration) }
    assertFailsWith<SkillBillRuntimeException> {
      fixture.resolver(
        committed =
          committedProjectCheckConfigForTests(
            knownEmptyBuild = true,
            validationArgv = validationGateTestDeclaration.fullGateCommand,
          ),
      ).resolveCreation(fixture.creationRequest())
    }.also { failure ->
      assertEquals(ManifestFailureCode.MANIFEST_FAILURE, failure.code)
    }
    assertEquals(0, fixture.execution.launches)
  }

  @Test
  fun `goal validation routes the whole branch even when changed files belong to another pack`() {
    val fixture = Fixture()
    fixture.packs = fixture.packs + reviewFallbackPackWithoutGate() +
      fixture.packs.single().copy(
        slug = "ios",
        routingSignals = RoutingSignals(listOf("*.swift"), emptyList(), listOf("*.swift")),
      )
    fixture.tracked =
      WorkflowGitNameListResult.Listed(
        listOf("runtime-kotlin/Main.kt", "runtime-kotlin/Other.kt", "ios/App.swift", "README.md"),
      )

    listOf(emptyList(), listOf("ios/App.swift")).forEach { owned ->
      fixture.inventory = WorkflowGitNameListResult.Listed(owned)

      val inputs =
        fixture.resolver().resolveInputs(
          root,
          FeatureTaskRuntimeQualityGateSelection.BUILD,
          ValidationDepth.FULL,
          7.minutes,
        )

      assertEquals("kotlin", inputs.packSlug)
      assertEquals(listOf("./gradlew", "compileKotlin"), inputs.declaration?.buildCommand)
    }
    fixture.inventory = WorkflowGitNameListResult.Listed(emptyList())
    fixture.tracked = WorkflowGitNameListResult.Failed("ls-files unavailable")
    assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> { fixture.create() }
  }

  @Test
  fun `creation records agent-validate when the installed catalog is only the review fallback`() {
    val fixture = Fixture()
    fixture.packs = listOf(reviewFallbackPackWithoutGate())
    fixture.inventory = WorkflowGitNameListResult.Listed(emptyList())
    fixture.tracked = WorkflowGitNameListResult.Listed(listOf("runtime-kotlin/Main.kt"))
    val resolver =
      fixture.resolver(
        committed = committedProjectCheckConfigForTests(buildArgv = null),
      )
    val inputs =
      resolver.resolveInputs(
        root,
        FeatureTaskRuntimeQualityGateSelection.VALIDATE,
        ValidationDepth.FULL,
        7.minutes,
      )
    val descriptor =
      resolver.resolveCreation(
        fixture.creationRequest(FeatureTaskRuntimeQualityGateSelection.VALIDATE),
      )
    val plan =
      fixture.execution.compatibility.requireSupportedExecution(
        fixture.execution.validator.write(descriptor.artifactValue, "created descriptor"),
        fixture.boundInputs(inputs, descriptor),
      )

    assertEquals(null, inputs.packSlug)
    assertEquals(null, inputs.declaration)
    assertEquals(ValidationGateCommandFamily.VALIDATION, inputs.commandFamily)
    assertTrue("validate" in plan.selectedStepIds)
    assertFalse("build" in plan.selectedStepIds)
  }

  @Test
  fun `resume uses committed project checks when pack commands change`() {
    val fixture = Fixture()
    val resolver = fixture.resolver()
    val descriptor = fixture.create()
    fixture.execution.seed(fixture.states, "wftr-clean", descriptor = descriptor.artifactValue)
    fixture.inventory = WorkflowGitNameListResult.Listed(emptyList())
    val clean =
      resolver.resolveInputs(
        root,
        FeatureTaskRuntimeQualityGateSelection.BUILD,
        ValidationDepth.FULL,
        7.minutes,
        "wftr-clean",
      )
    assertEquals("kotlin", clean.packSlug)
    val admitted = fixture.execution.admission.admit(fixture.states, "wftr-clean", clean)
    assertEquals(SkeletonDefinition.GOAL_CHILD.id, admitted.plan.definitionId)
    assertEquals(
      descriptor,
      resolver.resolveCreation(
        FeatureTaskRuntimeExecutionPlanCreationRequest(
          root,
          SkeletonDefinition.GOAL_CHILD,
          CodeReviewExecutionMode.INLINE,
          FeatureTaskRuntimeQualityGateSelection.BUILD,
          ValidationDepth.FULL,
          7.minutes,
          "wftr-clean",
        ),
      ),
    )
    fixture.inventory = WorkflowGitNameListResult.Listed(listOf("ios/New.swift"))
    val rerouted =
      resolver.resolveInputs(
        root,
        FeatureTaskRuntimeQualityGateSelection.BUILD,
        ValidationDepth.FULL,
        7.minutes,
        "wftr-clean",
      )
    assertEquals(clean.packSlug, rerouted.packSlug)
    assertEquals(clean.commandFamily, rerouted.commandFamily)
    assertEquals(clean.phaseTimeoutMillis, rerouted.phaseTimeoutMillis)
    assertEquals(
      clean.projectCheckSnapshot?.buildCommandList?.map { it.argv },
      rerouted.projectCheckSnapshot?.buildCommandList?.map { it.argv },
    )
    fixture.packs =
      fixture.packs.map { pack ->
        pack.copy(validationGate = requireNotNull(pack.validationGate).copy(buildCommand = listOf("other-build")))
      }
    val drifted =
      resolver.resolveInputs(
        root,
        FeatureTaskRuntimeQualityGateSelection.BUILD,
        ValidationDepth.FULL,
        7.minutes,
        "wftr-clean",
      )
    assertEquals(
      clean.projectCheckSnapshot?.buildCommandList?.map { it.argv },
      drifted.projectCheckSnapshot?.buildCommandList?.map { it.argv },
    )
    assertEquals(
      listOf("./gradlew", "compileKotlin"),
      drifted.projectCheckSnapshot?.buildCommands?.commands?.single()?.argv,
    )
    assertEquals(0, fixture.execution.launches)
  }

  @Test
  fun `resume adopts updated project checks without rewriting saved records`() {
    val fixture = Fixture()
    val descriptor = fixture.create()
    fixture.execution.seed(fixture.states, "wftr-clean", descriptor = descriptor.artifactValue)
    val before = fixture.states.getFeatureTaskWorkflow("wftr-clean")
    val changedConfig =
      fixture.resolver(
        committed = committedProjectCheckConfigForTests(buildArgv = listOf("./gradlew", "newBuild")),
      )
    repeat(2) {
      val current =
        changedConfig.resolveInputs(
          root,
          FeatureTaskRuntimeQualityGateSelection.BUILD,
          ValidationDepth.FULL,
          7.minutes,
          "wftr-clean",
        )
      val admittedCurrent = fixture.execution.admission.admit(fixture.states, "wftr-clean", current)
      assertEquals(
        listOf("./gradlew", "newBuild"),
        admittedCurrent.plan.projectCheckCommands?.buildCommandList?.single()?.argv,
      )
      assertEquals(before, fixture.states.getFeatureTaskWorkflow("wftr-clean"))
    }
    assertEquals(0, fixture.execution.launches)
  }

  @Test
  fun `legacy resume uses current validation commands and preserves the original workflow on repeated admission`() {
    val fixture = Fixture()
    fixture.packs = fixture.packs + reviewFallbackPackWithoutGate()
    val original =
      fixture.execution.codec.encodeExecution(
        fixture.execution.plan
          .withProjectCheckCommands(null)
          .withContractVersion(FEATURE_TASK_RUNTIME_EXECUTION_PLAN_PREVIOUS_CONTRACT_VERSION),
        EffectiveGatePolicyInputs(
          ValidationGateCommandFamily.BUILD,
          "generic",
          null,
          "runtime/gradlew",
          ValidationDepth.FULL,
          420000L,
        ),
      )
    val descriptor = ValidatedFeatureTaskRuntimeExecutionPlan.read(original, fixture.execution.validator)
    fixture.execution.seed(fixture.states, "wftr-fallback", descriptor = descriptor.artifactValue)
    val before = fixture.states.getFeatureTaskWorkflow("wftr-fallback")
    repeat(2) {
      val inputs =
        fixture.resolver().resolveInputs(
          root,
          FeatureTaskRuntimeQualityGateSelection.BUILD,
          ValidationDepth.FULL,
          7.minutes,
          "wftr-fallback",
        )
      val admitted = fixture.execution.admission.admit(fixture.states, "wftr-fallback", inputs)
      assertEquals("kotlin", inputs.packSlug)
      assertEquals(before, fixture.states.getFeatureTaskWorkflow("wftr-fallback"))
      assertEquals(SkeletonDefinition.GOAL_CHILD.id, admitted.plan.definitionId)
    }
    assertEquals(0, fixture.execution.launches)
  }

  @Test
  fun `changed pack gate commands resume planning and goal children without rewriting history`() {
    fun driftedSeed(
      definition: SkeletonDefinition,
    ): Pair<ExecutionPlanAdmissionFixture, InMemoryRuntimeWorkflowRepository> {
      val states = InMemoryRuntimeWorkflowRepository()
      val fixture = ExecutionPlanAdmissionFixture(definition, database = FakeDatabaseSessionFactory(states))
      val current = fixture.inputsFor("gradlew", 420000L)
      val previousGate =
        requireNotNull(current.declaration).let { gate ->
          gate.copy(collectAllFullGateCommand = gate.collectAllFullGateCommand + "--no-build-cache")
        }
      fixture.seed(states, "wftr-drift", descriptor = fixture.descriptor(current.copy(declaration = previousGate)))
      return fixture to states
    }

    val (planning, planStates) = driftedSeed(SkeletonDefinition.PLAN)
    val planBefore = planStates.getFeatureTaskWorkflow("wftr-drift")
    val resumed =
      planning.recoveryResolver(wrapperForRoot = { "gradlew" })
        .resolveInputs(root, null, ValidationDepth.FULL, 7.minutes, "wftr-drift")
    assertEquals(planning.inputsFor("gradlew", 420000L).declaration, resumed.declaration)
    assertEquals(
      SkeletonDefinition.PLAN.id,
      planning.admission.admit(planStates, "wftr-drift", resumed).plan.definitionId,
    )
    assertEquals(planBefore, planStates.getFeatureTaskWorkflow("wftr-drift"))

    val (child, childStates) = driftedSeed(SkeletonDefinition.GOAL_CHILD)
    val resumedChild =
      child.recoveryResolver(wrapperForRoot = { "gradlew" }).resolveInputs(
        root,
        FeatureTaskRuntimeQualityGateSelection.VALIDATE,
        ValidationDepth.FULL,
        7.minutes,
        "wftr-drift",
      )
    assertEquals(
      child.inputsFor("gradlew", 420000L).declaration,
      resumedChild.declaration,
    )
    assertTrue(childStates.getFeatureTaskWorkflow("wftr-drift") != null)
    assertEquals(0, planning.launches + child.launches)
  }

  private class Fixture {
    val states = InMemoryRuntimeWorkflowRepository()
    val database = FakeDatabaseSessionFactory(states)
    val execution =
      ExecutionPlanAdmissionFixture(
        SkeletonDefinition.GOAL_CHILD,
        qualityGate = FeatureTaskRuntimeQualityGateSelection.BUILD,
      )
    var inventory: WorkflowGitNameListResult = WorkflowGitNameListResult.Listed(listOf("runtime-kotlin/Main.kt"))
    var tracked: WorkflowGitNameListResult = WorkflowGitNameListResult.Listed(listOf("runtime-kotlin/Main.kt"))
    var inventoryRoot: Path? = null
    var packs =
      listOf(
        kotlinPackWithoutGate().copy(
          validationGate =
            validationGateTestDeclaration.copy(
              buildCommand = listOf("./gradlew", "compileKotlin"),
              cacheBypassingBuildCommand = listOf("./gradlew", "compileKotlin", "--rerun-tasks"),
            ),
        ),
      )

    fun resolver(
      wrapper: String = "runtime/gradlew",
      committed: ProjectCheckConfig? = committedFromPacks(),
    ) = FeatureTaskRuntimeExecutionPlanResolver(
      execution.strategies,
      execution.codec,
      execution.validator,
      ValidationGateResolver { packs },
      object : WorkflowGitOperations by NoopWorkflowGitOperations {
        override fun repositoryOwnedPaths(repoRoot: Path): WorkflowGitNameListResult {
          return inventory
        }

        override fun trackedPaths(repoRoot: Path): WorkflowGitNameListResult {
          inventoryRoot = repoRoot
          return tracked
        }
      },
      repoLocalConfig(wrapper),
      database,
      execution.compatibility,
      projectCheckCommandResolverForTests(committed = committed),
      diagnostics = NoopRuntimeDiagnostics,
    )

    fun creationRequest(
      qualityGate: FeatureTaskRuntimeQualityGateSelection? = FeatureTaskRuntimeQualityGateSelection.BUILD,
      workflowId: String? = null,
    ) = FeatureTaskRuntimeExecutionPlanCreationRequest(
      root,
      if (qualityGate == null) SkeletonDefinition.STANDALONE else SkeletonDefinition.GOAL_CHILD,
      CodeReviewExecutionMode.INLINE,
      qualityGate,
      ValidationDepth.FULL,
      7.minutes,
      workflowId,
      issueKey = "SKILL-384",
      invokedAgentId = "claude",
    )

    fun boundInputs(
      inputs: EffectiveGatePolicyInputs,
      descriptor: ValidatedFeatureTaskRuntimeExecutionPlan,
    ) = inputs.copy(projectCheckSnapshot = execution.codec.decode(descriptor.encoded()).projectCheckCommands)

    fun committedFromPacks(): ProjectCheckConfig? {
      val gate = packs.singleOrNull()?.validationGate ?: return null
      val build = gate.buildCommand?.takeIf { it.isNotEmpty() }
      val validation = gate.fullGateCommand.takeIf { it.isNotEmpty() }
      if (build == null && validation == null) return null
      return committedProjectCheckConfigForTests(buildArgv = build, validationArgv = validation)
    }

    fun create() = resolver().resolveCreation(creationRequest())
  }

  private companion object {
    val root = validationGateTestRepoRoot
  }
}
