package skillbill.engine.featuretask.slot

import skillbill.engine.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.slot.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.validation.ValidationGateResolver
import skillbill.engine.featuretask.validation.kotlinPackWithoutGate
import skillbill.engine.featuretask.validation.repoLocalConfig
import skillbill.engine.featuretask.validation.validationGateTestDeclaration
import skillbill.engine.featuretask.validation.validationGateTestRepoRoot
import skillbill.engine.featuretask.validation.model.ValidationGateCommandFamily
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.review.context.model.launch.CodeReviewExecutionMode
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
    val inputs = resolver.resolveInputs(root, FeatureTaskRuntimeQualityGateSelection.BUILD, ValidationDepth.FULL, 7.minutes)
    val descriptor = resolver.resolveCreation(root, SkeletonDefinition.GOAL_CHILD, CodeReviewExecutionMode.INLINE,
      FeatureTaskRuntimeQualityGateSelection.BUILD, ValidationDepth.FULL, 7.minutes)
    val plan = fixture.execution.compatibility.requireSupportedExecution(
      fixture.execution.validator.write(descriptor.artifactValue, "created descriptor"), inputs,
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

    val changed = fixture.resolver("other/gradlew").resolveInputs(
      root, FeatureTaskRuntimeQualityGateSelection.BUILD, ValidationDepth.FULL, 7.minutes,
    )
    assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> {
      fixture.execution.compatibility.requireSupportedExecution(
        fixture.execution.validator.write(descriptor.artifactValue, "created descriptor"), changed,
      )
    }
    val validation = resolver.resolveCreation(root, SkeletonDefinition.STANDALONE, CodeReviewExecutionMode.INLINE,
      null, ValidationDepth.FULL, 7.minutes)
    assertNotEquals(descriptor.artifactValue, validation.artifactValue)
  }

  @Test
  fun `creation refuses unreadable inventory ambiguous routing and missing build commands without launching`() {
    val fixture = Fixture()
    fixture.inventory = WorkflowGitNameListResult.Failed("inventory unavailable")
    assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> { fixture.create() }
    fixture.inventory = WorkflowGitNameListResult.Listed(listOf("runtime-kotlin/Main.kt", "ios/Main.swift"))
    fixture.packs = fixture.packs + fixture.packs.single().copy(
      slug = "ios", routingSignals = RoutingSignals(listOf("*.swift"), emptyList(), listOf("*.swift")),
    )
    assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> { fixture.create() }
    fixture.inventory = WorkflowGitNameListResult.Listed(listOf("runtime-kotlin/Main.kt"))
    fixture.packs = listOf(kotlinPackWithoutGate())
    assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> { fixture.create() }
    fixture.packs = fixture.packs.map { it.copy(validationGate = validationGateTestDeclaration) }
    assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> { fixture.create() }
    assertEquals(0, fixture.execution.launches)
  }

  private class Fixture {
    val execution = ExecutionPlanAdmissionFixture()
    var inventory: WorkflowGitNameListResult = WorkflowGitNameListResult.Listed(listOf("runtime-kotlin/Main.kt"))
    var inventoryRoot: Path? = null
    var packs = listOf(kotlinPackWithoutGate().copy(validationGate = validationGateTestDeclaration.copy(
      buildCommand = listOf("./gradlew", "compileKotlin"),
      cacheBypassingBuildCommand = listOf("./gradlew", "compileKotlin", "--rerun-tasks"),
    )))

    fun resolver(wrapper: String = "runtime/gradlew") = FeatureTaskRuntimeExecutionPlanResolver(
      execution.strategies, execution.codec, execution.validator, ValidationGateResolver { packs },
      object : WorkflowGitOperations by NoopWorkflowGitOperations {
        override fun repositoryOwnedPaths(repoRoot: Path): WorkflowGitNameListResult {
          inventoryRoot = repoRoot
          return inventory
        }
      },
      repoLocalConfig(wrapper),
    )

    fun create() = resolver().resolveCreation(root, SkeletonDefinition.GOAL_CHILD, CodeReviewExecutionMode.INLINE,
      FeatureTaskRuntimeQualityGateSelection.BUILD, ValidationDepth.FULL, 7.minutes)
  }

  private companion object {
    val root = validationGateTestRepoRoot
  }
}
