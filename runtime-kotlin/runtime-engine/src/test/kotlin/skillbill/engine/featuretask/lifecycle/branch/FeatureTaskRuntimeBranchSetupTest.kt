package skillbill.engine.featuretask.lifecycle.branch

import skillbill.application.decomposition.baseBranch
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeBranchSetupTest {
  @Test
  fun `target branch follows feat issue-key feature-name convention parsed from spec parent dir`() {
    val resolved =
      assertIs<FeatureTaskRuntimeTargetBranchResolved>(
        FeatureTaskRuntimeBranchSetup.targetBranch(
          issueKey = "SKILL-65.1",
          specReference = ".feature-specs/SKILL-65.1-runtime-feature-task-parity/spec_subtask_1.md",
        ),
      )

    assertEquals("feat/SKILL-65.1-runtime-feature-task-parity", resolved.branch)
  }

  @Test
  fun `target branch is invalid when the request issue key diverges from the spec parent dir`() {
    val invalid =
      assertIs<FeatureTaskRuntimeTargetBranchInvalid>(
        FeatureTaskRuntimeBranchSetup.targetBranch(
          issueKey = "SKILL-65",
          specReference = ".feature-specs/SKILL-65.1-runtime-feature-task-parity/spec.md",
        ),
      )

    assertContains(invalid.reason, "SKILL-65")
    assertContains(invalid.reason, "SKILL-65.1")
    assertContains(invalid.reason, "divergent")
  }

  @Test
  fun `target branch is invalid when the spec reference has no parent directory`() {
    val invalid =
      assertIs<FeatureTaskRuntimeTargetBranchInvalid>(
        FeatureTaskRuntimeBranchSetup.targetBranch(issueKey = "SKILL-65", specReference = "spec.md"),
      )

    assertContains(invalid.reason, "no parent directory")
  }

  @Test
  fun `decide is invalid when the spec reference has no parent directory`() {
    val invalid =
      assertIs<FeatureTaskRuntimeBranchDecisionInvalid>(
        FeatureTaskRuntimeBranchSetup.decide(issueKey = "SKILL-65", specReference = "spec.md", currentBranch = "main"),
      )

    assertContains(invalid.reason, "no parent directory")
  }

  @Test
  fun `default branch decision creates and switches from main`() {
    val decision =
      assertIs<FeatureTaskRuntimeBranchDecisionResolved>(
        FeatureTaskRuntimeBranchSetup.decide(
          issueKey = "SKILL-65.1",
          specReference = ".feature-specs/SKILL-65.1-runtime-feature-task-parity/spec.md",
          currentBranch = "main",
        ),
      )

    assertTrue(decision.create)
    assertEquals("feat/SKILL-65.1-runtime-feature-task-parity", decision.branch)
    assertEquals("main", decision.baseBranch)
  }

  @Test
  fun `protected branches master and trunk and blank also create`() {
    listOf("master", "trunk", "MAIN", "  ").forEach { current ->
      val decision =
        assertIs<FeatureTaskRuntimeBranchDecisionResolved>(
          FeatureTaskRuntimeBranchSetup.decide(
            issueKey = "SKILL-65.1",
            specReference = ".feature-specs/SKILL-65.1-runtime/spec.md",
            currentBranch = current,
          ),
        )
      assertTrue(decision.create, "expected create for current branch '$current'")
      assertEquals("feat/SKILL-65.1-runtime", decision.branch, "branch for current '$current'")
      assertEquals("main", decision.baseBranch, "base branch for current '$current'")
    }
  }

  @Test
  fun `non-default branch is reused as-is without a base branch`() {
    val decision =
      assertIs<FeatureTaskRuntimeBranchDecisionResolved>(
        FeatureTaskRuntimeBranchSetup.decide(
          issueKey = "SKILL-65.1",
          specReference = ".feature-specs/SKILL-65.1-runtime/spec.md",
          currentBranch = "feat/pre-created",
        ),
      )

    assertFalse(decision.create)
    assertEquals("feat/pre-created", decision.branch)
    assertNull(decision.baseBranch)
  }

  @Test
  fun `protected branch name is recognized case-insensitively`() {
    assertEquals("main", protectedBranchName("main"))
    assertEquals("Master", protectedBranchName("Master"))
    assertNull(protectedBranchName("feat/x"))
    assertNull(protectedBranchName(null))
  }
}
