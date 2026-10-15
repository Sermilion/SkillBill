package skillbill.engine.featuretask.lifecycle.execution

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.engine.featuretask.model.execution.FeatureTaskRuntimeExecutionPlanCreationRequest
import skillbill.engine.featuretask.runner.InMemoryRuntimeWorkflowRepository
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AuditPlanningExecutionPlanMappingTest {
  @Test
  fun `archived audit descriptors map to prose planning for every quality gate`() {
    val fixture = ExecutionPlanAdmissionFixture()
    listOf("goal-child-build", "goal-child-validate").forEach { mode ->
      listOf(1, 2).forEach { revision ->
        val encoded = ArchivedAuditExecutionPlanFixture.archive(fixture, mode, revision)
        val mapped = fixture.compatibility.requireSupportedComposition(encoded)
        val expected = ArchivedAuditExecutionPlanFixture.currentArchive(fixture, mode)
        assertContentEquals(expected, fixture.codec.encode(mapped))
        assertEquals(3, mapped.selectedStrategies.single { it.slot == PhaseSlot.AUDIT }.semanticRevision)
      }
    }
  }

  @Test
  fun `admission retains the original descriptor and all artifacts while binding the mapped plan`() {
    val fixture = ExecutionPlanAdmissionFixture()
    val repository = InMemoryRuntimeWorkflowRepository()
    val old = fixture.codec.decode(ArchivedAuditExecutionPlanFixture.archive(fixture, "goal-child-validate"))
    val encoded =
      fixture.codec.encode(
        old.withEffectivePolicies(FeatureTaskRuntimeEffectivePolicies.resolve(old, fixture.inputs)),
      )
    val descriptor = fixture.validator.read(encoded, "legacy fixture")
    fixture.seed(repository, "mapping", descriptor = descriptor)
    val before = repository.getFeatureTaskWorkflow("mapping")

    val admitted = fixture.admission.admit(repository, "mapping", fixture.inputs)
    admitted.requireCurrent(repository, "mapping")

    assertEquals(before, repository.getFeatureTaskWorkflow("mapping"))
    assertTrue("audit_plan_fix" in admitted.plan.selectedStepIds)
    val expected = fixture.compatibility.requireSupportedComposition(fixture.encoded)
    assertContentEquals(fixture.codec.encode(expected), fixture.codec.encode(admitted.plan))
    fixture.compatibility.requireCompatibleExecution(
      fixture.codec.encode(old.withEffectivePolicies(FeatureTaskRuntimeEffectivePolicies.resolve(old, fixture.inputs))),
      ValidatedFeatureTaskRuntimeExecutionPlan.read(fixture.encoded, fixture.validator),
    )
  }

  @Test
  fun `goal child preparation returns the original descriptor so reuse cannot overwrite migration evidence`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    val fixture =
      ExecutionPlanAdmissionFixture(
        definition = SkeletonDefinition.GOAL_CHILD,
        database = FakeDatabaseSessionFactory(repository),
        qualityGate = FeatureTaskRuntimeQualityGateSelection.BUILD,
      )
    val resolver = fixture.creationResolver()
    val root = Path.of("/tmp/audit-mapping")
    val inputs = resolver.resolveInputs(root, FeatureTaskRuntimeQualityGateSelection.BUILD, ValidationDepth.FULL, null)
    val old = fixture.codec.decode(ArchivedAuditExecutionPlanFixture.archive(fixture, "goal-child-build"))
    val encoded =
      fixture.codec.encode(
        old.withEffectivePolicies(FeatureTaskRuntimeEffectivePolicies.resolve(old, inputs)),
      )
    fixture.seed(repository, "mapping", descriptor = fixture.validator.read(encoded, "old goal child"))
    val before = repository.getFeatureTaskWorkflow("mapping")

    val prepared =
      resolver.resolveCreation(
        FeatureTaskRuntimeExecutionPlanCreationRequest(
          root,
          SkeletonDefinition.GOAL_CHILD,
          CodeReviewExecutionMode.INLINE,
          FeatureTaskRuntimeQualityGateSelection.BUILD,
          ValidationDepth.FULL,
          null,
          workflowId = "mapping",
        ),
      )

    assertContentEquals(encoded, prepared.encoded())
    assertEquals(before, repository.getFeatureTaskWorkflow("mapping"))
    assertTrue("audit_plan_fix" in fixture.admission.admit(repository, "mapping", inputs).plan.selectedStepIds)
  }

  @Test
  fun `mapping refuses changed audit caps and unrelated policy digests`() {
    val fixture = ExecutionPlanAdmissionFixture()
    val old = fixture.codec.decode(ArchivedAuditExecutionPlanFixture.archive(fixture, "goal-child-validate"))
    val changed =
      old.withTraversal(
        old.traversal.copy(backwardEdges = old.traversal.backwardEdges.map { it.copy(perEdgeCap = 9) }),
      )
    assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> {
      fixture.compatibility.requireSupportedComposition(fixture.codec.encode(changed))
    }
    val changedPolicy =
      old.withEffectivePolicies(
        old.effectivePolicies.map {
          if (it.id == "retry-budgets") it.copy(semanticDigest = "0".repeat(64)) else it
        },
      )
    assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> {
      fixture.compatibility.requireSupportedComposition(fixture.codec.encode(changedPolicy))
    }
    val changedFinalization =
      old.withEffectivePolicies(
        old.effectivePolicies.map {
          if (it.id == "finalization") it.copy(semanticDigest = "0".repeat(64)) else it
        },
      )
    assertFailsWith<IncompatibleFeatureTaskRuntimeExecutionPlanError> {
      fixture.compatibility.requireSupportedExecution(fixture.codec.encode(changedFinalization), fixture.inputs)
    }
  }
}
