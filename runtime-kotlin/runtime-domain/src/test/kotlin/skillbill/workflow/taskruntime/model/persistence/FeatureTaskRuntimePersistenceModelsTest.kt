package skillbill.workflow.taskruntime.model.persistence

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.contracts.JsonCodec
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_RUN_INVARIANTS_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.FeatureTaskRuntimeCommitPushResultArtifact
import skillbill.goalrunner.commitPushResultArtifact
import skillbill.goalrunner.goalContinuationOutcomeArtifact
import skillbill.goalrunner.goalSubtaskReviewArtifacts
import skillbill.goalrunner.model.FeatureTaskRuntimeGoalContinuationOutcome
import skillbill.goalrunner.model.asGoalRunnerIntOrNull
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.decomposition.DecompositionManifestWireCodec
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionManifestWireMap
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.runtime.DecompositionManifestProjectionFailureArtifact
import skillbill.workflow.decomposition.runtime.decompositionManifestProjectionFailure
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.decomposition.runtime.goalParentArtifactProjection
import skillbill.workflow.engine.model.DECOMPOSITION_MANIFEST_PROJECTION_FAILURE_ARTIFACT_KEY
import skillbill.workflow.engine.model.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME_ARTIFACT_KEY
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_REVIEW_GENERATION_ARTIFACT_KEY
import skillbill.workflow.engine.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.persistence.artifact.appendBoundedHistoryBySequence
import skillbill.workflow.model.persistence.artifact.durableArtifactMapReader
import skillbill.workflow.taskruntime.artifact.decomposeTerminal
import skillbill.workflow.taskruntime.artifact.goalContinuationFieldAdoption
import skillbill.workflow.taskruntime.artifact.operatorBlockRetry
import skillbill.workflow.taskruntime.artifact.phaseLedger
import skillbill.workflow.taskruntime.artifact.phaseRecords
import skillbill.workflow.taskruntime.artifact.resolvedBranch
import skillbill.workflow.taskruntime.artifact.reviewGeneration
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputSourceLocation
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection.BUILD
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection.VALIDATE
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimePersistenceModelsTest {
  @Test
  fun `typed phase accessors preserve decoded values and reject present null artifacts`() {
    val record =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "plan",
        status = "running",
        attemptCount = 1,
        startedAt = "2026-08-11T12:00:00Z",
        resolvedAgentId = "planner",
      )
    val ledger =
      FeatureTaskRuntimePhaseLedgerEntry(
        action = FeatureTaskRuntimePhaseLedgerAction.START,
        sequenceNumber = 0,
        timestamp = "2026-08-11T12:00:00Z",
        phaseId = "plan",
        attemptCount = 1,
      )
    val artifacts =
      DurableWorkflowArtifacts.fromMap(
        mapOf(
          FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to mapOf("plan" to record.toArtifactMap()),
          FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY to listOf(ledger.toArtifactMap()),
        ),
      )

    assertEquals(mapOf("plan" to record), artifacts.phaseRecords())
    assertEquals(listOf(ledger), artifacts.phaseLedger())
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      DurableWorkflowArtifacts.fromMap(
        mapOf(FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to null),
      ).phaseRecords()
    }
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      DurableWorkflowArtifacts.fromMap(
        mapOf(FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY to null),
      ).phaseLedger()
    }
  }

  @Test
  fun `remaining task runtime accessors preserve their decoded fixture values`() {
    val artifacts =
      DurableWorkflowArtifacts.fromMap(
        mapOf(
          FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY to
            mapOf(
              "reason" to "decomposition complete",
              "parent_spec_path" to ".feature-specs/SKILL-372/spec.md",
              "decomposition_manifest_path" to ".feature-specs/SKILL-372/decomposition-manifest.yaml",
              "subtask_spec_paths" to listOf(".feature-specs/SKILL-372/subtask_1.md"),
            ),
          FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY to
            mapOf(
              "branch" to "feat/SKILL-372",
              "created" to true,
              "baseline_owned_paths" to listOf("runtime-kotlin/runtime-domain"),
            ),
          FEATURE_TASK_RUNTIME_REVIEW_GENERATION_ARTIFACT_KEY to 3,
          FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY to
            mapOf(
              "phase_id" to "review",
              "reason" to "operator requested retry",
              "retried_at" to "2026-09-24T10:00:00Z",
            ),
          FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY to
            mapOf(
              "field" to "validation_depth",
              "adopted_value" to "full",
              "reason" to "legacy artifact",
            ),
        ),
      )

    assertEquals(
      "decomposition complete",
      artifacts.decomposeTerminal()?.reason,
    )
    assertEquals(
      FeatureTaskRuntimeResolvedBranch(
        branch = "feat/SKILL-372",
        created = true,
        baselineOwnedPaths = listOf("runtime-kotlin/runtime-domain"),
      ),
      artifacts.resolvedBranch(),
    )
    assertEquals(3, artifacts.reviewGeneration())
    assertEquals(
      FeatureTaskRuntimeOperatorBlockRetry(
        phaseId = "review",
        reason = "operator requested retry",
        retriedAt = "2026-09-24T10:00:00Z",
      ),
      artifacts.operatorBlockRetry(),
    )
    assertEquals(
      FeatureTaskRuntimeGoalContinuationFieldAdoption(
        field = "validation_depth",
        adoptedValue = "full",
        reason = "legacy artifact",
      ),
      artifacts.goalContinuationFieldAdoption(),
    )
  }

  @Test
  fun `durable artifact integer reader preserves integral values and rejects lossy values`() {
    val reader =
      durableArtifactMapReader(
        mapOf(
          "byte" to 7.toByte(),
          "decimal" to BigDecimal("8"),
          "string" to "9",
          "fractional" to BigDecimal("2.7"),
          "overflow" to BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
        ),
      )

    assertEquals(7, reader.requiredInt("byte"))
    assertEquals(8, reader.requiredInt("decimal"))
    assertEquals(9, reader.requiredInt("string"))
    assertEquals(10, durableArtifactMapReader(mapOf("whole" to 10.0)).requiredInt("whole"))
    assertFailsWith<InvalidWorkflowStateSchemaError> { reader.optionalInt("fractional") }
    assertFailsWith<InvalidWorkflowStateSchemaError> { reader.optionalLong("overflow") }
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      durableArtifactMapReader(mapOf("nonFinite" to Double.NaN)).requiredInt("nonFinite")
    }
    assertFailsWith<InvalidWorkflowStateSchemaError> { 2.7.asGoalRunnerIntOrNull() }
  }

  @Test
  fun `decomposition accessor preserves the manifest and rejects a present null artifact`() {
    val manifest =
      DecompositionManifest(
        issueKey = "SKILL-372",
        featureName = "domain boundaries",
        parentSpecPath = ".feature-specs/SKILL-372/spec.md",
        baseBranch = "main",
        featureBranch = "feat/SKILL-372",
        currentSubtaskIntent =
          CurrentSubtaskIntent(
            subtaskId = 2,
            action = "implement",
          ),
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 2,
              name = "domain artifacts",
              specPath = ".feature-specs/SKILL-372/subtask_2.md",
            ),
          ),
      )
    val artifacts =
      DurableWorkflowArtifacts.fromMap(
        mapOf(
          DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
            DecompositionManifestWireMap.from(
              DecompositionManifestWireCodec.encode(manifest),
            ),
        ),
      )

    assertEquals(manifest, artifacts.decompositionRuntime())
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      DurableWorkflowArtifacts.fromMap(mapOf(DECOMPOSITION_RUNTIME_ARTIFACT_KEY to null))
        .decompositionRuntime()
    }
  }

  @Test
  fun `projection failure accessor distinguishes absent malformed and typed values`() {
    assertNull(DurableWorkflowArtifacts.EMPTY.decompositionManifestProjectionFailure())
    assertEquals(
      DecompositionManifestProjectionFailureArtifact(
        operation = "write",
        targetPath = ".feature-specs/SKILL-372/decomposition-manifest.yaml",
      ),
      DurableWorkflowArtifacts.fromMap(
        mapOf(
          DECOMPOSITION_MANIFEST_PROJECTION_FAILURE_ARTIFACT_KEY to
            mapOf(
              "operation" to "write",
              "target_path" to ".feature-specs/SKILL-372/decomposition-manifest.yaml",
            ),
        ),
      ).decompositionManifestProjectionFailure(),
    )
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      DurableWorkflowArtifacts.fromMap(
        mapOf(DECOMPOSITION_MANIFEST_PROJECTION_FAILURE_ARTIFACT_KEY to null),
      ).decompositionManifestProjectionFailure()
    }
  }

  @Test
  fun `goal parent projection preserves unrelated artifacts while removing legacy controls`() {
    val projected =
      goalParentArtifactProjection(
        existing =
          DurableWorkflowArtifacts.fromMap(
            linkedMapOf(
              "plan" to mapOf("mode" to "decompose"),
              "goal_review_policy" to mapOf("code_review_mode" to "inline"),
              "goal_out_of_band_acceptances" to listOf(mapOf("subtask_id" to 1)),
            ),
          ),
        encodedManifest =
          FeatureTaskRuntimeWorkflowArtifactMap.from(
            linkedMapOf("issue_key" to "SKILL-372", "status" to "in_progress"),
          ),
      )

    assertEquals(
      WorkflowArtifactPatch.from(
        linkedMapOf(
          "plan" to mapOf("mode" to "decompose"),
          "decomposition_runtime" to linkedMapOf("issue_key" to "SKILL-372", "status" to "in_progress"),
        ),
      ),
      projected,
    )
  }

  @Test
  fun `launched model and effort round-trip through the phase record artifact map`() {
    val wire =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "implement",
        status = "running",
        attemptCount = 1,
        startedAt = "2026-08-11T12:00:00Z",
        resolvedAgentId = "cursor",
        launchedModel = "claude-opus-4-8[effort=high]",
        launchedEffort = "high",
      ).toArtifactMap()

    val decoded = FeatureTaskRuntimePhaseRecord.fromArtifactMap(wire)

    assertEquals("claude-opus-4-8[effort=high]", decoded.launchedModel)
    assertEquals("high", decoded.launchedEffort)
  }

  @Test
  fun `phase record written before the launched model fields still decodes`() {
    val preChangeWire =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "implement",
        status = "running",
        attemptCount = 1,
        startedAt = "2026-08-11T12:00:00Z",
        resolvedAgentId = "claude",
      ).toArtifactMap()

    assertTrue("launched_model" !in preChangeWire)
    assertTrue("launched_effort" !in preChangeWire)
    val decoded = FeatureTaskRuntimePhaseRecord.fromArtifactMap(preChangeWire)
    assertNull(decoded.launchedModel)
    assertNull(decoded.launchedEffort)
  }

  @Test
  fun `a present-but-blank launched model or effort fails record construction`() {
    val base =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "implement",
        status = "running",
        attemptCount = 1,
        startedAt = "2026-08-11T12:00:00Z",
        resolvedAgentId = "claude",
      )

    assertFailsWith<IllegalArgumentException> { base.copy(launchedModel = " ") }
    assertFailsWith<IllegalArgumentException> { base.copy(launchedEffort = " ") }
  }

  @Test
  fun `legacy rejected output is consumed and discarded during phase record migration`() {
    val current =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "implement",
        status = "blocked",
        attemptCount = 1,
        startedAt = "2026-07-28T12:00:00Z",
        resolvedAgentId = "codex",
      ).toArtifactMap()

    val decoded = FeatureTaskRuntimePhaseRecord.fromArtifactMap(current + ("rejected_output" to "private body"))

    assertNull(decoded.rejectedOutput)
    assertTrue("rejected_output" !in decoded.toArtifactMap())
  }

  @Test
  fun `phase execution origin rejects incompatible legacy and unknown explicit values`() {
    val current =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "plan",
        status = "completed",
        attemptCount = 1,
        startedAt = "2026-07-18T12:00:00Z",
        resolvedAgentId = "planner",
      ).toArtifactMap()
    val legacy = current - setOf("contract_version", "record_kind", "first_started_at", "execution_origin")
    val error =
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        FeatureTaskRuntimePhaseRecord.fromArtifactMap(legacy)
      }
    assertTrue(error.message.orEmpty().contains("restart the active run"))
    assertTrue(error.message.orEmpty().contains("out-of-band migration"))
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseRecord.fromArtifactMap(current + ("execution_origin" to "fabricated"))
    }
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap(
        mapOf(
          "action" to "complete",
          "sequence_number" to 0,
          "timestamp" to "2026-07-18T12:00:00Z",
          "phase_id" to "plan",
          "attempt_count" to 1,
          "execution_origin" to "fabricated",
        ),
      )
    }
  }

  @Test
  fun `phase record round trips typed failure disposition and file manifests`() {
    val record =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "review",
        status = "blocked",
        attemptCount = 1,
        startedAt = "2026-07-13T12:00:00Z",
        resolvedAgentId = "reviewer",
        blockedReason = "Policy conflict.",
        failureDisposition = FeatureTaskRuntimeFailureDisposition.NON_RETRYABLE_POLICY_CONFLICT,
        fileManifestBefore = listOf("src/Existing.kt"),
        fileManifestAfter = listOf(".feature-specs/SKILL-124/spec.md", "src/Existing.kt"),
        fileManifestIntroduced = listOf(".feature-specs/SKILL-124/spec.md"),
      )

    assertEquals(record, FeatureTaskRuntimePhaseRecord.fromArtifactMap(record.toArtifactMap()))
  }

  @Test
  fun `per-phase record round trips through its artifact map`() {
    val record =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "implement",
        status = "completed",
        attemptCount = 2,
        startedAt = "2026-06-02T10:00:00Z",
        finishedAt = "2026-06-02T10:05:00Z",
        durationMillis = 300_000,
        resolvedAgentId = "agent-implement-1",
        outputArtifact = """{"contract_version":"0.2"}""",
      )
    val decoded = FeatureTaskRuntimePhaseRecord.fromArtifactMap(record.toArtifactMap())
    assertEquals(record, decoded)
  }

  @Test
  fun `per-phase record persists typed structural repair evidence without payload text`() {
    val evidence =
      FeatureTaskRuntimePhaseOutputRepairEvidence(
        format = FeatureTaskRuntimePhaseOutputFormat.JSON,
        originalDigest = "a".repeat(64),
        repairedDigest = "b".repeat(64),
        operation = FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER,
        sourceLocation =
          FeatureTaskRuntimePhaseOutputSourceLocation(
            sourceLabel = "plan",
            offset = 17,
            line = 2,
            column = 4,
          ),
      )
    val record =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "plan",
        status = "completed",
        attemptCount = 1,
        startedAt = "2026-06-02T10:00:00Z",
        resolvedAgentId = "planner",
        outputArtifact = "{\"phase_id\":\"plan\"}",
        repairEvidence = evidence,
      )

    val artifact = record.toArtifactMap()
    assertEquals(evidence, FeatureTaskRuntimePhaseRecord.fromArtifactMap(artifact).repairEvidence)
    assertTrue("original_payload" !in artifact.toString())
  }

  @Test
  fun `resolved-branch round trips through its artifact map when created`() {
    val resolved =
      FeatureTaskRuntimeResolvedBranch(
        branch = "feat/SKILL-65.1-runtime-feature-task-parity",
        baseBranch = "main",
        created = true,
        reviewBaseSha = "a".repeat(40),
        baselineUntrackedPaths = listOf("owned-new.txt"),
        baselineOwnedPaths = listOf("tracked-before.kt", "owned-new.txt"),
        workflowOwnedPaths = listOf("src/Changed.kt"),
      )
    val decoded = FeatureTaskRuntimeResolvedBranch.fromArtifactMap(resolved.toArtifactMap())
    assertEquals(resolved, decoded)
    assertTrue(decoded.created)
    assertEquals("main", decoded.baseBranch)
    assertEquals("a".repeat(40), decoded.reviewBaseSha)
    assertEquals(listOf("owned-new.txt"), decoded.baselineUntrackedPaths)
    assertEquals(listOf("tracked-before.kt", "owned-new.txt"), decoded.baselineOwnedPaths)
    assertEquals(listOf("src/Changed.kt"), decoded.workflowOwnedPaths)
  }

  @Test
  fun `reused resolved-branch omits base branch and defaults created false`() {
    val resolved = FeatureTaskRuntimeResolvedBranch(branch = "feat/pre-created-branch")
    val map = resolved.toArtifactMap()
    assertNull(map["base_branch"])
    assertEquals(false, map["created"])
    assertEquals(resolved, FeatureTaskRuntimeResolvedBranch.fromArtifactMap(map))
  }

  @Test
  fun `resolved-branch decode loud-fails on a blank branch`() {
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimeResolvedBranch.fromArtifactMap(mapOf("branch" to ""))
    }
  }

  @Test
  fun `resolved-branch decode rejects malformed list elements with a typed schema error`() {
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimeResolvedBranch.fromArtifactMap(
        mapOf(
          "branch" to "feat/example",
          "baseline_owned_paths" to listOf("tracked.kt", 7),
        ),
      )
    }
  }

  @Test
  fun `running per-phase record omits finish and duration and output`() {
    val record =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "plan",
        status = "running",
        attemptCount = 1,
        startedAt = "2026-06-02T10:00:00Z",
        resolvedAgentId = "agent-plan-1",
      )
    val map = record.toArtifactMap()
    assertNull(map["finished_at"])
    assertNull(map["duration_millis"])
    assertNull(map["output_artifact"])
    assertEquals(record, FeatureTaskRuntimePhaseRecord.fromArtifactMap(map))
  }

  @Test
  fun `blocked per-phase record round trips with blocked reason and distinct first started at`() {
    val record =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "review",
        status = "blocked",
        attemptCount = 3,
        startedAt = "2026-06-02T10:10:00Z",
        firstStartedAt = "2026-06-02T10:00:00Z",
        resolvedAgentId = "agent-review-1",
        blockedReason = "exhausted the bounded fix loop",
      )
    val decoded = FeatureTaskRuntimePhaseRecord.fromArtifactMap(record.toArtifactMap())
    assertEquals(record, decoded)
    assertEquals(Instant.parse("2026-06-02T10:00:00Z"), decoded.firstStartedAt)
    assertEquals("exhausted the bounded fix loop", decoded.blockedReason)
  }

  @Test
  fun `rejected raw output never enters the artifact map`() {
    val record =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "implement",
        status = "blocked",
        attemptCount = 1,
        startedAt = "2026-06-02T10:00:00Z",
        resolvedAgentId = "agent-implement-1",
        rejectedOutput = "sentinel-private-body",
      )

    val artifact = record.toArtifactMap()

    assertEquals(false, artifact.containsKey("rejected_output"))
    assertEquals(false, artifact.toString().contains("sentinel-private-body"))
    assertNull(FeatureTaskRuntimePhaseRecord.fromArtifactMap(artifact).rejectedOutput)
  }

  @Test
  fun `per-phase record rejects missing legacy first started at`() {
    val current =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "plan",
        status = "running",
        attemptCount = 1,
        startedAt = "2026-06-02T10:00:00Z",
        resolvedAgentId = "agent-plan-1",
      ).toArtifactMap()
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseRecord.fromArtifactMap(current - "first_started_at")
    }
  }

  @Test
  fun `per-phase record decode loud-fails on missing required field`() {
    val malformed =
      mapOf(
        "phase_id" to "plan",
        "status" to "completed",
        "started_at" to "2026-06-02T10:00:00Z",
        "resolved_agent_id" to "agent-plan-1",
      )
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseRecord.fromArtifactMap(malformed)
    }
  }

  @Test
  fun `per-phase record decode loud-fails on wrong-typed attempt count`() {
    val malformed =
      mapOf(
        "phase_id" to "plan",
        "status" to "completed",
        "attempt_count" to "not-an-int",
        "started_at" to "2026-06-02T10:00:00Z",
        "resolved_agent_id" to "agent-plan-1",
      )
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseRecord.fromArtifactMap(malformed)
    }
  }

  @Test
  fun `ledger entry round trips through its artifact map for every action`() {
    FeatureTaskRuntimePhaseLedgerAction.entries.forEachIndexed { index, action ->
      val entry =
        FeatureTaskRuntimePhaseLedgerEntry(
          action = action,
          sequenceNumber = index,
          timestamp = "2026-06-02T10:0$index:00Z",
          phaseId = "review",
          attemptCount = 1,
          resolvedAgentId = "agent-review-1",
          fixLoopIteration = if (action == FeatureTaskRuntimePhaseLedgerAction.FIX_LOOP_ITERATION) 2 else null,
          blockedReason = if (action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED) "gate failed" else null,
        )
      assertEquals(entry, FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap(entry.toArtifactMap()))
    }
  }

  @Test
  fun `ledger action wire values cover the required event set`() {
    assertEquals(
      listOf(
        "start",
        "resume",
        "retry",
        "fix_loop_iteration",
        "loop_edge",
        "loop_cap_exhausted",
        "blocked",
        "paused",
        "complete",
      ),
      FeatureTaskRuntimePhaseLedgerAction.entries.map { it.wireValue },
    )
  }

  @Test
  fun `per-phase record round trips loop_id and edge_iteration additively`() {
    val record =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "implement",
        status = "running",
        attemptCount = 2,
        startedAt = "2026-06-02T10:00:00Z",
        resolvedAgentId = "agent-implement-1",
        loopId = "review-fix",
        edgeIteration = 2,
      )
    val map = record.toArtifactMap()
    assertEquals("review-fix", map["loop_id"])
    assertEquals(2, map["edge_iteration"])
    assertEquals(record, FeatureTaskRuntimePhaseRecord.fromArtifactMap(map))
  }

  @Test
  fun `removed plan_fix phase id loud-fails on private phase records`() {
    val record =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "implement",
        status = "completed",
        attemptCount = 1,
        startedAt = "2026-06-02T10:00:00Z",
        resolvedAgentId = "agent-implement-1",
      )
    val error =
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        FeatureTaskRuntimePhaseRecord.fromArtifactMap(record.toArtifactMap() + ("phase_id" to "plan_fix"))
      }
    assertTrue(error.message.orEmpty().contains("plan_fix"))
  }

  @Test
  fun `removed plan_fix phase id loud-fails on phase ledger entries`() {
    val entry =
      FeatureTaskRuntimePhaseLedgerEntry(
        action = FeatureTaskRuntimePhaseLedgerAction.START,
        sequenceNumber = 1,
        timestamp = "2026-06-02T10:00:00Z",
        phaseId = "implement",
        attemptCount = 1,
      )
    val error =
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap(entry.toArtifactMap() + ("phase_id" to "plan_fix"))
      }
    assertTrue(error.message.orEmpty().contains("plan_fix"))
  }

  @Test
  fun `review phase record round trips the durably reserved pass number`() {
    val record =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "review",
        status = "running",
        attemptCount = 2,
        startedAt = "2026-06-02T10:00:00Z",
        resolvedAgentId = "agent-review-1",
        loopId = "review_fix",
        edgeIteration = 1,
        reviewPassNumber = 2,
      )

    val map = record.toArtifactMap()

    assertEquals(2, map["review_pass_number"])
    assertEquals(record, FeatureTaskRuntimePhaseRecord.fromArtifactMap(map))
    listOf(3, 7, 42).forEach { pass ->
      assertEquals(
        pass,
        FeatureTaskRuntimePhaseRecord.fromArtifactMap(map + ("review_pass_number" to pass)).reviewPassNumber,
      )
    }
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseRecord.fromArtifactMap(map + ("review_pass_number" to 0))
    }
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseRecord.fromArtifactMap(map + ("phase_id" to "audit"))
    }
  }

  @Test
  fun `per-phase record omits optional loop context while retaining required wire identity`() {
    val record =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "plan",
        status = "running",
        attemptCount = 1,
        startedAt = "2026-06-02T10:00:00Z",
        resolvedAgentId = "agent-plan-1",
      )
    val map = record.toArtifactMap()
    assertNull(map["loop_id"])
    assertNull(map["edge_iteration"])
    assertNull(map["review_pass_number"])
    assertEquals(FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION, map["contract_version"])
    assertEquals("private_phase_record", map["record_kind"])
    val decoded = FeatureTaskRuntimePhaseRecord.fromArtifactMap(map)
    assertNull(decoded.loopId)
    assertNull(decoded.edgeIteration)
  }

  @Test
  fun `per-phase record decode loud-fails on edge_iteration below one`() {
    val malformed =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "implement",
        status = "running",
        attemptCount = 1,
        startedAt = "2026-06-02T10:00:00Z",
        resolvedAgentId = "agent-implement-1",
        loopId = "review-fix",
        edgeIteration = 1,
      ).toArtifactMap() + ("edge_iteration" to 0)
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseRecord.fromArtifactMap(malformed)
    }
  }

  @Test
  fun `ledger entry round trips loop_id and edge_iteration additively`() {
    val entry =
      FeatureTaskRuntimePhaseLedgerEntry(
        action = FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE,
        sequenceNumber = 7,
        timestamp = "2026-06-02T10:07:00Z",
        phaseId = "implement",
        attemptCount = 1,
        loopId = "review-fix",
        edgeIteration = 3,
      )
    val map = entry.toArtifactMap()
    assertEquals("review-fix", map["loop_id"])
    assertEquals(3, map["edge_iteration"])
    assertEquals(entry, FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap(map))

    val legacy =
      mapOf(
        "action" to "retry",
        "sequence_number" to 0,
        "timestamp" to "2026-06-02T10:00:00Z",
        "phase_id" to "review",
        "attempt_count" to 1,
      )
    val decoded = FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap(legacy)
    assertNull(decoded.loopId)
    assertNull(decoded.edgeIteration)
  }

  @Test
  fun `unknown ledger action loud-fails with a typed schema error`() {
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseLedgerAction.fromWire("teleport")
    }
  }

  @Test
  fun `run-invariants decode loud-fails with typed schema error on unknown feature size`() {
    val malformed =
      mapOf(
        "contract_version" to FEATURE_TASK_RUNTIME_RUN_INVARIANTS_CONTRACT_VERSION,
        "spec_reference" to ".feature-specs/SKILL-65/spec.md",
        "feature_size" to "HUGE",
        "acceptance_criteria" to listOf("AC-1"),
        "mandates_and_overrides" to emptyList<String>(),
        "code_review_mode" to "auto",
      )

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      featureTaskRuntimeRunInvariantsFromArtifactMap(malformed)
    }
  }

  @Test
  fun `run-invariants decode translates constructor invariant failures to typed schema errors`() {
    val malformed =
      mapOf(
        "contract_version" to FEATURE_TASK_RUNTIME_RUN_INVARIANTS_CONTRACT_VERSION,
        "spec_reference" to ".feature-specs/SKILL-135/spec.md",
        "feature_size" to "MEDIUM",
        "acceptance_criteria" to List(1_000) { index -> "Criterion ${index + 1}" },
        "mandates_and_overrides" to emptyList<String>(),
        "code_review_mode" to "auto",
      )

    val error =
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        featureTaskRuntimeRunInvariantsFromArtifactMap(malformed)
      }

    assertIs<IllegalArgumentException>(error.cause)
    assertTrue(error.message.orEmpty().contains("supports at most 999 criteria"))
  }

  @Test
  fun `run-invariants persist the selected code-review mode strictly`() {
    CodeReviewExecutionMode.entries.forEach { mode ->
      val invariants =
        FeatureTaskRuntimeRunInvariants(
          specReference = ".feature-specs/SKILL-119/spec.md",
          acceptanceCriteria = listOf("AC-1"),
          mandatesAndOverrides = emptyList(),
          codeReviewMode = mode,
        )
      val map = invariants.toArtifactMap()

      assertEquals(mode.wireValue, map["code_review_mode"])
      assertEquals(invariants, featureTaskRuntimeRunInvariantsFromArtifactMap(map))
    }
    val invalid =
      FeatureTaskRuntimeRunInvariants(
        specReference = ".feature-specs/SKILL-119/spec.md",
        acceptanceCriteria = listOf("AC-1"),
        mandatesAndOverrides = emptyList(),
      ).toArtifactMap()
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      featureTaskRuntimeRunInvariantsFromArtifactMap(invalid + ("code_review_mode" to "DELEGATED"))
    }
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      featureTaskRuntimeRunInvariantsFromArtifactMap(invalid - "code_review_mode")
    }
  }

  @Test
  fun `run-invariants stamp the durable contract version`() {
    val map =
      FeatureTaskRuntimeRunInvariants(
        specReference = ".feature-specs/SKILL-159/spec.md",
        acceptanceCriteria = listOf("AC-1"),
        mandatesAndOverrides = emptyList(),
      ).toArtifactMap()

    assertEquals(FEATURE_TASK_RUNTIME_RUN_INVARIANTS_CONTRACT_VERSION, map["contract_version"])
  }

  @Test
  fun `pre-SKILL-159 run invariants loud-fail instead of being reinterpreted under the new mode semantics`() {
    val current =
      FeatureTaskRuntimeRunInvariants(
        specReference = ".feature-specs/SKILL-159/spec.md",
        acceptanceCriteria = listOf("AC-1"),
        mandatesAndOverrides = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.DELEGATED,
      ).toArtifactMap()

    val unversioned =
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        featureTaskRuntimeRunInvariantsFromArtifactMap(current - "contract_version")
      }
    assertTrue(unversioned.message.orEmpty().contains("contract_version"))

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      featureTaskRuntimeRunInvariantsFromArtifactMap(current + ("contract_version" to "0.0"))
    }
  }

  @Test
  fun `ledger entry decode loud-fails when attempt count is zero`() {
    val malformed =
      mapOf(
        "action" to "start",
        "sequence_number" to 0,
        "timestamp" to "2026-06-02T10:00:00Z",
        "phase_id" to "plan",
        "attempt_count" to 0,
      )
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap(malformed)
    }
  }

  @Test
  fun `ledger entry decode loud-fails on missing timestamp`() {
    val malformed =
      mapOf(
        "action" to "start",
        "sequence_number" to 0,
        "phase_id" to "plan",
        "attempt_count" to 1,
      )
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap(malformed)
    }
  }

  @Test
  fun `append-only ledger keeps monotonic sequence order and prunes oldest beyond the limit`() {
    var ledger = emptyList<Any>()
    (0 until FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT + 3).forEach { index ->
      val entry =
        FeatureTaskRuntimePhaseLedgerEntry(
          action = FeatureTaskRuntimePhaseLedgerAction.RETRY,
          sequenceNumber = index,
          timestamp = "2026-06-02T10:00:00Z",
          phaseId = "implement",
          attemptCount = 1,
        )
      ledger = appendBoundedHistoryBySequence(ledger, entry.toArtifactMap(), FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT)
    }
    assertEquals(FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT, ledger.size)
    val sequences =
      ledger.map { entry ->
        (JsonCodec.anyToStringAnyMap(entry)?.get("sequence_number") as Number).toInt()
      }
    assertEquals(sequences.sorted(), sequences)
    assertEquals(3, sequences.first())
    assertEquals(FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT + 2, sequences.last())
  }
}

