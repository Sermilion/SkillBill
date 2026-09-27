package skillbill.engine.featuretask.lifecycle.checkpoint

import skillbill.engine.featuretask.lifecycle.continuation.matches
import skillbill.engine.featuretask.lifecycle.subtask.ISSUE
import skillbill.engine.featuretask.lifecycle.subtask.decide
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCheckpointDecision
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCheckpointScopeInput
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.engine.featuretask.runloop.core.reconcileCheckpointPathInventory
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeCheckpointScopeTest {
  @Test
  fun `a goal child's baseline keeps only paths already dirty when the goal started`() {
    val baseline =
      goalScopedBaselinePaths(
        ownBaseline = listOf("operator/Notes.md", "src/LeftoverFromEarlierAttempt.kt"),
        goalStartBaseline = listOf("operator/Notes.md"),
      )

    assertEquals(listOf("operator/Notes.md"), baseline)
  }

  @Test
  fun `a workflow outside a goal keeps its own baseline`() {
    val own = listOf("operator/Notes.md", "src/Dirty.kt")

    assertEquals(own, goalScopedBaselinePaths(ownBaseline = own, goalStartBaseline = null))
  }

  @Test
  fun `stages owned inventory without foreign staged paths`() {
    val decision =
      decide(
        CheckpointScopeDecideFixture(
          ownedPaths = listOf("src/Owned.kt", "src/AlsoOwned.kt"),
          phaseIntroducedPaths = listOf("src/Owned.kt"),
          foreignStagedPaths = listOf("unrelated/Foreign.kt"),
        ),
      )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertEquals(listOf("src/AlsoOwned.kt", "src/Owned.kt"), stage.ownedPaths)
    assertEquals(emptyList(), stage.adoptedPaths)
  }

  @Test
  fun `a dirty path outside the durable inventory is not staged`() {
    val decision =
      decide(
        CheckpointScopeDecideFixture(
          ownedPaths = listOf("src/Owned.kt"),
          phaseIntroducedPaths = listOf("src/Owned.kt"),
          worktreeDeltaPaths = listOf("src/Owned.kt", "unrelated/AppearedSince.kt"),
        ),
      )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertEquals(listOf("src/Owned.kt"), stage.ownedPaths)
  }

  @Test
  fun `a dirty path is ignored when the durable inventory has no overlapping delta`() {
    val decision =
      decide(
        CheckpointScopeDecideFixture(
          ownedPaths = listOf("src/Owned.kt"),
          phaseIntroducedPaths = emptyList(),
          worktreeDeltaPaths = listOf("unrelated/ForeignOnly.kt"),
        ),
      )

    assertIs<FeatureTaskRuntimeCheckpointDecision.Skip>(decision)
  }

  @Test
  fun `foreign dirt alone is ignored as this subtask's work`() {
    val decision =
      decide(
        CheckpointScopeDecideFixture(
          ownedPaths = emptyList(),
          phaseIntroducedPaths = emptyList(),
          foreignStagedPaths = listOf("unrelated/Foreign.kt", "unrelated/AlsoForeign.kt"),
        ),
      )

    assertIs<FeatureTaskRuntimeCheckpointDecision.Skip>(decision)
  }

  @Test
  fun `a path introduced outside the owned inventory is staged when the phase wrote it`() {
    val decision =
      decide(
        CheckpointScopeDecideFixture(
          ownedPaths = listOf("src/Owned.kt"),
          phaseIntroducedPaths = listOf("src/Owned.kt", "src/Smuggled.kt"),
        ),
      )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertEquals(listOf("src/Owned.kt", "src/Smuggled.kt"), stage.ownedPaths)
  }

  @Test
  fun `package-move delete sources are adopted instead of blocking outside inventory`() {
    val decision =
      decide(
        CheckpointScopeDecideFixture(
          ownedPaths = listOf("src/run/Owned.kt"),
          phaseIntroducedPaths = listOf("src/Owned.kt", "src/run/Owned.kt"),
          worktreeDeltaPaths = listOf("src/Owned.kt", "src/run/Owned.kt"),
          deletedPaths = listOf("src/Owned.kt"),
        ),
      )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertEquals(listOf("src/Owned.kt", "src/run/Owned.kt"), stage.ownedPaths)
    assertEquals(listOf("src/Owned.kt"), stage.adoptedPaths)
  }

  @Test
  fun `a concurrently prepared foreign feature spec is staged when the phase wrote it`() {
    val foreign = ".feature-specs/OTHER-999-concurrent/spec.md"
    val decision =
      decide(
        CheckpointScopeDecideFixture(
          ownedPaths = listOf(".feature-specs/$ISSUE-scoped/spec.md"),
          phaseIntroducedPaths =
            listOf(
              ".feature-specs/$ISSUE-scoped/spec.md",
              foreign,
            ),
        ),
      )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertEquals(
      listOf(foreign),
      stage.ownedPaths.sorted(),
    )
  }

  @Test
  fun `a foreign feature spec inside the owned inventory is staged with the rest`() {
    val foreign = ".feature-specs/OTHER-999-concurrent/spec.md"
    val decision =
      decide(
        CheckpointScopeDecideFixture(
          ownedPaths = listOf(foreign, "src/Owned.kt"),
          phaseIntroducedPaths = listOf("src/Owned.kt"),
        ),
      )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertEquals(listOf(foreign, "src/Owned.kt"), stage.ownedPaths)
  }

  @Test
  fun `an already-owned foreign feature spec the phase left dirty is staged`() {
    val foreign = ".feature-specs/OTHER-999-concurrent/spec.md"
    val decision =
      decide(
        CheckpointScopeDecideFixture(
          ownedPaths = listOf(foreign, "src/Owned.kt"),
          phaseIntroducedPaths = listOf(foreign, "src/Owned.kt"),
        ),
      )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertEquals(listOf(foreign, "src/Owned.kt"), stage.ownedPaths)
  }

  @Test
  fun `a foreign feature spec that is merely present and untouched produces no false block`() {
    val decision =
      decide(
        CheckpointScopeDecideFixture(
          ownedPaths = listOf("src/Owned.kt"),
          phaseIntroducedPaths = listOf("src/Owned.kt"),
          foreignStagedPaths = listOf(".feature-specs/OTHER-999-concurrent/spec.md"),
        ),
      )

    assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
  }

  @Test
  fun `an owned path that is also foreign-staged is adopted and committed rather than blocking`() {
    val decision =
      decide(
        CheckpointScopeDecideFixture(
          ownedPaths = listOf("src/Contested.kt"),
          phaseIntroducedPaths = listOf("src/Contested.kt"),
          foreignStagedPaths = listOf("src/Contested.kt"),
        ),
      )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertEquals(listOf("src/Contested.kt"), stage.ownedPaths)
    assertEquals(listOf("src/Contested.kt"), stage.adoptedPaths)
  }

  @Test
  fun `an owned path modified concurrently without staging is adopted rather than blocking`() {
    val decision =
      decide(
        CheckpointScopeDecideFixture(
          ownedPaths = listOf("src/Contested.kt", "src/Owned.kt"),
          phaseIntroducedPaths = listOf("src/Contested.kt", "src/Owned.kt"),
          concurrentlyModifiedOwnedPaths = listOf("src/Contested.kt"),
        ),
      )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertEquals(listOf("src/Contested.kt", "src/Owned.kt"), stage.ownedPaths)
    assertEquals(listOf("src/Contested.kt"), stage.adoptedPaths, "only the diverged path is reported")
  }

  @Test
  fun `a diverged owned path outside the working-tree delta is still staged`() {
    val decision =
      decide(
        CheckpointScopeDecideFixture(
          ownedPaths = listOf("src/Contested.kt"),
          phaseIntroducedPaths = emptyList(),
          worktreeDeltaPaths = emptyList(),
          foreignStagedPaths = listOf("src/Contested.kt"),
        ),
      )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertEquals(listOf("src/Contested.kt"), stage.ownedPaths)
  }

  @Test
  fun `case-aliased and separator-aliased overlaps adopt the inventory spelling of the path`() {
    val decision =
      decide(
        CheckpointScopeDecideFixture(
          ownedPaths = listOf("Src/Contested.kt"),
          phaseIntroducedPaths = listOf("Src/Contested.kt"),
          foreignStagedPaths = listOf("src/contested.kt"),
        ),
      )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertEquals(listOf("Src/Contested.kt"), stage.ownedPaths, "one path, not two aliases")
    assertEquals(listOf("Src/Contested.kt"), stage.adoptedPaths)
  }

  @Test
  fun `a case-aliased concurrent modification adopts the inventory spelling of the path`() {
    val decision =
      decide(
        CheckpointScopeDecideFixture(
          ownedPaths = listOf("Src/Contested.kt"),
          phaseIntroducedPaths = listOf("Src/Contested.kt"),
          concurrentlyModifiedOwnedPaths = listOf("src/contested.kt"),
        ),
      )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertEquals(listOf("Src/Contested.kt"), stage.ownedPaths, "one path, not two aliases")
    assertEquals(listOf("Src/Contested.kt"), stage.adoptedPaths)
  }

  @Test
  fun `a foreign staged path is ignored as this subtask's work`() {
    val decision =
      decide(
        CheckpointScopeDecideFixture(
          ownedPaths = listOf("src/Owned.kt"),
          phaseIntroducedPaths = listOf("src/Owned.kt"),
          foreignStagedPaths = listOf("unrelated/Foreign.kt"),
        ),
      )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertEquals(listOf("src/Owned.kt"), stage.ownedPaths)
    assertEquals(emptyList(), stage.adoptedPaths)
  }

  @Test
  fun `an alias of an owned path is not reported as introduced outside the inventory`() {
    val decision =
      decide(
        CheckpointScopeDecideFixture(
          ownedPaths = listOf("src/Owned.kt"),
          phaseIntroducedPaths = listOf("SRC/OWNED.KT"),
        ),
      )

    assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
  }

  @Test
  fun `phase-written paths are the delta the phase's own file manifest accounts for`() {
    val written =
      phaseWrittenPaths(
        worktreeDeltaPaths = listOf("src/Owned.kt", "unrelated/SiblingAgentWrote.kt", "new/dir/Nested.kt"),
        phaseManifestPaths = listOf("src/Owned.kt", "new/dir"),
      )

    assertEquals(listOf("new/dir/Nested.kt", "src/Owned.kt"), written)
  }

  @Test
  fun `phase-written paths are empty when the phase's manifest accounts for nothing`() {
    assertEquals(
      emptyList(),
      phaseWrittenPaths(
        worktreeDeltaPaths = listOf("unrelated/SiblingAgentWrote.kt"),
        phaseManifestPaths = emptyList(),
      ),
    )
  }

  @Test
  fun `review exclusions widen the baseline to every untracked path the run does not own`() {
    val exclusions =
      reviewUntrackedExclusions(
        baselineUntrackedPaths = listOf("pre/Existing.kt"),
        currentUntrackedPaths =
          listOf(
            "pre/Existing.kt",
            "src/Owned.kt",
            "unrelated/AppearedSince.kt",
            ".feature-specs/OTHER-999-concurrent/spec.md",
          ),
        ownedPaths = listOf("src/Owned.kt"),
      )

    assertEquals(
      listOf(".feature-specs/OTHER-999-concurrent/spec.md", "pre/Existing.kt", "unrelated/AppearedSince.kt"),
      exclusions,
    )
    assertFalse("src/Owned.kt" in exclusions, "an owned path must stay inside the review scope")
  }

  @Test
  fun `commit messages distinguish initial implementation from audit repair and review remediation`() {
    val initial = message(phaseId = "audit", loopId = null, generation = 0, intent = INTENT_INITIAL)
    val auditRepair = message(phaseId = "audit", loopId = "audit_gap", generation = 1, intent = INTENT_FIX)
    val reviewRemediation = message(phaseId = "review", loopId = "review_fix", generation = 2, intent = INTENT_FIX)

    assertEquals(3, setOf(initial, auditRepair, reviewRemediation).size)
    listOf(initial, auditRepair, reviewRemediation).forEach { message ->
      assertContains(message, ISSUE)
      assertContains(message, BRANCH)
    }
    assertContains(auditRepair, "loop=audit_gap")
    assertContains(auditRepair, "generation=1")
    assertContains(reviewRemediation, "loop=review_fix")
    assertContains(reviewRemediation, "generation=2")
  }

  @Test
  fun `checkpoint subject carries the subtask name while metadata and trailer stay in the body`() {
    val named =
      message(
        phaseId = "audit",
        loopId = "review_fix",
        generation = 3,
        intent = INTENT_FIX,
        subtaskName = SUBTASK_NAME,
      )

    val lines = named.lines()
    assertEquals("$ISSUE: $SUBTASK_NAME", lines.first())
    listOf("phase=", "loop=", "generation=").forEach { metadata ->
      assertFalse(lines.first().contains(metadata), "checkpoint metadata must not occupy the commit subject")
      assertContains(named, metadata)
    }
    assertEquals(
      "Skill-Bill-Subtask: $ISSUE/7",
      named.lines().last { it.isNotBlank() },
      "the subtask trailer must terminate the message so git reads it as a trailer",
    )

    val unnamed = message(phaseId = "audit", loopId = null, generation = 0, intent = INTENT_INITIAL)
    assertEquals("$ISSUE: subtask 7", unnamed.lines().first())
  }

  @Test
  fun `commit subject uses the subtask name and falls back without inventing prose`() {
    assertEquals(
      "$ISSUE: $SUBTASK_NAME",
      FeatureTaskRuntimeCheckpointMessage.subject(ISSUE, SUBTASK_NAME, "7"),
    )
    assertEquals(
      "$ISSUE: subtask 7",
      FeatureTaskRuntimeCheckpointMessage.subject(ISSUE, "   ", "7"),
    )
  }

  @Test
  fun `subtask trailers round-trip and never match another subtask`() {
    val identity = FeatureTaskRuntimeSubtaskCommitIdentity(issueKey = ISSUE, subtaskId = "7")
    val rendered = message(phaseId = "audit", loopId = null, generation = 0, intent = INTENT_INITIAL)

    assertEquals(identity, FeatureTaskRuntimeSubtaskCommitIdentity.parse(rendered))
    assertTrue(identity.matches(rendered))

    assertFalse(identity.matches("done\n\nSkill-Bill-Subtask: $ISSUE/6\n"))
    assertFalse(identity.matches("done\n\nSkill-Bill-Subtask: OTHER-1/7\n"))
    assertNull(FeatureTaskRuntimeSubtaskCommitIdentity.parse("done\n\nno trailer here\n"))
  }

  private fun message(
    phaseId: String,
    loopId: String?,
    generation: Int,
    intent: String,
    subtaskName: String? = null,
  ): String =
    FeatureTaskRuntimeCheckpointMessage.build(
      issueKey = ISSUE,
      subtaskName = subtaskName,
      metadata =
        FeatureTaskRuntimeCheckpointMetadata(
          phaseId = phaseId,
          loopId = loopId,
          generation = generation,
          branch = BRANCH,
          intent = intent,
        ),
      identity = FeatureTaskRuntimeSubtaskCommitIdentity(issueKey = ISSUE, subtaskId = "7"),
    )

  @Test
  fun `runtime-private run-evidence does not block outside-inventory ownership`() {
    val evidence = ".skill-bill/run-evidence/wftr-1/fp/evidence.json"
    val patch = ".skill-bill/run-evidence/wftr-1/fp/diff.patch"
    val decision =
      decide(
        CheckpointScopeDecideFixture(
          ownedPaths = listOf("src/Owned.kt"),
          phaseIntroducedPaths = listOf("src/Owned.kt", evidence, patch, ".skill-bill/"),
          workflowId = "wftr-1",
        ),
      )

    val stage = assertIs<FeatureTaskRuntimeCheckpointDecision.Stage>(decision)
    assertEquals(listOf("src/Owned.kt"), stage.ownedPaths)
  }

  @Test
  fun `runtime-private paths are stripped from phaseWrittenPaths even when the manifest collapses the tree`() {
    val written =
      phaseWrittenPaths(
        worktreeDeltaPaths =
          listOf(
            "src/Owned.kt",
            ".skill-bill/state.db",
            ".skill-bill/run-evidence/wf/fp/diff.patch",
          ),
        phaseManifestPaths = listOf("src/Owned.kt", ".skill-bill/"),
      )

    assertEquals(
      listOf(".skill-bill/run-evidence/wf/fp/diff.patch", "src/Owned.kt"),
      written,
      "run-evidence survives the prefix seam so provenance decides ownership downstream",
    )
  }

  @Test
  fun `trackable skill-bill config is not treated as runtime-private`() {
    assertFalse(isRuntimePrivatePath(".skill-bill/config.yaml"))
    assertTrue(isRuntimePrivatePath(".skill-bill/state.db"))
  }

  @Test
  fun `run-evidence ownership follows the active run, not the store prefix`() {
    val runEvidence =
      listOf(
        ".skill-bill/run-evidence/wf-mine/fp/diff.patch",
        ".skill-bill/run-evidence/wf-other/fp/diff.patch",
        ".skill-bill/run-evidence/forged.json",
      )
    val decision =
      decide(
        CheckpointScopeDecideFixture(
          ownedPaths = listOf("src/Owned.kt"),
          phaseIntroducedPaths = listOf("src/Owned.kt") + runEvidence,
          worktreeDeltaPaths = listOf("src/Owned.kt") + runEvidence,
          workflowId = "wf-mine",
        ),
      )

    assertEquals(
      FeatureTaskRuntimeCheckpointDecision.Stage(
        listOf(
          ".skill-bill/run-evidence/forged.json",
          ".skill-bill/run-evidence/wf-other/fp/diff.patch",
          "src/Owned.kt",
        ),
      ),
      decision,
    )
    assertEquals(
      listOf(
        ".skill-bill/run-evidence/wf-other/fp/diff.patch",
        ".skill-bill/run-evidence/forged.json",
        "src/Owned.kt",
      ),
      reconcileCheckpointPathInventory(
        repoRoot = Path.of("/repo"),
        issueKey = ISSUE,
        specReference = ".feature-specs/$ISSUE/spec.md",
        workflowId = "wf-mine",
        paths = runEvidence + "src/Owned.kt",
      ),
      "only this run's own evidence leaves the inventory the review pathspec is built from",
    )
  }

  private data class CheckpointScopeDecideFixture(
    val ownedPaths: List<String>,
    val phaseIntroducedPaths: List<String>,
    val worktreeDeltaPaths: List<String> = (ownedPaths + phaseIntroducedPaths).distinct(),
    val foreignStagedPaths: List<String> = emptyList(),
    val concurrentlyModifiedOwnedPaths: List<String> = emptyList(),
    val deletedPaths: List<String> = emptyList(),
    val workflowId: String? = null,
  ) {
    fun decide(): FeatureTaskRuntimeCheckpointDecision =
      FeatureTaskRuntimeCheckpointScope.decide(
        FeatureTaskRuntimeCheckpointScopeInput(
          issueKey = ISSUE,
          ownedPaths = ownedPaths,
          phaseIntroducedPaths = phaseIntroducedPaths,
          worktreeDeltaPaths = worktreeDeltaPaths,
          foreignStagedPaths = foreignStagedPaths,
          concurrentlyModifiedOwnedPaths = concurrentlyModifiedOwnedPaths,
          deletedPaths = deletedPaths,
          workflowId = workflowId,
        ),
      )
  }

  private fun decide(fixture: CheckpointScopeDecideFixture): FeatureTaskRuntimeCheckpointDecision = fixture.decide()

  private companion object {
    const val ISSUE = "SKILL-150"
    const val BRANCH = "feat/SKILL-150-scoped-checkpoint"
    const val SUBTASK_NAME = "scoped-checkpoint-staging"
    val INTENT_INITIAL = FeatureTaskRuntimeCheckpointMessage.INTENT_AUDITED_IMPLEMENTATION
    val INTENT_FIX = FeatureTaskRuntimeCheckpointMessage.INTENT_REMEDIATION
  }
}
