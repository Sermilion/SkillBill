package skillbill.contracts.issuekey

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoalIntakeTokenPredicateTest {
  @Test
  fun `goal intake tokens are issue keys, slugged names, urls, and feature-spec paths`() {
    assertTrue(looksLikeGoalIntakeToken("APP-123"))
    assertTrue(looksLikeGoalIntakeToken("SKILL-414-stabilization-pass"))
    assertTrue(looksLikeGoalIntakeToken("https://linear.app/x/issue/APP-1"))
    assertTrue(looksLikeGoalIntakeToken(".feature-specs/APP-1-x/spec.md"))
    assertFalse(looksLikeGoalIntakeToken("phse"))
  }
}