class FeatureTaskRuntimeGoalContinuationPersistenceModelsTest {
  @Test
  fun `goal-runner artifact accessors preserve typed values and reject malformed present values`() {
    val outcome =
      FeatureTaskRuntimeGoalContinuationOutcome(
        issueKey = "SKILL-372",
        subtaskId = 2,
        status = "complete",
        workflowId = "wf-child",
        commitSha = "abc123",
        lastResumableStep = "commit_push",
      )
    val artifacts =
      DurableWorkflowArtifacts.fromMap(
        mapOf(
          FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME_ARTIFACT_KEY to outcome.toPersistenceWire(),
          DecompositionManifestPayloadKeys.COMMIT_PUSH_RESULT to
            mapOf(
              DecompositionManifestPayloadKeys.COMMIT_SHA to "abc123",
              DecompositionManifestPayloadKeys.PRE_COMMIT_PROJECTION to true,
            ),
        ),
      )

    assertEquals(outcome, artifacts.goalContinuationOutcomeArtifact())
    assertEquals(
      FeatureTaskRuntimeCommitPushResultArtifact("abc123", true),
      artifacts.commitPushResultArtifact(),
    )
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      DurableWorkflowArtifacts.fromMap(
        mapOf(DecompositionManifestPayloadKeys.COMMIT_PUSH_RESULT to null),
      ).commitPushResultArtifact()
    }
  }

  @Test
  fun `goal review accessor distinguishes absent state from an incomplete artifact family`() {
    assertNull(DurableWorkflowArtifacts.EMPTY.goalSubtaskReviewArtifacts())
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      DurableWorkflowArtifacts.fromMap(
        mapOf(GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to emptyMap<String, Any?>()),
      ).goalSubtaskReviewArtifacts()
    }
  }

  @Test
  fun `goal-continuation accessor distinguishes absent and malformed durable artifacts`() {
    val valid =
      FeatureTaskRuntimeGoalContinuationArtifact(
        issueKey = "SKILL-372",
        subtaskId = 2,
        suppressPr = true,
        goalBranch = "feat/SKILL-372",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      )

    assertNull(DurableWorkflowArtifacts.EMPTY.goalContinuationArtifact())
    assertEquals(
      valid,
      DurableWorkflowArtifacts.fromMap(
        mapOf(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to valid.toArtifactMap()),
      ).goalContinuationArtifact(),
    )
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      DurableWorkflowArtifacts.fromMap(
        mapOf(
          FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to
            valid.toArtifactMap() + ("subtask_id" to 2.7),
        ),
      ).goalContinuationArtifact()
    }
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      DurableWorkflowArtifacts.fromMap(mapOf(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to null))
        .goalContinuationArtifact()
    }
  }

  @Test
  fun `goal-continuation artifact retains the immutable review mode and optional parallel lane`() {
    val artifact =
      FeatureTaskRuntimeGoalContinuationArtifact(
        issueKey = "SKILL-119",
        subtaskId = 2,
        suppressPr = true,
        goalBranch = "feat/SKILL-119-subtask-2",
        parentWorkflowId = "wfl-parent",
        codeReviewMode = CodeReviewExecutionMode.DELEGATED,
        validationDepth = ValidationDepth.FULL,
        agentAddonSelection =
          AgentAddonSelection(
            listOf(
              PersistedAgentAddonSelectionEntry(
                "helper",
                "/repo/agent-addons/helper/agent-addon.yaml",
                "a".repeat(64),
              ),
            ),
          ),
      )

    assertEquals(artifact, FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(artifact.toArtifactMap()))
  }

  @Test
  fun `goal-continuation artifact round-trips explicit depths and preserves absent validation_depth`() {
    val full =
      FeatureTaskRuntimeGoalContinuationArtifact(
        issueKey = "SKILL-173",
        subtaskId = 1,
        suppressPr = true,
        goalBranch = "feat/SKILL-173",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
        validationDepth = ValidationDepth.FULL,
      )
    assertEquals(full, FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(full.toArtifactMap()))
    assertEquals("full", full.toArtifactMap()["validation_depth"])

    assertEquals(
      full,
      FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(
        full.toArtifactMap() + ("validation_depth" to "build_only"),
      ),
    )

    val absentKey =
      linkedMapOf<String, Any?>(
        "issue_key" to "SKILL-173",
        "subtask_id" to 1,
        "suppress_pr" to true,
        "goal_branch" to "feat/SKILL-173",
        "code_review_mode" to "inline",
      )
    val absent = FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(absentKey)
    assertNull(absent.validationDepth)
    assertNull(absent.toArtifactMap()["validation_depth"])
    assertNull(
      FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(absent.toArtifactMap()).validationDepth,
    )
  }

  @Test
  fun `goal-continuation artifact rejects missing mode unknown fields and blank parallel lane`() {
    val complete =
      FeatureTaskRuntimeGoalContinuationArtifact(
        issueKey = "SKILL-119",
        subtaskId = 2,
        suppressPr = true,
        goalBranch = "feat/SKILL-119-subtask-2",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ).toArtifactMap()

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(complete - "code_review_mode")
    }
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(complete + ("unexpected" to true))
    }
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(complete + ("parallel_review_agent" to ""))
    }
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(complete + ("validation_depth" to "partial"))
    }
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(complete + ("code_review_mode" to "partial"))
    }
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(
        complete + ("quality_gate_selection" to "unknown"),
      )
    }
  }

  @Test
  fun `goal-continuation decode rejects non-positive subtask ids with a typed schema error`() {
    val malformed =
      mapOf(
        "issue_key" to "SKILL-119",
        "subtask_id" to 0,
        "suppress_pr" to true,
        "goal_branch" to "feat/SKILL-119-subtask-2",
        "code_review_mode" to "inline",
      )

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(malformed)
    }
  }

  @Test
  fun `goal-continuation artifact round-trips quality_gate_selection and absent decodes as validate`() {
    val build =
      FeatureTaskRuntimeGoalContinuationArtifact(
        issueKey = "SKILL-204",
        subtaskId = 1,
        suppressPr = true,
        goalBranch = "feat/SKILL-204",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
        qualityGateSelection = BUILD,
      )
    assertEquals("build", build.toArtifactMap()["quality_gate_selection"])
    assertEquals(
      build,
      FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(build.toArtifactMap()),
    )

    val absent =
      FeatureTaskRuntimeGoalContinuationArtifact(
        issueKey = "SKILL-204",
        subtaskId = 2,
        suppressPr = true,
        goalBranch = "feat/SKILL-204",
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      )
    assertNull(absent.toArtifactMap()["quality_gate_selection"])
    assertEquals(
      VALIDATE,
      FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(
        absent.toArtifactMap() + ("quality_gate_selection" to "validate"),
      ).qualityGateSelection,
    )
  }

  @Test
  fun `goal-continuation outcome round trips agent attribution through its artifact map`() {
    val outcome =
      FeatureTaskRuntimeGoalContinuationOutcome(
        issueKey = "SKILL-89",
        subtaskId = 4,
        status = "complete",
        workflowId = "wf-4",
        commitSha = "abc123",
        lastResumableStep = "commit_push",
        finalizingAgentId = "claude",
        participatingAgentIds = listOf("codex", "claude"),
      )
    val map = outcome.toArtifactMap()
    assertEquals("claude", map["finalizing_agent_id"])
    assertEquals(listOf("codex", "claude"), map["participating_agent_ids"])
    assertEquals(outcome, FeatureTaskRuntimeGoalContinuationOutcome.fromArtifactMap(map))
  }

  @Test
  fun `legacy goal-continuation outcome without agent fields decodes to null and empty`() {
    val legacy =
      mapOf(
        "issue_key" to "SKILL-89",
        "subtask_id" to 2,
        "status" to "complete",
        "workflow_id" to "wf-2",
        "last_resumable_step" to "commit_push",
      )
    val decoded = FeatureTaskRuntimeGoalContinuationOutcome.fromArtifactMap(legacy)
    assertNull(decoded.finalizingAgentId)
    assertTrue(decoded.participatingAgentIds.isEmpty())
  }

  @Test
  fun `goal-continuation outcome omits finalizing agent when null but always emits the participants list`() {
    val outcome =
      FeatureTaskRuntimeGoalContinuationOutcome(
        issueKey = "SKILL-89",
        subtaskId = 1,
        status = "complete",
        workflowId = "wf-1",
        lastResumableStep = "commit_push",
      )
    val map = outcome.toArtifactMap()
    assertNull(map["finalizing_agent_id"])
    assertEquals(emptyList<String>(), map["participating_agent_ids"])
  }

  @Test
  fun `goal-continuation outcome decode loud-fails on a non-string participant element`() {
    val malformed =
      mapOf(
        "issue_key" to "SKILL-89",
        "subtask_id" to 3,
        "status" to "complete",
        "workflow_id" to "wf-3",
        "last_resumable_step" to "commit_push",
        "participating_agent_ids" to listOf("codex", 7),
      )
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimeGoalContinuationOutcome.fromArtifactMap(malformed)
    }
  }

  @Test
  fun `goal-continuation outcome decode loud-fails on a non-list participants value`() {
    val malformed =
      mapOf(
        "issue_key" to "SKILL-89",
        "subtask_id" to 3,
        "status" to "complete",
        "workflow_id" to "wf-3",
        "last_resumable_step" to "commit_push",
        "participating_agent_ids" to "codex",
      )
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimeGoalContinuationOutcome.fromArtifactMap(malformed)
    }
  }
}
