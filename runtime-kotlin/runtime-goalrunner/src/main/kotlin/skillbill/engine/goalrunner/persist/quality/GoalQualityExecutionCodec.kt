package skillbill.engine.goalrunner.persist.quality

import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.ExecutionTier
import skillbill.config.model.PhaseModelDirective
import skillbill.contracts.workflow.goal.GoalQualityExecutionPayloadKeys
import skillbill.goalrunner.model.quality.GoalQualityAcceptedEnvelope
import skillbill.goalrunner.model.quality.GoalQualityAcceptedOutput
import skillbill.goalrunner.model.quality.GoalQualityExecution
import skillbill.goalrunner.model.quality.GoalQualityExecutionInputs
import skillbill.goalrunner.model.quality.GoalQualityExecutionPlans
import skillbill.goalrunner.model.quality.GoalQualityExecutionProgress
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.goalrunner.model.quality.GoalQualityExecutionStatus
import skillbill.goalrunner.model.quality.GoalQualityHydratedAddonInput
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.goalrunner.model.quality.GoalQualityReadiness
import skillbill.goalrunner.model.quality.GoalQualityValidationDisposition
import skillbill.install.model.SupportedAgent
import skillbill.workflow.taskruntime.artifact.asQaRepairStateWireMap
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodeFindingVerificationDispositionFromArtifact
import skillbill.workflow.taskruntime.artifact.decodePhaseLedgerEntryFromArtifact
import skillbill.workflow.taskruntime.artifact.decodePhaseRecordFromArtifact
import skillbill.workflow.taskruntime.artifact.decodeValidationGateProgressFromArtifact
import skillbill.workflow.taskruntime.artifact.decodeVerificationBoundaryHeadingProvenanceFromArtifact
import skillbill.workflow.taskruntime.artifact.toQaRepairState
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection

internal fun GoalQualityExecutionRecord.toPersistenceWire(): Map<String, Any?> =
  mapOf(
    GoalQualityExecutionPayloadKeys.CONTRACT_VERSION to contractVersion,
    GoalQualityExecutionPayloadKeys.READINESS to readiness.wireValue,
    GoalQualityExecutionPayloadKeys.EXECUTION to execution?.let { it.toPersistenceWire() },
  )

internal fun GoalQualityExecutionInputs.toPersistenceWire(): Map<String, Any?> {
  val keys = GoalQualityExecutionPayloadKeys
  return mapOf(
    keys.ISSUE_KEY to issueKey,
    keys.INTAKE to intake,
    keys.REPO_ROOT to repoRoot,
    keys.INVOKED_AGENT_ID to invokedAgentId,
    keys.CONFIGURED_AGENT_OVERRIDE_ID to configuredAgentOverrideId,
    keys.TIMEOUT_MILLIS to timeoutMillis,
    keys.QA_SELECTION to qaSelection.wireValue,
    keys.PARENT_SPEC_PATH to parentSpecPath,
    keys.ACCEPTANCE_CRITERIA to acceptanceCriteria,
    keys.ACCEPTANCE_CRITERIA_READ_FAILURE to acceptanceCriteriaReadFailure,
    keys.BRANCH to branch,
    keys.MODEL_DIRECTIVES to modelDirectives.toSortedMap().mapValues { (_, value) -> value.toPersistenceWire() },
    keys.MODEL_MATRIX to modelMatrix?.toPersistenceWire(),
    keys.ADDONS to
      addons.map { addon ->
        mapOf(
          keys.SLUG to addon.persisted.slug,
          keys.SOURCE_IDENTITY to addon.persisted.sourceIdentity,
          keys.CONTENT_SHA256 to addon.persisted.contentSha256,
          keys.DESCRIPTION to addon.description,
          keys.CONTENT to addon.content,
        )
      },
  )
}

private fun PhaseModelDirective.toPersistenceWire(): Map<String, Any?> =
  mapOf(
    GoalQualityExecutionPayloadKeys.MODEL to model,
    GoalQualityExecutionPayloadKeys.EFFORT to effort,
  )

