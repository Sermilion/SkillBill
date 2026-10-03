package skillbill.engine.goalrunner.reset

import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.GoalRunnerStatusTestPorts
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.goalTestPhaseRecorder
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerReplanRequest
import skillbill.engine.goalrunner.model.GoalRunnerStatusRequest
import skillbill.engine.goalrunner.persist.DeadProcessSupervisor
import skillbill.error.shellcontent.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.goalrunner.model.GoalPlanningStatusState.NOT_STARTED
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
import skillbill.workflow.taskruntime.model.persistence.featureTaskRuntimeCheckpointRefName
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalRunnerReplanTest {
  private val idleClock: Clock = Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC)

  @Test
  fun `scoped replan deletes only the target plan and retargets intent`() {
    val original =
      manifest(subtaskCount = 3).copy(
        status = "in_progress",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 3, action = "start"),
        subtasks =
          manifest(subtaskCount = 3).subtasks.map { subtask ->
            when (subtask.id) {
              1 -> subtask.copy(status = "complete", commitSha = "sha-1", workflowId = "wfl-1")
              2 -> subtask.copy(status = "complete", commitSha = "sha-2", workflowId = "wfl-2")
              else -> subtask.copy(status = "pending")
            }
          },
      )
    val store =
      InMemoryGoalManifestStore(original).apply {
        plannedSubtaskIds = mutableSetOf(1, 2, 3)
        sharedPreplanPrepared = true
        persistOutOfBandAcceptance(
          "wfl-parent",
          GoalRunnerOutOfBandAcceptance(1, "sha-1", "landed outside", "2026-07-27T11:00:00Z"),
        )
        seedIdleLease()
      }
    val service = idleReplanService(store)

    val result =
      requireNotNull(
        service.replan(GoalRunnerReplanRequest(issueKey = "SKILL-56", subtaskId = 3)),
      )

    assertTrue(result.discardedPlan)
    assertEquals(listOf(1, 2, 3), result.before.plannedSubtaskIds)
    assertEquals(listOf(1, 2), result.after.plannedSubtaskIds)
    assertTrue(result.after.sharedPreplanPrepared)
    assertEquals(CurrentSubtaskIntent(subtaskId = 3, action = "start"), store.manifest.currentSubtaskIntent)
    assertEquals(original.subtasks, store.manifest.subtasks)
    assertEquals(
      mapOf(1 to GoalRunnerOutOfBandAcceptance(1, "sha-1", "landed outside", "2026-07-27T11:00:00Z")),
      store.acceptances,
    )
    assertEquals(1, store.scopedReplanCount)
    assertEquals(setOf(1, 2), store.plannedSubtaskIds.toSet())
    assertEquals(3, listOf(1, 2, 3).first { it !in store.plannedSubtaskIds })
  }

  @Test
  fun `scoped replan resumes when the target already owns a child workflow`() {
    val store =
      InMemoryGoalManifestStore(
        manifest(subtaskCount = 2).copy(
          status = "in_progress",
          currentSubtaskIntent = CurrentSubtaskIntent(1, "start"),
          subtasks =
            listOf(
              manifest(subtaskCount = 2).subtasks[0].copy(status = "complete", commitSha = "sha-1"),
              manifest(subtaskCount = 2).subtasks[1].copy(
                status = "in_progress",
                workflowId = "wfl-2",
                lastResumableStep = "implement",
              ),
            ),
        ),
      ).apply {
        plannedSubtaskIds = mutableSetOf(1, 2)
        seedIdleLease()
      }
    val service = idleReplanService(store)

    service.replan(GoalRunnerReplanRequest("SKILL-56", subtaskId = 2))

    assertEquals(CurrentSubtaskIntent(2, "resume"), store.manifest.currentSubtaskIntent)
    assertEquals("wfl-2", store.manifest.subtasks.last().workflowId)
    assertEquals("implement", store.manifest.subtasks.last().lastResumableStep)
  }

  @Test
  fun `scoped replan refuses live goals without mutation`() {
    val store =
      refusalBaseStore().apply {
        executionLeaseForTest = idleLease().copy(expiresAt = Instant.parse("2026-07-27T12:00:01Z"))
      }
    val failure =
      assertFailsWith<IllegalArgumentException> {
        testGoalRunnerStatusService(
          manifestStore = store,
          outcomeStore = RecordingOutcomeStore(),
          phaseRecorder = goalTestPhaseRecorder(),
          clock = idleClock,
        )
          .replan(GoalRunnerReplanRequest("SKILL-56", 2))
      }
    assertTrue(failure.message!!.contains("live"), failure.message)
    assertEquals(0, store.scopedReplanCount)
    assertEquals(setOf(1, 2), store.plannedSubtaskIds)
  }

  @Test
  fun `scoped replan refuses unknown liveness without mutation`() {
    val store =
      refusalBaseStore(
        base =
          refusalBaseManifest().copy(
            currentSubtaskIntent = CurrentSubtaskIntent(2, "resume"),
            subtasks =
              listOf(
                refusalBaseManifest().subtasks[0],
                refusalBaseManifest().subtasks[1].copy(
                  status = "in_progress",
                  workflowId = "wfl-prose-unknown",
                  lastResumableStep = "implement",
                ),
              ),
          ),
      )
    val failure =
      assertFailsWith<IllegalArgumentException> {
        testGoalRunnerStatusService(
          manifestStore = store,
          outcomeStore = RecordingOutcomeStore(),
          phaseRecorder = goalTestPhaseRecorder(),
          clock = idleClock,
        )
          .replan(GoalRunnerReplanRequest("SKILL-56", 2))
      }
    assertTrue(failure.message!!.contains("unknown execution liveness"), failure.message)
    assertEquals(0, store.scopedReplanCount)
  }

  @Test
  fun `scoped replan refuses terminal targets naming reset without mutation`() {
    val store =
      refusalBaseStore(
        base =
          refusalBaseManifest().copy(
            subtasks =
              listOf(
                refusalBaseManifest().subtasks[0],
                refusalBaseManifest().subtasks[1].copy(status = "complete", commitSha = "sha-2"),
              ),
          ),
      ).apply { seedIdleLease() }
    val failure =
      assertFailsWith<IllegalArgumentException> {
        idleReplanService(store).replan(GoalRunnerReplanRequest("SKILL-56", 2))
      }
    assertTrue(failure.message!!.contains("reset"), failure.message)
    assertEquals(0, store.scopedReplanCount)
  }

  @Test
  fun `scoped replan refuses absent subtask without mutation`() {
    val store = refusalBaseStore().apply { seedIdleLease() }
    val failure =
      assertFailsWith<IllegalArgumentException> {
        idleReplanService(store).replan(GoalRunnerReplanRequest("SKILL-56", 9))
      }
    assertTrue(failure.message!!.contains("not part of goal"), failure.message)
    assertEquals(0, store.scopedReplanCount)
  }

  @Test
  fun `scoped replan returns null for unknown issue key without mutation`() {
    assertNull(
      idleReplanService(refusalBaseStore().apply { seedIdleLease() })
        .replan(GoalRunnerReplanRequest("SKILL-999", 1)),
    )
  }

  @Test
  fun `non-positive subtask id is rejected at the request boundary`() {
    assertFailsWith<IllegalArgumentException> {
      GoalRunnerReplanRequest(issueKey = "SKILL-56", subtaskId = 0)
    }
  }

  @Test
  fun `acceptances survive scoped replan`() {
    val store =
      InMemoryGoalManifestStore(
        manifest(subtaskCount = 2).copy(
          status = "in_progress",
          currentSubtaskIntent = CurrentSubtaskIntent(2, "start"),
          subtasks =
            listOf(
              manifest(subtaskCount = 2).subtasks[0].copy(status = "complete", commitSha = "abc1234"),
              manifest(subtaskCount = 2).subtasks[1].copy(status = "pending"),
            ),
        ),
      ).apply {
        plannedSubtaskIds = mutableSetOf(1, 2)
        persistOutOfBandAcceptance(
          "wfl-parent",
          GoalRunnerOutOfBandAcceptance(1, "abc1234", "shipped by hand", "2026-07-27T11:00:00Z"),
        )
        seedIdleLease()
      }
    val service = idleReplanService(store)

    service.replan(GoalRunnerReplanRequest("SKILL-56", 2))

    assertEquals(
      mapOf(1 to GoalRunnerOutOfBandAcceptance(1, "abc1234", "shipped by hand", "2026-07-27T11:00:00Z")),
      store.acceptances,
    )
    assertEquals("complete", store.manifest.subtasks.first().status)
    assertEquals("abc1234", store.manifest.subtasks.first().commitSha)
  }

  @Test
  fun `include-shared-preplan preserves complete-with-commit plans and cascades only non-terminals`() {
    val original =
      manifest(subtaskCount = 3).copy(
        status = "in_progress",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 3, action = "start"),
        subtasks =
          manifest(subtaskCount = 3).subtasks.map { subtask ->
            when (subtask.id) {
              1 -> subtask.copy(status = "complete", commitSha = "sha-1", workflowId = "wfl-1")
              2 -> subtask.copy(status = "pending")
              else -> subtask.copy(status = "pending")
            }
          },
      )
    val store =
      InMemoryGoalManifestStore(original).apply {
        plannedSubtaskIds = mutableSetOf(1, 2, 3)
        sharedPreplanPrepared = true
        persistOutOfBandAcceptance(
          "wfl-parent",
          GoalRunnerOutOfBandAcceptance(1, "sha-1", "landed outside", "2026-07-27T11:00:00Z"),
        )
        seedIdleLease()
      }
    val service = idleReplanService(store)

    val result =
      requireNotNull(
        service.replan(
          GoalRunnerReplanRequest(issueKey = "SKILL-56", subtaskId = 3, includeSharedPreplan = true),
        ),
      )

    assertTrue(result.discardedPlan)
    assertTrue(result.discardedSharedPreplan)
    assertEquals(listOf(2), result.cascadedPlanSubtaskIds)
    assertEquals(listOf(1, 2, 3), result.before.plannedSubtaskIds)
    assertEquals(listOf(1), result.after.plannedSubtaskIds)
    assertTrue(result.before.sharedPreplanPrepared)
    assertTrue(!result.after.sharedPreplanPrepared)
    assertEquals(setOf(1), store.plannedSubtaskIds.toSet())
    assertTrue(!store.sharedPreplanPrepared)
    assertEquals(true, store.lastIncludeSharedPreplan)
    assertEquals(original.subtasks, store.manifest.subtasks)
    assertEquals("complete", store.manifest.subtasks[0].status)
    assertEquals("sha-1", store.manifest.subtasks[0].commitSha)
    assertEquals("wfl-1", store.manifest.subtasks[0].workflowId)
    assertEquals(
      mapOf(1 to GoalRunnerOutOfBandAcceptance(1, "sha-1", "landed outside", "2026-07-27T11:00:00Z")),
      store.acceptances,
    )
  }

  @Test
  fun `include-shared-preplan WE-4719 shape retains every complete-with-commit sibling`() {
    val original =
      manifest(subtaskCount = 3).copy(
        status = "in_progress",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 3, action = "start"),
        subtasks =
          manifest(subtaskCount = 3).subtasks.map { subtask ->
            when (subtask.id) {
              1 -> subtask.copy(status = "complete", commitSha = "sha-1", workflowId = "wfl-1")
              2 -> subtask.copy(status = "complete", commitSha = "sha-2", workflowId = "wfl-2")
              else -> subtask.copy(status = "pending")
            }
          },
      )
    val store =
      InMemoryGoalManifestStore(original).apply {
        plannedSubtaskIds = mutableSetOf(1, 2, 3)
        sharedPreplanPrepared = true
        seedIdleLease()
      }
    val service = idleReplanService(store)

    val result =
      requireNotNull(
        service.replan(
          GoalRunnerReplanRequest(issueKey = "SKILL-56", subtaskId = 3, includeSharedPreplan = true),
        ),
      )

    assertEquals(emptyList(), result.cascadedPlanSubtaskIds)
    assertEquals(listOf(1, 2), result.after.plannedSubtaskIds)
    assertTrue(result.discardedSharedPreplan)
    assertEquals(setOf(1, 2), store.plannedSubtaskIds.toSet())
    assertEquals(original.subtasks, store.manifest.subtasks)
  }

  @Test
  fun `omitting include-shared-preplan leaves shared preplan and sibling plans`() {
    val store =
      InMemoryGoalManifestStore(
        manifest(subtaskCount = 3).copy(
          status = "in_progress",
          currentSubtaskIntent = CurrentSubtaskIntent(3, "start"),
          subtasks =
            manifest(subtaskCount = 3).subtasks.map { subtask ->
              when (subtask.id) {
                1 -> subtask.copy(status = "complete", commitSha = "sha-1")
                2 -> subtask.copy(status = "complete", commitSha = "sha-2")
                else -> subtask.copy(status = "pending")
              }
            },
        ),
      ).apply {
        plannedSubtaskIds = mutableSetOf(1, 2, 3)
        sharedPreplanPrepared = true
        seedIdleLease()
      }
    val service = idleReplanService(store)

    val result = requireNotNull(service.replan(GoalRunnerReplanRequest("SKILL-56", 3)))

    assertEquals(false, store.lastIncludeSharedPreplan)
    assertTrue(result.after.sharedPreplanPrepared)
    assertEquals(listOf(1, 2), result.after.plannedSubtaskIds)
    assertEquals(emptyList(), result.cascadedPlanSubtaskIds)
    assertTrue(!result.discardedSharedPreplan)
    assertEquals(setOf(1, 2), store.plannedSubtaskIds.toSet())
  }

  @Test
  fun `include-shared-preplan refuses when shared digest moved with zero mutation`() {
    val store =
      refusalBaseStore(
        base =
          manifest(subtaskCount = 3).copy(
            status = "in_progress",
            currentSubtaskIntent = CurrentSubtaskIntent(3, "start"),
            subtasks =
              manifest(subtaskCount = 3).subtasks.map { subtask ->
                when (subtask.id) {
                  1 -> subtask.copy(status = "complete", commitSha = "sha-1", workflowId = "wfl-1")
                  2 -> subtask.copy(status = "complete", commitSha = "sha-2", workflowId = "wfl-2")
                  else -> subtask.copy(status = "pending")
                }
              },
          ),
      ).apply {
        plannedSubtaskIds = mutableSetOf(1, 2, 3)
        sharedPreplanPrepared = true
        forceSharedDigestMismatchOnReplan = true
        seedIdleLease()
      }
    val failure =
      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> {
        idleReplanService(store)
          .replan(GoalRunnerReplanRequest("SKILL-56", 3, includeSharedPreplan = true))
      }
    assertTrue(failure.message!!.contains("shared preplan changed"), failure.message)
    assertEquals(1, store.scopedReplanCount)
    assertEquals(setOf(1, 2, 3), store.plannedSubtaskIds.toSet())
    assertTrue(store.sharedPreplanPrepared)
  }

  @Test
  fun `status after include-shared-preplan reports shared_preplan false with regeneration pending`() {
    val store =
      InMemoryGoalManifestStore(
        manifest(subtaskCount = 3).copy(
          status = "in_progress",
          currentSubtaskIntent = CurrentSubtaskIntent(3, "start"),
          subtasks =
            manifest(subtaskCount = 3).subtasks.map { subtask ->
              when (subtask.id) {
                1 -> subtask.copy(status = "complete", commitSha = "sha-1", workflowId = "wfl-1")
                2 -> subtask.copy(status = "complete", commitSha = "sha-2", workflowId = "wfl-2")
                else -> subtask.copy(status = "pending")
              }
            },
        ),
      ).apply {
        plannedSubtaskIds = mutableSetOf(1, 2, 3)
        sharedPreplanPrepared = true
        seedIdleLease()
      }
    val service = idleReplanService(store)
    service.replan(GoalRunnerReplanRequest("SKILL-56", 3, includeSharedPreplan = true))

    val status =
      requireNotNull(
        service.status(
          GoalRunnerStatusRequest(
            issueKey = "SKILL-56",
            invokedAgentId = "codex",
          ),
        ),
      )
    val planning = requireNotNull(status.planning)
    assertTrue(!planning.sharedPreplanPrepared)
    assertEquals(NOT_STARTED, planning.state)
    assertTrue(
      planning.reason!!.contains("not started") || planning.reason!!.contains("preplan"),
      planning.reason,
    )
    assertEquals("complete", store.manifest.subtasks[0].status)
    assertEquals("sha-1", store.manifest.subtasks[0].commitSha)
    assertEquals("wfl-1", store.manifest.subtasks[0].workflowId)
    assertEquals("complete", store.manifest.subtasks[1].status)
    assertEquals("pending", store.manifest.subtasks[2].status)
  }

  @Test
  fun `scoped replan prunes the cleared child's checkpoint refs and keeps its siblings'`() {
    val repo = Files.createTempDirectory("skillbill-replan-checkpoint-refs")
    try {
      initGitRepository(repo)
      val git = GitWorkflowGitOperations()
      val head = gitCommand(repo, "rev-parse", "HEAD")
      val siblingRef = featureTaskRuntimeCheckpointRefName("SKILL-56", "1", 0)
      listOf(
        siblingRef,
        featureTaskRuntimeCheckpointRefName("SKILL-56", "2", 0),
        featureTaskRuntimeCheckpointRefName("SKILL-56", "2", 1),
      ).forEach { ref ->
        assertIs<WorkflowGitOperationResult.Ok>(
          git.updateCheckpointRef(repo, FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE, ref, head),
        )
      }
      val store =
        refusalBaseStore().apply {
          seedIdleLease()
          clearedChildSubtaskIdsOnReplan = listOf(2)
        }
      val service =
        testGoalRunnerStatusService(
          manifestStore = store,
          outcomeStore = RecordingOutcomeStore(),
          phaseRecorder = goalTestPhaseRecorder(),
          clock = idleClock,
          ports = GoalRunnerStatusTestPorts(gitOperations = git, workerSupervisor = DeadProcessSupervisor),
        )

      service.replan(GoalRunnerReplanRequest("SKILL-56", subtaskId = 2, repoRoot = repo))

      val remaining =
        assertIs<WorkflowGitNameListResult.Listed>(
          git.listCheckpointRefs(repo, "$FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE/SKILL-56/"),
        ).names
      assertEquals(listOf(siblingRef), remaining.map(String::trim))
    } finally {
      repo.toFile().deleteRecursively()
    }
  }

  private fun initGitRepository(repo: Path) {
    gitCommand(repo, "init", "--initial-branch", "main")
    gitCommand(repo, "config", "user.email", "runtime@skill-bill.test")
    gitCommand(repo, "config", "user.name", "Skill Bill Runtime")
    gitCommand(repo, "config", "commit.gpgsign", "false")
    repo.resolve("Base.kt").writeText("base\n")
    gitCommand(repo, "add", "-A")
    gitCommand(repo, "commit", "-m", "base")
  }

  private fun gitCommand(
    repo: Path,
    vararg args: String,
  ): String {
    val process =
      ProcessBuilder(listOf("git", "-C", repo.toString()) + args.toList())
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "git ${args.joinToString(" ")} failed with $exitCode: $output" }
    return output
  }

  private fun refusalBaseManifest() =
    manifest(subtaskCount = 2).copy(
      status = "in_progress",
      currentSubtaskIntent = CurrentSubtaskIntent(2, "start"),
      subtasks =
        listOf(
          manifest(subtaskCount = 2).subtasks[0].copy(status = "complete", commitSha = "sha-1"),
          manifest(subtaskCount = 2).subtasks[1].copy(status = "pending"),
        ),
    )

  private fun refusalBaseStore(base: DecompositionManifest = refusalBaseManifest()): InMemoryGoalManifestStore =
    InMemoryGoalManifestStore(base).apply {
      plannedSubtaskIds = mutableSetOf(1, 2)
    }

  private fun InMemoryGoalManifestStore.seedIdleLease() {
    executionLeaseForTest = idleLease()
  }

  private fun idleReplanService(store: InMemoryGoalManifestStore) =
    testGoalRunnerStatusService(
      manifestStore = store,
      outcomeStore = RecordingOutcomeStore(),
      phaseRecorder = goalTestPhaseRecorder(),
      clock = idleClock,
      ports = GoalRunnerStatusTestPorts(workerSupervisor = DeadProcessSupervisor),
    )

  private fun idleLease(): GoalRunnerExecutionLease =
    GoalRunnerExecutionLease(
      generation = 1,
      ownerToken = "parent-owner",
      hostIdentity = "host",
      bootIdentity = "boot",
      pid = 42,
      processBirthToken = "birth-42",
      heartbeatAt = "2026-07-27T11:59:50Z",
      expiresAt = "2026-07-27T11:59:59Z",
    )
}
