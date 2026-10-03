package skillbill.engine.featuretask.slot

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.engine.featuretask.model.execution.FeatureTaskRuntimeExecutionPlanCreationRequest
import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.runner.InMemoryRuntimeWorkflowRepository
import skillbill.engine.featuretask.validation.ValidationGateResolver
import skillbill.engine.featuretask.validation.kotlinPackWithoutGate
import skillbill.engine.featuretask.validation.repoLocalConfig
import skillbill.engine.featuretask.validation.reviewFallbackPackWithoutGate
import skillbill.engine.featuretask.validation.validationGateTestDeclaration
import skillbill.engine.featuretask.validation.validationGateTestRepoRoot
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.shellcontent.MissingValidationGateError
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
    val descriptor =
      resolver.resolveCreation(
        FeatureTaskRuntimeExecutionPlanCreationRequest(
          root,
          SkeletonDefinition.GOAL_CHILD,
          CodeReviewExecutionMode.INLINE,
          FeatureTaskRuntimeQualityGateSelection.BUILD,
          ValidationDepth.FULL,
          7.minutes,
        ),
      )
    val plan =
      fixture.execution.compatibility.requireSupportedExecution(
        fixture.execution.validator.write(descriptor.artifactValue, "created descriptor"),
        inputs,
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
    assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> {
      fixture.execution.compatibility.requireSupportedExecution(
        fixture.execution.validator.write(descriptor.artifactValue, "created descriptor"),
        changed,
      )
    }
    val validation =
      resolver.resolveCreation(
        FeatureTaskRuntimeExecutionPlanCreationRequest(
          root,
          SkeletonDefinition.STANDALONE,
          CodeReviewExecutionMode.INLINE,
          null,
          ValidationDepth.FULL,
          7.minutes,
        ),
      )
    assertNotEquals(descriptor.artifactValue, validation.artifactValue)
  }

  @Test
  fun `creation refuses unknown routing and missing build commands before implementation`() {
    val fixture = Fixture()
    fixture.inventory = WorkflowGitNameListResult.Failed("inventory unavailable")
    assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> { fixture.create() }
    fixture.inventory = WorkflowGitNameListResult.Listed(listOf("runtime-kotlin/Main.kt", "ios/Main.swift"))
    fixture.packs = fixture.packs +
      fixture.packs.single().copy(
        slug = "ios",
        routingSignals = RoutingSignals(listOf("*.swift"), emptyList(), listOf("*.swift")),
      )
    assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> { fixture.create() }
    fixture.inventory = WorkflowGitNameListResult.Listed(listOf("runtime-kotlin/Main.kt"))
    fixture.packs = listOf(kotlinPackWithoutGate())
    assertFailsWith<MissingValidationGateError> { fixture.create() }
    fixture.packs = fixture.packs.map { it.copy(validationGate = validationGateTestDeclaration) }
    assertFailsWith<MissingValidationGateError> { fixture.create() }
    assertEquals(0, fixture.execution.launches)
  }

  @Test
  fun `goal child creation before implementation binds the repository's pack instead of the review fallback`() {
    val fixture = Fixture()
    fixture.packs = fixture.packs + reviewFallbackPackWithoutGate()
    fixture.tracked = WorkflowGitNameListResult.Listed(listOf("runtime-kotlin/Main.kt", "README.md"))

    listOf(emptyList(), listOf(".feature-specs/SKILL-1-demo/decomposition-manifest.yaml")).forEach { owned ->
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
  fun `clean or differently routed checkout resumes the recorded pack but changed commands are rejected`() {
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
    assertEquals(
      clean,
      resolver.resolveInputs(
        root,
        FeatureTaskRuntimeQualityGateSelection.BUILD,
        ValidationDepth.FULL,
        7.minutes,
        "wftr-clean",
      ),
    )
    fixture.packs =
      fixture.packs.map { pack ->
        pack.copy(validationGate = requireNotNull(pack.validationGate).copy(buildCommand = listOf("other-build")))
      }
    assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> {
      resolver.resolveInputs(
        root,
        FeatureTaskRuntimeQualityGateSelection.BUILD,
        ValidationDepth.FULL,
        7.minutes,
        "wftr-clean",
      )
    }
    assertEquals(0, fixture.execution.launches)
  }

  @Test
  fun `resume refuses a recorded fallback build plan without replacing it with fresh Kotlin routing`() {
    val fixture = Fixture()
    fixture.packs = fixture.packs + reviewFallbackPackWithoutGate()
    val original =
      fixture.execution.codec.encodeExecution(
        fixture.execution.plan,
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
    val error =
      assertFailsWith<MissingValidationGateError> {
        fixture.resolver().resolveCreation(
          FeatureTaskRuntimeExecutionPlanCreationRequest(
            root,
            SkeletonDefinition.GOAL_CHILD,
            CodeReviewExecutionMode.INLINE,
            FeatureTaskRuntimeQualityGateSelection.BUILD,
            ValidationDepth.FULL,
            7.minutes,
            "wftr-fallback",
          ),
        )
      }
    assertTrue(requireNotNull(error.message).contains("Recorded build gate pack 'generic'"))
    assertTrue(requireNotNull(error.message).contains("reviewed semantic mapping"))
    assertEquals(0, fixture.execution.launches)
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
    var tracked: WorkflowGitNameListResult = WorkflowGitNameListResult.Listed(emptyList())
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

    fun resolver(wrapper: String = "runtime/gradlew") =
      FeatureTaskRuntimeExecutionPlanResolver(
        execution.strategies,
        execution.codec,
        execution.validator,
        ValidationGateResolver { packs },
        object : WorkflowGitOperations by NoopWorkflowGitOperations {
          override fun repositoryOwnedPaths(repoRoot: Path): WorkflowGitNameListResult {
            inventoryRoot = repoRoot
            return inventory
          }

          override fun trackedPaths(repoRoot: Path): WorkflowGitNameListResult = tracked
        },
        repoLocalConfig(wrapper),
        database,
        execution.compatibility,
      )

    fun create() =
      resolver().resolveCreation(
        FeatureTaskRuntimeExecutionPlanCreationRequest(
          root,
          SkeletonDefinition.GOAL_CHILD,
          CodeReviewExecutionMode.INLINE,
          FeatureTaskRuntimeQualityGateSelection.BUILD,
          ValidationDepth.FULL,
          7.minutes,
        ),
      )
  }

  private companion object {
    val root = validationGateTestRepoRoot
  }
}
