package skillbill.engine.featuretask.persist.acceptance

import skillbill.agent.model.PhaseOutput
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AcceptanceCriterionRepairSourceIdentityTest {
  @Test
  fun `satisfied verification survives runtime projections and checkpoint commits without spending a failure`() =
    verifyRepair()

  @Test
  fun `satisfied verification still rejects source changes after repair completion`() =
    listOf("source.txt", "new-source.txt", ".skill-bill/config.yaml", ".skill-bill/phases/audit.md").forEach {
      verifyRepair(changedPath = it)
    }

  private fun verifyRepair(changedPath: String? = null) {
    SerialRepairSqliteFixture().use { f ->
      git(f.root, "init")
      git(f.root, "config", "user.email", "skill-bill@example.test")
      git(f.root, "config", "user.name", "Skill Bill")
      git(f.root, "config", "commit.gpgsign", "false")
      Files.writeString(f.root.resolve(".gitignore"), "metrics.db*\n")
      Files.writeString(f.root.resolve("source.txt"), "original\n")
      git(f.root, "add", "source.txt", ".gitignore")
      git(f.root, "commit", "-m", "initial")
      f.project = { request ->
        val projection = f.root.resolve(".skill-bill/acceptance-repair/state.json")
        Files.createDirectories(projection.parent)
        Files.writeString(projection, request.toString())
      }
      f.owned { worker ->
        val scope =
          AcceptanceCriterionRepairRunState(
            f.persistence.inspectExisting(worker, f.binding.authoredCriteria, f.binding.specDigest),
            f.binding,
          )
        val recorder =
          assertNotNull(
            AcceptanceCriterionRepairPhaseRecorder(
              f.persistence,
              GitWorkflowGitOperations(),
              f.workflows,
              f.diagnostics,
            )
              .session(scope, worker, f.root, AcceptanceAuditStrategy().acceptanceRepairSteps),
          )
        complete(recorder, "audit", 1, "advance", "Open criteria: AC-001")
        Files.writeString(f.root.resolve("source.txt"), "repaired\n")
        complete(recorder, "audit_implement_fix", 1, "satisfied", "The repair is complete.")
        git(f.root, "add", "source.txt")
        git(f.root, "commit", "-m", "repair checkpoint")
        val evidence = f.root.resolve(".skill-bill/run-evidence/workflow/checkpoint/evidence.json")
        Files.createDirectories(evidence.parent)
        Files.writeString(evidence, "runtime evidence")
        if (changedPath != null) {
          val changed = f.root.resolve(changedPath)
          Files.createDirectories(changed.parent)
          Files.writeString(changed, "changed after repair\n")
        }
        complete(recorder, "audit", 2, "satisfied", "The production requirement is satisfied.")

        val state = assertNotNull(scope.snapshot)
        assertEquals(
          if (changedPath != null) {
            AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE
          } else {
            AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS
          },
          state.outcomes.single().kind,
        )
        assertEquals(if (changedPath != null) 1 else 0, state.unsuccessfulTotals.getOrDefault("AC-001", 0))
        assertEquals(
          if (changedPath != null) {
            AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED
          } else {
            AcceptanceCriterionSourceDisposition.SOURCE_CLOSED
          },
          state.criteria.single { it.canonicalRef == "AC-001" }.sourceDisposition,
        )
      }
    }
  }

  private fun complete(
    recorder: AcceptanceCriterionRepairSession,
    phaseId: String,
    attempt: Int,
    verdict: String,
    prose: String,
  ) {
    val phase =
      FeatureTaskRuntimePhaseStateRequest(
        recorder.worker.workflowId,
        phaseId,
        "running",
        attempt,
        "codex",
        false,
        mutating = phaseId == "audit_implement_fix",
      )
    assertEquals(RequiredPhaseWrite.Acknowledged, recorder.start(phase))
    val output =
      NormalizedFeatureTaskRuntimePhaseOutput(
        phaseId,
        "completed",
        prose,
        PhaseOutput(prose),
        verdict = verdict,
      )
    assertEquals(
      RequiredPhaseWrite.Acknowledged,
      recorder.complete(
        phase.copy(
          status = "completed",
          finished = true,
          normalizedOutput = output,
          outputArtifact = output.canonicalJson,
        ),
      ),
    )
  }

  private fun git(
    root: Path,
    vararg args: String,
  ) {
    val process = ProcessBuilder(listOf("git", "-C", root.toString()) + args).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    assertTrue(process.waitFor() == 0, output)
  }
}
