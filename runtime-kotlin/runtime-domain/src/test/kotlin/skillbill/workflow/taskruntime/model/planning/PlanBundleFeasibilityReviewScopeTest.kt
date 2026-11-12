package skillbill.workflow.taskruntime.model.planning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PlanBundleFeasibilityReviewScopeTest {
  @Test
  fun `changed specs invalidate optional skipped dependency closure and retain unrelated success`() {
    val previousIdentity = identity("b")
    val currentIdentity = identity("c")
    val previous =
      FeatureTaskRuntimePlanBundleFeasibilityState(
        revision = 1,
        mode = PlanBundleFeasibilityMode.REVIEW,
        identity = previousIdentity,
        acceptedLines =
          listOf(
            acceptedLine(previousIdentity, 1),
            acceptedLine(previousIdentity, 2),
            acceptedLine(previousIdentity, 3, listOf(2)),
            acceptedLine(previousIdentity, 4, listOf(2, 3)),
            acceptedLine(previousIdentity, 6, listOf(2)),
          ),
        conflicts =
          listOf(
            PlanBundleFeasibilityConflict("5.1", "conflict 5.1: Existing conflict cites src/plan.md:15"),
          ),
        changedSubtaskIds = emptyList(),
        requiredRefs = currentIdentity.subtasks.flatMap { it.criterionRefs },
        carriedRefs = emptyList(),
        reviewRoundsStarted = 1,
        repairLaunchesStarted = 0,
        activeLaunch = null,
        settledLaunches = emptyList(),
        lastReviewValue = null,
        approval = null,
        legacyOrigin = false,
      )

    val result =
      assertIs<PlanBundleFeasibilityReviewScopeResult.Valid>(
        PlanBundleFeasibilityReviewScope.derive(currentIdentity, previous),
      ).projection

    assertEquals(listOf("2.1", "3.1", "4.1", "5.1", "6.1"), result.requiredRefs)
    assertEquals(listOf("1.1"), result.carriedRefs)
    assertEquals(listOf(previous.acceptedLines.first()), result.carriedLines)
    assertEquals(listOf(2), result.changedSubtaskIds)
    assertEquals(previous.conflicts, result.previousConflictLines)

    val parentDrift = identity("b").copy(parentSha256 = "4".repeat(64))
    val manifestDrift = identity("b").copy(manifestSha256 = "5".repeat(64))
    for (editedIdentity in listOf(parentDrift, manifestDrift)) {
      val fullReview =
        assertIs<PlanBundleFeasibilityReviewScopeResult.Valid>(
          PlanBundleFeasibilityReviewScope.derive(editedIdentity, previous),
        ).projection
      assertEquals(editedIdentity.subtasks.flatMap { it.criterionRefs }, fullReview.requiredRefs)
      assertEquals(emptyList(), fullReview.carriedRefs)
    }
  }

  private fun acceptedLine(
    identity: PlanBundleFeasibilityIdentity,
    id: Int,
    dependencyIds: List<Int> = emptyList(),
  ): PlanBundleFeasibilityAcceptedLine =
    PlanBundleFeasibilityAcceptedLine(
      ref = "$id.1",
      line = "criterion $id.1: Child $id remains feasible under its recorded dependencies.",
      judgedParentSha256 = identity.parentSha256,
      judgedManifestSha256 = identity.manifestSha256,
      judgedSpecSha256 = identity.subtasks.first { it.id == id }.specSha256,
      dependencyHashes =
        dependencyIds.map { dependencyId ->
          PlanBundleFeasibilityDependencyHash(
            dependencyId,
            identity.subtasks.first { it.id == dependencyId }.specSha256,
          )
        },
    )

  private fun identity(changedSecondSpec: String): PlanBundleFeasibilityIdentity {
    val specs = listOf("a", changedSecondSpec, "d", "e", "f", "g")
    val dependencies =
      listOf(
        emptyList(),
        emptyList(),
        listOf(PlanBundleFeasibilityDependency(2, optional = true, skipped = true)),
        listOf(PlanBundleFeasibilityDependency(3, optional = false, skipped = false)),
        emptyList(),
        listOf(PlanBundleFeasibilityDependency(2, optional = true, skipped = false)),
      )
    return PlanBundleFeasibilityIdentity(
      parentSha256 = HASH,
      manifestSha256 = MANIFEST,
      immutableManifestSha256 = IMMUTABLE,
      subtasks =
        specs.mapIndexed { index, spec ->
          PlanBundleFeasibilitySubtaskIdentity(
            id = index + 1,
            specPath = "spec-${index + 1}.md",
            specSha256 = spec.hashCode().toUInt().toString(16).padStart(64, '0'),
            criterionRefs = listOf("${index + 1}.1"),
            dependencies = dependencies[index],
          )
        },
    )
  }

  private companion object {
    val HASH = "1".repeat(64)
    val MANIFEST = "2".repeat(64)
    val IMMUTABLE = "3".repeat(64)
  }
}