private fun ExecutionMatrix.toPersistenceWire(): Map<String, Any?> {
  val keys = GoalQualityExecutionPayloadKeys
  return mapOf(
    keys.PHASE_TIERS to phaseTiers.toSortedMap().mapValues { it.value.name },
    keys.AGENTS to
      agents.entries.sortedBy { it.key.id }.associate { (agent, tiers) ->
        agent.id to
          tiers.entries.sortedBy { it.key.name }.associate { (tier, directive) ->
            tier.name to directive.toPersistenceWire()
          }
      },
    keys.AGENT_PHASE_OVERRIDES to
      agentPhaseOverrides.entries.sortedBy { it.key.id }.associate { (agent, overrides) ->
        agent.id to overrides.toSortedMap().mapValues { (_, directive) -> directive.toPersistenceWire() }
      },
  )
}

private fun decodeInputs(value: Any?): GoalQualityExecutionInputs? {
  val raw = value as? Map<*, *> ?: return null
  val keys = GoalQualityExecutionPayloadKeys
  val directives =
    (raw[keys.MODEL_DIRECTIVES] as? Map<*, *>)?.entries?.associate { (phase, directive) ->
      (phase as String) to decodeDirective(directive)
    }.orEmpty()
  val matrix = (raw[keys.MODEL_MATRIX] as? Map<*, *>)?.let(::decodeMatrix)
  val addons =
    (raw[keys.ADDONS] as? List<*>)?.map { item ->
      val addon = item as? Map<*, *> ?: error("Stored quality add-on is not an object.")
      GoalQualityHydratedAddonInput(
        PersistedAgentAddonSelectionEntry(
          addon[keys.SLUG] as String,
          addon[keys.SOURCE_IDENTITY] as String,
          addon[keys.CONTENT_SHA256] as String,
        ),
        addon[keys.DESCRIPTION] as String,
        addon[keys.CONTENT] as String,
      )
    }.orEmpty()
  return GoalQualityExecutionInputs(
    issueKey = raw[keys.ISSUE_KEY] as String,
    intake = raw[keys.INTAKE] as String?,
    repoRoot = raw[keys.REPO_ROOT] as String,
    invokedAgentId = raw[keys.INVOKED_AGENT_ID] as String,
    configuredAgentOverrideId = raw[keys.CONFIGURED_AGENT_OVERRIDE_ID] as String?,
    timeoutMillis = (raw[keys.TIMEOUT_MILLIS] as Number?)?.toLong(),
    qaSelection = FeatureTaskRuntimeQaSelection.fromWire(raw[keys.QA_SELECTION] as String),
    parentSpecPath = raw[keys.PARENT_SPEC_PATH] as String,
    acceptanceCriteria = (raw[keys.ACCEPTANCE_CRITERIA] as? List<*>)?.map { it as String },
    acceptanceCriteriaReadFailure = raw[keys.ACCEPTANCE_CRITERIA_READ_FAILURE] as String?,
    branch = raw[keys.BRANCH] as String,
    modelDirectives = directives,
    modelMatrix = matrix,
    addons = addons,
  )
}

private fun decodeDirective(value: Any?): PhaseModelDirective {
  val raw = value as Map<*, *>
  val keys = GoalQualityExecutionPayloadKeys
  return PhaseModelDirective(raw[keys.MODEL] as String, raw[keys.EFFORT] as String?)
}

private fun decodeMatrix(value: Map<*, *>): ExecutionMatrix {
  val keys = GoalQualityExecutionPayloadKeys
  val tiers =
    (value[keys.PHASE_TIERS] as? Map<*, *>)?.entries?.associate { (phase, tier) ->
      (phase as String) to ExecutionTier.valueOf(tier as String)
    }.orEmpty()
  val agents =
    (value[keys.AGENTS] as? Map<*, *>)?.entries?.associate { (agent, rawTiers) ->
      val supported = SupportedAgent.entries.single { it.id == agent }
      val directives =
        (rawTiers as Map<*, *>).entries.associate { (tier, directive) ->
          ExecutionTier.valueOf(tier as String) to decodeDirective(directive)
        }
      supported to directives
    }.orEmpty()
  val overrides =
    (value[keys.AGENT_PHASE_OVERRIDES] as? Map<*, *>)?.entries?.associate { (agent, rawOverrides) ->
      val supported = SupportedAgent.entries.single { it.id == agent }
      val directives =
        (rawOverrides as Map<*, *>).entries.associate { (phase, directive) ->
          (phase as String) to decodeDirective(directive)
        }
      supported to directives
    }.orEmpty()
  return ExecutionMatrix(tiers, agents, overrides)
}

