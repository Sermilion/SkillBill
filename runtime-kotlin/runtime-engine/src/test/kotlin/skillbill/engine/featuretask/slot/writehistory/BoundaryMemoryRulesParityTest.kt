package skillbill.engine.featuretask.slot.writehistory

import skillbill.engine.directive.directiveResource
import kotlin.test.Test
import kotlin.test.assertTrue

class BoundaryMemoryRulesParityTest {
  @Test
  fun `the history and decision rules forbid memory under goal-planning excluded roots`() {
    listOf(
      BoundaryHistoryStrategy.HISTORY_RULES_RESOURCE,
      BoundaryHistoryStrategy.DECISIONS_RULES_RESOURCE,
    ).forEach { resource ->
      val rules = directiveResource(resource)
      assertTrue("never create `agent/` under `platform-packs/`" in rules, rules)
      assertTrue("goal-planning discovery exclusion list" in rules, rules)
    }
  }
}
