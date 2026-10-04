package skillbill.engine.featuretask.runner

import java.nio.file.Files
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

internal object SlotBaselineCommittedTree {
  private const val README = "README.md"

  fun relativeFixturePaths(): Set<String> =
    Files.walk(SlotBaselineTestResources.resolve(SlotBaselinePaths.RESOURCE_ROOT)).use { paths ->
      paths
        .filter { it.isRegularFile() && it.fileName.toString() != README }
        .map { it.relativeTo(SlotBaselineTestResources.resourcesRoot).toString().replace('\\', '/') }
        .toList()
        .toSortedSet()
    }

  fun liveCaptureTree(): Map<String, String> =
    buildMap {
      putAll(SlotBaselineFullRunCapture.standalone().encodedFiles(SlotBaselinePaths.STANDALONE))
      putAll(SlotBaselineFullRunCapture.goalChildBuild().encodedFiles(SlotBaselinePaths.GOAL_CHILD_BUILD))
      putAll(SlotBaselineFullRunCapture.goalChildValidate().encodedFiles(SlotBaselinePaths.GOAL_CHILD_VALIDATE))
      putAll(SlotBaselineGoalPlanningCapture.encodedFiles())
      putAll(SlotBaselineCodeReviewCapture.encodedFiles())
      putAll(SlotBaselineMcpLifecycleCapture.encodedFiles())
      putAll(SlotBaselinePhaseRunCapture.encodedFiles())
    }.toSortedMap()
}