private fun GoalQualityExecution.toPersistenceWire(): Map<String, Any?> =
  mapOf(
    GoalQualityExecutionPayloadKeys.PARENT_WORKFLOW_ID to parentWorkflowId,
    GoalQualityExecutionPayloadKeys.INVOCATION_ID to invocationId,
    GoalQualityExecutionPayloadKeys.BASELINE_CONTENT_FINGERPRINT to baselineContentFingerprint,
    GoalQualityExecutionPayloadKeys.SELECTION_FINGERPRINT to selectionFingerprint,
    GoalQualityExecutionPayloadKeys.GOVERNING_INPUT_FINGERPRINT to governingInputFingerprint,
    GoalQualityExecutionPayloadKeys.GOVERNING_INPUTS to governingInputs?.toPersistenceWire(),
    GoalQualityExecutionPayloadKeys.EXECUTION_PLANS to
      executionPlans?.let {
        mapOf(
          GoalQualityExecutionPayloadKeys.QA_PLAN to it.qa,
          GoalQualityExecutionPayloadKeys.VALIDATION_PLAN to it.validation,
          GoalQualityExecutionPayloadKeys.PLAN_FINGERPRINT to it.fingerprint,
        )
      },
    GoalQualityExecutionPayloadKeys.PROGRESS to progress?.toPersistenceWire(),
    GoalQualityExecutionPayloadKeys.STATUS to status.wireValue,
    GoalQualityExecutionPayloadKeys.VALIDATION to validation.wireValue,
    GoalQualityExecutionPayloadKeys.WARNING to warning,
    GoalQualityExecutionPayloadKeys.SETTLED_CONTENT_FINGERPRINT to settledContentFingerprint,
    GoalQualityExecutionPayloadKeys.POST_QA_CONTENT_FINGERPRINT to postQaContentFingerprint,
    GoalQualityExecutionPayloadKeys.QA_TESTED_TREE_IDENTITY to qaTestedTreeIdentity,
    GoalQualityExecutionPayloadKeys.SKIP_REASON to skipReason,
  )

internal fun GoalQualityExecutionRecord.Companion.fromPersistenceWire(
  payload: Map<
    String,
    Any?,
  >,
): GoalQualityExecutionRecord {
  val executionMap = payload[GoalQualityExecutionPayloadKeys.EXECUTION] as? Map<*, *>
  val execution =
    executionMap?.let {
      GoalQualityExecution(
        parentWorkflowId = it[GoalQualityExecutionPayloadKeys.PARENT_WORKFLOW_ID] as String,
        invocationId = it[GoalQualityExecutionPayloadKeys.INVOCATION_ID] as String,
        baselineContentFingerprint = it[GoalQualityExecutionPayloadKeys.BASELINE_CONTENT_FINGERPRINT] as String,
        selectionFingerprint = it[GoalQualityExecutionPayloadKeys.SELECTION_FINGERPRINT] as String?,
        governingInputFingerprint = it[GoalQualityExecutionPayloadKeys.GOVERNING_INPUT_FINGERPRINT] as String?,
        governingInputs = decodeInputs(it[GoalQualityExecutionPayloadKeys.GOVERNING_INPUTS]),
        executionPlans =
          (it[GoalQualityExecutionPayloadKeys.EXECUTION_PLANS] as? Map<*, *>)?.let { plans ->
            GoalQualityExecutionPlans(
              plans[GoalQualityExecutionPayloadKeys.QA_PLAN] as String,
              plans[GoalQualityExecutionPayloadKeys.VALIDATION_PLAN] as String,
              plans[GoalQualityExecutionPayloadKeys.PLAN_FINGERPRINT] as? String
                ?: GoalQualityExecutionPlans(
                  plans[GoalQualityExecutionPayloadKeys.QA_PLAN] as String,
                  plans[GoalQualityExecutionPayloadKeys.VALIDATION_PLAN] as String,
                ).fingerprint,
            )
          },
        progress = (it[GoalQualityExecutionPayloadKeys.PROGRESS] as? Map<*, *>)?.let(::decodeProgress),
        status = GoalQualityExecutionStatus.fromWire(it[GoalQualityExecutionPayloadKeys.STATUS] as String),
        validation =
          GoalQualityValidationDisposition.fromWire(
            it[GoalQualityExecutionPayloadKeys.VALIDATION] as String,
          ),
        warning = it[GoalQualityExecutionPayloadKeys.WARNING] as String?,
        settledContentFingerprint = it[GoalQualityExecutionPayloadKeys.SETTLED_CONTENT_FINGERPRINT] as String?,
        postQaContentFingerprint = it[GoalQualityExecutionPayloadKeys.POST_QA_CONTENT_FINGERPRINT] as String?,
        qaTestedTreeIdentity = it[GoalQualityExecutionPayloadKeys.QA_TESTED_TREE_IDENTITY] as String?,
        skipReason = it[GoalQualityExecutionPayloadKeys.SKIP_REASON] as String?,
      )
    }
  return GoalQualityExecutionRecord(
    contractVersion = payload[GoalQualityExecutionPayloadKeys.CONTRACT_VERSION] as String,
    readiness = GoalQualityReadiness.fromWire(payload[GoalQualityExecutionPayloadKeys.READINESS] as String),
    execution = execution,
  )
}

