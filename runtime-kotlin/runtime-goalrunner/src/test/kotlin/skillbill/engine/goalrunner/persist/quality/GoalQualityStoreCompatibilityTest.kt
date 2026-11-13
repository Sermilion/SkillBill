package skillbill.engine.goalrunner.persist.quality

import org.junit.jupiter.api.io.TempDir
import skillbill.config.model.ExecutionTier
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.goal.GoalQualityExecutionPayloadKeys
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerOutcomeStore
import skillbill.engine.goalrunner.persist.taskRuntimeWorkflowRecord
import skillbill.error.core.SkillBillRuntimeException
import skillbill.goalrunner.model.quality.GoalQualityAcceptedOutput
import skillbill.goalrunner.model.quality.GoalQualityExecutionAdmission
import skillbill.goalrunner.model.quality.GoalQualityExecutionInputs
import skillbill.goalrunner.model.quality.GoalQualityExecutionPlans
import skillbill.goalrunner.model.quality.GoalQualityExecutionProgress
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.goalrunner.model.quality.GoalQualityReadiness
import skillbill.infrastructure.contracts.workflow.decomposition.DecompositionManifestSchemaValidator
import skillbill.install.model.SupportedAgent
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoalQualityStoreCompatibilityTest {
  @TempDir lateinit var home: Path
  private val now = Instant.parse("2026-11-13T10:00:00Z")
  private val clock = Clock.fixed(now, ZoneOffset.UTC)
  private val keys = GoalQualityExecutionPayloadKeys
  private val plans = GoalQualityExecutionPlans("build-plan", "qa-plan", "validation-plan")

  @Test
  fun `current and historical quality stores retain previous provider proof across adoption save and reopen`() {
    listOf("0.7", "0.6").forEach { version ->
      val parent = "quality-parent-$version"
      val inputs = inputsWithPreviousProvider()
      val progress = completedQa()
      val record = initialRecord(parent, inputs, progress)
      val original = payload(record, version)
      val database = phaseRunDatabase(home.resolve(version), clock)
      seed(database, parent, original)
      val store = testWorkflowGoalRunnerOutcomeStore(database, clock = clock)
      val retained = assertNotNull(store.retainedGoalQualityExecution(parent))
      assertEquals(jsonPayload(original), jsonPayload(storedPayload(database, parent)))
      val loaded =
        if (version == "0.6") {
          val historical = assertIs<GoalQualityExecutionRead.Historical>(retained)
          assertEquals(jsonPayload(original), jsonPayload(historical.originalPayload))
          store.admitGoalQualityExecution(
            parent,
            GoalQualityAdmissionRequest("baseline", inputs, plans, expectedRetainedRead = retained),
          )
        } else {
          assertIs<GoalQualityExecutionRead.Current>(retained).record
        }
      val execution = assertNotNull(loaded.execution)
      assertEquals(record.execution?.invocationId, execution.invocationId)
      assertEquals(governingInputFingerprint(inputs), execution.governingInputFingerprint)
      assertEquals(inputs.toPersistenceWire(), execution.governingInputs?.toPersistenceWire())
      assertEquals(setOf(SupportedAgent.CLAUDE), execution.governingInputs?.modelMatrix?.agents?.keys)
      assertEquals(plans, execution.executionPlans)
      assertEquals(progress, execution.progress?.qa)
      assertTrue(store.transitionGoalQualityExecution(parent, execution.invocationId, loaded, loaded.startPreQaBuild()))

      val reopened = testWorkflowGoalRunnerOutcomeStore(phaseRunDatabase(home.resolve(version), clock), clock = clock)
      val saved = assertNotNull(reopened.goalQualityExecution(parent))
      assertEquals(loaded.startPreQaBuild(), saved)
      if (version == "0.6") {
        val archive = assertNotNull(saved.adoptionEvidence)
        assertEquals("0.6", archive.sourceContractVersion)
        assertEquals(jsonPayload(original), JsonCodec.parseObjectOrNull(archive.originalPayloadJson))
      }
    }
  }

  @Test
  fun `current quality store rejects stale governing input fingerprints without rewriting proof`() {
    val parent = "quality-stale-parent"
    val record = initialRecord(parent, inputsWithPreviousProvider(), completedQa())
    val original = record.toPersistenceWire().toMutableMap()
    val execution = requireNotNull(JsonCodec.anyToStringAnyMap(original[keys.EXECUTION])).toMutableMap()
    execution[keys.GOVERNING_INPUT_FINGERPRINT] = "stale-fingerprint"
    original[keys.EXECUTION] = execution
    val database = phaseRunDatabase(home, clock)
    seed(database, parent, original)

    assertFailsWith<SkillBillRuntimeException> {
      testWorkflowGoalRunnerOutcomeStore(database, clock = clock).goalQualityExecution(parent)
    }

    assertEquals(jsonPayload(original), jsonPayload(storedPayload(database, parent)))
  }

  private fun inputsWithPreviousProvider(): GoalQualityExecutionInputs {
    val base =
      GoalQualityExecutionInputs(
        issueKey = "SONA-19", intake = null, repoRoot = home.toString(), invokedAgentId = "claude",
        configuredAgentOverrideId = null, timeoutMillis = null, qaSelection = FeatureTaskRuntimeQaSelection.AUTO,
        parentSpecPath = "spec.md", acceptanceCriteria = emptyList(), acceptanceCriteriaReadFailure = null,
        branch = "feat/SONA-19", modelDirectives = emptyMap(), modelMatrix = null, addons = emptyList(),
      )
    val directive = mapOf(keys.MODEL to "retained-model", keys.EFFORT to "high")
    val previousProvider = charArrayOf('j', 'u', 'n', 'i', 'e').concatToString()
    val matrix =
      mapOf(
        keys.PHASE_TIERS to mapOf("implement" to ExecutionTier.IMPLEMENTATION.name),
        keys.AGENTS to
          mapOf(
            "claude" to mapOf(ExecutionTier.IMPLEMENTATION.name to directive),
            previousProvider to mapOf(ExecutionTier.REASONING.name to directive),
          ),
        keys.AGENT_PHASE_OVERRIDES to mapOf(previousProvider to mapOf("plan" to directive)),
      )
    return requireNotNull(decodeInputs(base.toPersistenceWire() + (keys.MODEL_MATRIX to matrix)))
  }

  private fun initialRecord(
    parent: String,
    inputs: GoalQualityExecutionInputs,
    progress: GoalQualityPhaseProgress,
  ): GoalQualityExecutionRecord {
    val admitted =
      GoalQualityExecutionRecord(readiness = GoalQualityReadiness.CLEAN_UNSTARTED).admit(
        GoalQualityExecutionAdmission(
          parent,
          "preserved-invocation",
          "baseline",
          fingerprint("$parent\u00001\u0000skipped"),
          governingInputFingerprint(inputs),
          inputs,
          plans,
        ),
      )
    return admitted.copy(
      execution = requireNotNull(admitted.execution).copy(progress = GoalQualityExecutionProgress(qa = progress)),
    )
  }

  private fun completedQa(): GoalQualityPhaseProgress {
    val start = FeatureTaskRuntimePhaseLedgerEntry(FeatureTaskRuntimePhaseLedgerAction.START, 1, now, "qa", 1, "claude")
    return GoalQualityPhaseProgress(
      phaseRecords =
        mapOf(
          "qa" to
            FeatureTaskRuntimePhaseRecord(
              "qa",
              WorkflowStepStatus.COMPLETED,
              1,
              now,
              resolvedAgentId = "claude",
              finishedAt = now,
            ),
        ),
      phaseLedger =
        listOf(
          start,
          start.copy(action = FeatureTaskRuntimePhaseLedgerAction.COMPLETE, sequenceNumber = 2),
        ),
      acceptedOutputs = listOf(GoalQualityAcceptedOutput("qa", 1, "preserved completed work")),
      terminalCompleted = true,
      revision = 2,
    )
  }

  private fun payload(
    record: GoalQualityExecutionRecord,
    version: String,
  ): Map<String, Any?> {
    val wire = record.toPersistenceWire().toMutableMap()
    if (version == "0.6") {
      wire.remove(keys.ADOPTION_EVIDENCE)
      val execution = requireNotNull(JsonCodec.anyToStringAnyMap(wire[keys.EXECUTION])).toMutableMap()
      val oldPlans = requireNotNull(JsonCodec.anyToStringAnyMap(execution[keys.EXECUTION_PLANS])).toMutableMap()
      oldPlans.remove(keys.BUILD_PLAN)
      oldPlans[keys.PLAN_FINGERPRINT] = fingerprint("${plans.qa}\u0000${plans.validation}")
      execution[keys.EXECUTION_PLANS] = oldPlans
      wire[keys.EXECUTION] = execution
      wire[keys.CONTRACT_VERSION] = version
    }
    return wire
  }

  private fun seed(
    database: DatabaseSessionFactory,
    parent: String,
    payload: Map<String, Any?>,
  ) {
    val manifest =
      DecompositionManifest(
        issueKey = "SONA-19",
        featureName = "agent removal",
        parentSpecPath = "spec.md",
        baseBranch = "main",
        featureBranch = "feat/SONA-19",
        currentSubtaskIntent = CurrentSubtaskIntent(1, "start"),
        subtasks = listOf(DecompositionSubtask(1, "preserved subtask", "subtask.md", status = "skipped")),
      )
    val artifacts =
      mapOf(
        DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION.entry(payload),
        DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.entry(
          DecompositionManifestSchemaValidator().encodeManifestWireMap(manifest),
        ),
      )
    database.transaction {
      it.workflowStates.saveFeatureTaskWorkflow(
        taskRuntimeWorkflowRecord(parent).copy(artifactsJson = JsonCodec.mapToJsonString(artifacts)),
        FeatureTaskWorkflowMode.RUNTIME,
      )
    }
  }

  private fun storedPayload(
    database: DatabaseSessionFactory,
    parent: String,
  ): Map<String, Any?> =
    database.read {
      val snapshot = requireNotNull(it.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parent))
      requireNotNull(
        JsonCodec.anyToStringAnyMap(DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION.value(snapshot.artifacts)),
      )
    }

  private fun jsonPayload(payload: Map<String, Any?>) = JsonCodec.parseObjectOrNull(JsonCodec.mapToJsonString(payload))

  private fun fingerprint(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
