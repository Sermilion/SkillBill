package skillbill.engine.featuretask.slotbaseline

import skillbill.contracts.JsonCodec
import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.engine.RuntimeRecordingLauncher
import skillbill.engine.WORKFLOW_ID
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.phaseIdFromPrompt
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry

internal data class SlotBaselineDurableBundle(
  val workflowSnapshot: Map<String, Any?>,
  val phaseRecords: Map<String, Any?>,
  val ledgerEntries: List<Any?>,
  val handoffProjections: Any?,
  val runInvariants: Any?,
  val featureTaskRuntimeFinished: Any?,
  val prompts: Map<String, String>,
) {
  fun encodedFiles(resourcePrefix: String): Map<String, String> =
    buildMap {
      put("$resourcePrefix/${SlotBaselinePaths.WORKFLOW_SNAPSHOT}", SlotBaselineJson.encode(workflowSnapshot))
      put("$resourcePrefix/${SlotBaselinePaths.PHASE_RECORDS}", SlotBaselineJson.encode(phaseRecords))
      put("$resourcePrefix/${SlotBaselinePaths.LEDGER_ENTRIES}", SlotBaselineJson.encode(ledgerEntries))
      put("$resourcePrefix/${SlotBaselinePaths.HANDOFF_PROJECTIONS}", SlotBaselineJson.encode(handoffProjections))
      put("$resourcePrefix/${SlotBaselinePaths.RUN_INVARIANTS}", SlotBaselineJson.encode(runInvariants))
      put(
        "$resourcePrefix/${SlotBaselinePaths.FEATURE_TASK_RUNTIME_FINISHED}",
        SlotBaselineJson.encode(featureTaskRuntimeFinished),
      )
      prompts.forEach { (stepId, prompt) ->
        put("$resourcePrefix/${SlotBaselinePaths.PROMPTS_DIR}/$stepId.txt", SlotBaselineJson.encode(prompt))
      }
    }

  companion object {
    private const val REVIEW_STEP = "review"
    private const val PROMPT_ATTEMPT_SEPARATOR = "\n---\n"

    fun fromRun(
      recorder: FeatureTaskRuntimePhaseRecorder,
      database: DatabaseSessionFactory,
      phaseLauncher: RuntimeRecordingLauncher,
      reviewLauncher: RuntimeRecordingLauncher,
    ): SlotBaselineDurableBundle {
      val record =
        requireNotNull(database.read { it.workflowStates.getFeatureTaskWorkflow(WORKFLOW_ID) }) {
          "workflow $WORKFLOW_ID was not persisted"
        }
      val artifacts =
        JsonCodec.parseObjectOrNull(record.artifactsJson)
          ?.let(JsonCodec::jsonElementToValue)
          ?.let(JsonCodec::anyToStringAnyMap)
          .orEmpty()
      return SlotBaselineDurableBundle(
        workflowSnapshot =
          mapOf(
            "workflow_id" to record.workflowId,
            "session_id" to record.sessionId,
            "workflow_name" to record.workflowName,
            "contract_version" to record.contractVersion,
            "workflow_status" to record.workflowStatus,
            "current_step_id" to record.currentStepId,
            "steps_json" to SlotBaselineJson.parseEmbedded(record.stepsJson),
            "artifacts_json" to artifacts,
            "mode" to record.mode,
          ),
        phaseRecords =
          recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty().mapValues { (_, phaseRecord) ->
            phaseRecord.asWorkflowArtifactEntry()
          },
        ledgerEntries = recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty().map { it.asWorkflowArtifactEntry() },
        handoffProjections =
          artifacts[DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS.label()],
        runInvariants = artifacts[DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RUN_INVARIANTS.label()],
        featureTaskRuntimeFinished =
          SlotBaselineSqlite.latestOutboxPayload(
            database.resolveDbPath(),
            TelemetryOutboxEvent.FEATURE_TASK_RUNTIME_FINISHED.wireValue,
          ),
        prompts = launchedPrompts(phaseLauncher, reviewLauncher),
      )
    }

    private fun launchedPrompts(
      phaseLauncher: RuntimeRecordingLauncher,
      reviewLauncher: RuntimeRecordingLauncher,
    ): Map<String, String> {
      val phasePrompts =
        phaseLauncher.requests.mapNotNull { request ->
          request.skillRunRequest.promptOverride?.let { prompt -> phaseIdFromPrompt(prompt) to prompt }
        }
      val reviewPrompts =
        reviewLauncher.requests.mapNotNull { request ->
          request.skillRunRequest.promptOverride?.let { prompt -> REVIEW_STEP to prompt }
        }
      return (phasePrompts + reviewPrompts)
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, attempts) -> attempts.joinToString(PROMPT_ATTEMPT_SEPARATOR) }
    }
  }
}