private fun GoalQualityExecutionProgress.toPersistenceWire(): Map<String, Any?> =
  mapOf(
    GoalQualityExecutionPayloadKeys.QA_PROGRESS to qa.toPersistenceWire(),
    GoalQualityExecutionPayloadKeys.VALIDATION_PROGRESS to validation.toPersistenceWire(),
  )

private fun GoalQualityPhaseProgress.toPersistenceWire(): Map<String, Any?> =
  mapOf(
    GoalQualityExecutionPayloadKeys.PHASE_RECORDS to
      phaseRecords.mapValues { (_, record) ->
        record.asWorkflowArtifactEntry().toMap()
      },
    GoalQualityExecutionPayloadKeys.PHASE_LEDGER to phaseLedger.map { it.asWorkflowArtifactEntry().toMap() },
    GoalQualityExecutionPayloadKeys.ACCEPTED_OUTPUTS to
      acceptedOutputs.map {
        mapOf(
          GoalQualityExecutionPayloadKeys.PHASE_ID to it.phaseId,
          GoalQualityExecutionPayloadKeys.OCCURRENCE to it.occurrence,
          GoalQualityExecutionPayloadKeys.OUTPUT to it.output,
        )
      },
    GoalQualityExecutionPayloadKeys.ACCEPTED_ENVELOPES to
      acceptedEnvelopes.map {
        mapOf(
          GoalQualityExecutionPayloadKeys.PHASE_ID to it.phaseId,
          GoalQualityExecutionPayloadKeys.ATTEMPT to it.attempt,
          GoalQualityExecutionPayloadKeys.ENVELOPE to it.envelope.toMap(),
        )
      },
    GoalQualityExecutionPayloadKeys.QA_REPAIR_STATE to qaRepairState?.asQaRepairStateWireMap()?.toMap(),
    GoalQualityExecutionPayloadKeys.BUILD_GATE_PROGRESS to buildGateProgress?.asWorkflowArtifactEntry()?.toMap(),
    GoalQualityExecutionPayloadKeys.BUILD_GATE_PROGRESS_KEY to buildGateProgressKey,
    GoalQualityExecutionPayloadKeys.VALIDATION_GATE_PROGRESS to
      validationGateProgress?.asWorkflowArtifactEntry()?.toMap(),
    GoalQualityExecutionPayloadKeys.VALIDATION_GATE_PROGRESS_KEY to validationGateProgressKey,
    GoalQualityExecutionPayloadKeys.VERIFICATION_CHECKPOINT to
      verificationCheckpoint?.map {
        it.asWorkflowArtifactEntry().toMap()
      },
    GoalQualityExecutionPayloadKeys.VERIFICATION_BOUNDARY to
      verificationBoundary?.mapValues { (_, entries) ->
        entries.map { it.asWorkflowArtifactEntry().toMap() }
      },
    GoalQualityExecutionPayloadKeys.TERMINAL_BLOCKED_STEP to terminalBlockedStep,
    GoalQualityExecutionPayloadKeys.TERMINAL_BLOCKED_REASON to terminalBlockedReason,
    GoalQualityExecutionPayloadKeys.TERMINAL_COMPLETED to terminalCompleted,
    GoalQualityExecutionPayloadKeys.REVISION to revision,
  )

