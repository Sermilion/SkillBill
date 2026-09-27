package skillbill.engine.featuretask.slotbaseline

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class SlotBaselineCaptureTest {
  @Test
  fun captureAllSlotBaselineFixtures() {
    assumeTrue(System.getenv(CAPTURE_ENVIRONMENT_KEY) == "1", "set $CAPTURE_ENVIRONMENT_KEY=1 to regenerate")
    SlotBaselineWriter.replaceTree(SlotBaselineCommittedTree.liveCaptureTree())
  }

  @Test
  fun consecutiveCapturesProduceByteIdenticalTree() {
    val first = SlotBaselineCommittedTree.liveCaptureTree()
    val second = SlotBaselineCommittedTree.liveCaptureTree()

    assertEquals(first.keys, second.keys, "consecutive captures produced different fixture paths")
    val drift =
      first.filter { (path, bytes) -> second.getValue(path) != bytes }.map { (path, bytes) ->
        "non-deterministic fixture $path\n${SlotBaselineFixtureCompare.unifiedDiff(bytes, second.getValue(path))}"
      }
    check(drift.isEmpty()) { drift.joinToString("\n\n") }
  }

  private companion object {
    const val CAPTURE_ENVIRONMENT_KEY = "SKILL_BILL_SLOTBASELINE_CAPTURE"
  }
}
