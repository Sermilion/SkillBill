package skillbill.workflow.taskruntime.model.skeleton

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PhaseStepPolicySemanticIdentityTest {
  private val policy =
    PhaseStepPolicy(
      mutating = false,
      singleAgentSession = false,
      readOnlyIdle = false,
      fileMutating = false,
      generationScoped = false,
    )

  @Test
  fun `every behavior affecting step policy field changes semantic identity`() {
    val baseline = identity(policy)
    val changes =
      listOf(
        policy.copy(mutating = true),
        policy.copy(singleAgentSession = true),
        policy.copy(readOnlyIdle = true),
        policy.copy(fileMutating = true),
        policy.copy(generationScoped = true),
        policy.copy(outputGateAttempts = 2),
        policy.copy(extendsOwnedInventory = true),
      ).map(::identity)
    changes.forEach { assertNotEquals(baseline, it) }
    assertEquals(changes.size, changes.toSet().size)
    assertEquals(baseline, identity(policy.copy()))
    assertTrue(Regex("step-policy-v2:[0-9a-f]{64}").matches(baseline))
  }

  @Test
  fun `strategy revision and dispatch identity cannot share a policy identity`() {
    val baseline = identity(policy)
    assertNotEquals(baseline, policy.semanticIdentity("other", 1, "implement"))
    assertNotEquals(baseline, policy.semanticIdentity("implementation", 2, "implement"))
    assertNotEquals(baseline, policy.semanticIdentity("implementation", 1, "simplify"))
    assertNotEquals(
      policy.semanticIdentity("a:b", 1, "c"),
      policy.semanticIdentity("a", 1, "b:c"),
    )
  }

  private fun identity(value: PhaseStepPolicy): String = value.semanticIdentity("implementation", 1, "implement")
}
