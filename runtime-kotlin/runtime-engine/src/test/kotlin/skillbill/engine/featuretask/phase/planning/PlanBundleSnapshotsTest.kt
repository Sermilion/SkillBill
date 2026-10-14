package skillbill.engine.featuretask.phase.planning

import skillbill.application.TestDecompositionManifestStore
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testDecompositionManifestWriter
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationRuntime
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationWriter
import skillbill.engine.featuretask.runloop.planning.PlanUnitRecords
import skillbill.engine.featuretask.runner.writePlanBundle
import skillbill.featurespec.model.FeatureSpecWriteResult
import skillbill.infrastructure.workflow.filesystem.FileSystemFeatureSpecPathResolver
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundle
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundleOrigin
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundleSubtask
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ISSUE_KEY = "SKILL-425"
private const val DETAILS_HEADING = "## Implementation Details"

class PlanBundleSnapshotsTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-plan-bundle-snapshots")
  private val snapshots =
    PlanBundleSnapshots(
      FeatureTaskRuntimeDecompositionPlanner(
        preparationRuntime = FeatureSpecPreparationRuntime(),
        preparationWriter =
          FeatureSpecPreparationWriter(
            decompositionManifestValidator = testDecompositionManifestValidator,
            fileStore = TestDecompositionManifestStore,
            decompositionManifestWriter = testDecompositionManifestWriter,
          ),
        specPathResolver = FileSystemFeatureSpecPathResolver(),
      ),
    )

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
  }

  @Test
  fun `settling one unit writes only its details and leaves the bundle free of drift`() {
    val written = writePlanBundle(repoRoot, ISSUE_KEY)
    val bundle = snapshots.capture(repoRoot, written, FeatureTaskRuntimePlanBundleOrigin.AUTHORED)
    appendDetails(written, 0, "- Planned task one.")

    val settled = snapshots.settleUnit(repoRoot, ISSUE_KEY, bundle, unitId = 1)

    val planned = requirePlanned(settled).bundle
    assertTrue(unitComplete(requireNotNull(planned.subtask(1))))
    assertFalse(unitComplete(requireNotNull(planned.subtask(2))))
    assertNull(snapshots.drift(repoRoot, ISSUE_KEY, planned))
  }

  @Test
  fun `a hand-written bundle keeps its details so planning has nothing left to fan out`() {
    val written = writePlanBundle(repoRoot, ISSUE_KEY)
    written.subtaskSpecPaths.indices.forEach { appendDetails(written, it, "- Hand-written plan.") }

    val bundle = snapshots.capture(repoRoot, written, FeatureTaskRuntimePlanBundleOrigin.EXISTING)

    assertTrue(bundle.subtasks.all(::unitComplete))
    assertNull(snapshots.drift(repoRoot, ISSUE_KEY, bundle))
  }

  @Test
  fun `an edited scope invalidates only that unit after a rebase`() {
    val written = writePlanBundle(repoRoot, ISSUE_KEY)
    written.subtaskSpecPaths.indices.forEach { appendDetails(written, it, "- Planned task.") }
    val bundle = snapshots.capture(repoRoot, written, FeatureTaskRuntimePlanBundleOrigin.EXISTING)
    val secondSpec = repoRoot.resolve(written.subtaskSpecPaths[1])
    Files.writeString(secondSpec, "Scope note added after planning.\n" + Files.readString(secondSpec))

    assertNotNull(snapshots.drift(repoRoot, ISSUE_KEY, bundle))
    val rebased = requireNotNull(snapshots.rebase(repoRoot, ISSUE_KEY, bundle, settledSubtaskIds = emptySet()))

    assertTrue(unitComplete(requireNotNull(rebased.subtask(1))))
    assertFalse(unitComplete(requireNotNull(rebased.subtask(2))))
    assertNull(requireNotNull(rebased.subtask(2)).detailsSha256)
  }

  @Test
  fun `rewritten details make a recorded unit incomplete so a resumed fan-out replans it`() {
    val written = writePlanBundle(repoRoot, ISSUE_KEY)
    appendDetails(written, 0, "- Planned task.")
    val bundle = snapshots.capture(repoRoot, written, FeatureTaskRuntimePlanBundleOrigin.EXISTING)
    val unit = requireNotNull(bundle.subtask(1))
    assertTrue(unitComplete(unit))

    val spec = repoRoot.resolve(written.subtaskSpecPaths[0])
    Files.writeString(spec, Files.readString(spec).replace("- Planned task.", "- Something else."))

    assertFalse(unitComplete(unit))
  }

  @Test
  fun `a unit whose details section is blank is rejected instead of recorded`() {
    val written = writePlanBundle(repoRoot, ISSUE_KEY)
    val bundle: FeatureTaskRuntimePlanBundle =
      snapshots.capture(repoRoot, written, FeatureTaskRuntimePlanBundleOrigin.AUTHORED)
    appendDetails(written, 0, "")

    val settled = snapshots.settleUnit(repoRoot, ISSUE_KEY, bundle, unitId = 1)

    assertTrue(settled is PlanUnitSettlement.Rejected, "expected Rejected but was $settled")
  }

  @Test
  fun `a unit that rewrites its scope as well as its details is rejected instead of recorded`() {
    val written = writePlanBundle(repoRoot, ISSUE_KEY)
    val bundle = snapshots.capture(repoRoot, written, FeatureTaskRuntimePlanBundleOrigin.AUTHORED)
    val spec = repoRoot.resolve(written.subtaskSpecPaths[0])
    Files.writeString(spec, "Scope rewritten by the planning unit.\n" + Files.readString(spec))
    appendDetails(written, 0, "- Planned task.")

    val settled = snapshots.settleUnit(repoRoot, ISSUE_KEY, bundle, unitId = 1)

    assertTrue(settled is PlanUnitSettlement.Rejected, "expected Rejected but was $settled")
  }

  private fun unitComplete(unit: FeatureTaskRuntimePlanBundleSubtask): Boolean =
    snapshots.unitComplete(
      repoRoot,
      unit,
      PlanUnitRecords.keptDetails("wftr-test", unit.id, "- Planned task.", Instant.EPOCH),
    )

  private fun requirePlanned(settlement: PlanUnitSettlement): PlanUnitSettlement.Planned {
    assertTrue(settlement is PlanUnitSettlement.Planned, "expected Planned but was $settlement")
    return settlement
  }

  private fun appendDetails(
    written: FeatureSpecWriteResult,
    index: Int,
    body: String,
  ) {
    val spec = repoRoot.resolve(written.subtaskSpecPaths[index])
    Files.writeString(spec, Files.readString(spec) + "\n$DETAILS_HEADING\n$body\n")
  }
}