private fun decodeProgress(raw: Map<*, *>): GoalQualityExecutionProgress =
  GoalQualityExecutionProgress(
    qa = decodePhaseProgress(raw[GoalQualityExecutionPayloadKeys.QA_PROGRESS]),
    validation = decodePhaseProgress(raw[GoalQualityExecutionPayloadKeys.VALIDATION_PROGRESS]),
  )

private fun decodePhaseProgress(value: Any?): GoalQualityPhaseProgress {
  val raw = value as? Map<*, *> ?: error("Stored goal quality phase progress is not an object.")
  val keys = GoalQualityExecutionPayloadKeys
  val phaseRecords =
    (raw[keys.PHASE_RECORDS] as? Map<*, *>)?.entries?.associate { (phase, record) ->
      val phaseId = phase as String
      phaseId to requireNotNull(decodePhaseRecordFromArtifact(record))
    }.orEmpty()
  val phaseLedger =
    (raw[keys.PHASE_LEDGER] as? List<*>)?.map {
      requireNotNull(decodePhaseLedgerEntryFromArtifact(it))
    }.orEmpty()
  val outputs =
    (raw[keys.ACCEPTED_OUTPUTS] as? List<*>)?.map { item ->
      val entry = item as Map<*, *>
      GoalQualityAcceptedOutput(
        entry[keys.PHASE_ID] as String,
        (entry[keys.OCCURRENCE] as Number).toInt(),
        entry[keys.OUTPUT] as String,
      )
    }.orEmpty()
  val envelopes =
    (raw[keys.ACCEPTED_ENVELOPES] as? List<*>)?.map { item ->
      val entry = item as Map<*, *>
      GoalQualityAcceptedEnvelope(
        entry[keys.PHASE_ID] as String,
        (entry[keys.ATTEMPT] as Number).toInt(),
        FeatureTaskRuntimeWorkflowArtifactMap.from(entry[keys.ENVELOPE]),
      )
    }.orEmpty()
  val qaRepairState =
    raw[keys.QA_REPAIR_STATE]?.let {
      FeatureTaskRuntimeWorkflowArtifactMap.from(it).toQaRepairState()
    }
  val buildProgress = raw[keys.BUILD_GATE_PROGRESS]?.let(::decodeValidationGateProgressFromArtifact)
  val validationProgress = raw[keys.VALIDATION_GATE_PROGRESS]?.let(::decodeValidationGateProgressFromArtifact)
  val verification =
    (raw[keys.VERIFICATION_CHECKPOINT] as? List<*>)?.mapIndexed { index, item ->
      requireNotNull(decodeFindingVerificationDispositionFromArtifact(item, "goal quality verification[$index]"))
    }
  val boundary =
    (raw[keys.VERIFICATION_BOUNDARY] as? Map<*, *>)?.entries?.associate { (phase, items) ->
      val phaseId = phase as String
      phaseId to
        (items as List<*>).mapIndexed { index, item ->
          requireNotNull(decodeVerificationBoundaryHeadingProvenanceFromArtifact(item, "$phaseId[$index]"))
        }
    }
  return GoalQualityPhaseProgress(
    phaseRecords = phaseRecords,
    phaseLedger = phaseLedger,
    acceptedOutputs = outputs,
    acceptedEnvelopes = envelopes,
    qaRepairState = qaRepairState,
    buildGateProgress = buildProgress,
    buildGateProgressKey = raw[keys.BUILD_GATE_PROGRESS_KEY] as String?,
    validationGateProgress = validationProgress,
    validationGateProgressKey = raw[keys.VALIDATION_GATE_PROGRESS_KEY] as String?,
    verificationCheckpoint = verification,
    verificationBoundary = boundary,
    terminalBlockedStep = raw[keys.TERMINAL_BLOCKED_STEP] as String?,
    terminalBlockedReason = raw[keys.TERMINAL_BLOCKED_REASON] as String?,
    terminalCompleted = raw[keys.TERMINAL_COMPLETED] as Boolean,
    revision = (raw[keys.REVISION] as Number).toLong(),
  )
}
