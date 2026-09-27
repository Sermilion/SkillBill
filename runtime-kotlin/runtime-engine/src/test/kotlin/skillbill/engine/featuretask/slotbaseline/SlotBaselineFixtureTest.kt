package skillbill.engine.featuretask.slotbaseline

import kotlin.test.Test
import kotlin.test.assertEquals

class SlotBaselineFixtureTest {
  @Test
  fun liveCaptureMatchesEveryCommittedFixture() {
    val live = SlotBaselineCommittedTree.liveCaptureTree()

    assertEquals(
      SlotBaselineCommittedTree.relativeFixturePaths(),
      live.keys,
      "committed fixture paths must equal the live capture paths; regenerate with SKILL_BILL_SLOTBASELINE_CAPTURE=1",
    )
    live.forEach { (resourcePath, bytes) -> SlotBaselineFixtureCompare.assertBytesEqual(resourcePath, bytes) }
  }
}
