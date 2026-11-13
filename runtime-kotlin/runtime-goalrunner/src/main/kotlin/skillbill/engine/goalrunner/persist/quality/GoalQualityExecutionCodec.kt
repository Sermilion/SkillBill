package skillbill.engine.goalrunner.persist.quality

import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.config.model.ExecutionMatrix
import skillbill.config.model.ExecutionTier
import skillbill.config.model.PhaseModelDirective
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.contracts.system.OpenCodeEnvironmentSelection
import skillbill.contracts.system.OpenCodeLaunchIdentity
import skillbill.contracts.system.OpenCodeLaunchIdentityKeys
import skillbill.contracts.workflow.goal.GOAL_QUALITY_EXECUTION_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GoalQualityExecutionPayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.quality.GoalQualityAcceptedEnvelope
import skillbill.goalrunner.model.quality.GoalQualityAcceptedOutput
import skillbill.goalrunner.model.quality.GoalQualityAcceptedSourceObservation
import skillbill.goalrunner.model.quality.GoalQualityAdoptionEvidence
import skillbill.goalrunner.model.quality.GoalQualityExecution
import skillbill.goalrunner.model.quality.GoalQualityExecutionInputs
import skillbill.goalrunner.model.quality.GoalQualityExecutionPlans
import skillbill.goalrunner.model.quality.GoalQualityExecutionProgress
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.goalrunner.model.quality.GoalQualityExecutionStatus
import skillbill.goalrunner.model.quality.GoalQualityHydratedAddonInput
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.goalrunner.model.quality.GoalQualityPreQaBuildOccurrence
import skillbill.goalrunner.model.quality.GoalQualityPreQaBuildState
import skillbill.goalrunner.model.quality.GoalQualityQaSourceWitness
import skillbill.goalrunner.model.quality.GoalQualityReadiness
import skillbill.goalrunner.model.quality.GoalQualityValidationDisposition
import skillbill.goalrunner.model.quality.GoalQualityValidationOccurrence
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
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRepairOutput
import java.security.MessageDigest

internal fun GoalQualityExecutionRecord.toPersistenceWire(): Map<String, Any?> =
  mapOf(
    GoalQualityExecutionPayloadKeys.CONTRACT_VERSION to contractVersion,
    GoalQualityExecutionPayloadKeys.READINESS to readiness.wireValue,
    GoalQualityExecutionPayloadKeys.EXECUTION to execution?.let { it.toPersistenceWire() },
    GoalQualityExecutionPayloadKeys.ADOPTION_EVIDENCE to
      adoptionEvidence?.let {
        mapOf(
          GoalQualityExecutionPayloadKeys.SOURCE_CONTRACT_VERSION to it.sourceContractVersion,
          GoalQualityExecutionPayloadKeys.ORIGINAL_PAYLOAD_JSON to it.originalPayloadJson,
        )
      },
  )

internal fun GoalQualityExecutionInputs.toPersistenceWire(): Map<String, Any?> {
  val keys = GoalQualityExecutionPayloadKeys
  val wire =
    mapOf(
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
  return openCodeIdentity?.let { wire + (keys.OPEN_CODE_IDENTITY to it.toPersistenceWire()) } ?: wire
}

private fun OpenCodeLaunchIdentity.toPersistenceWire(): Map<String, Any?> {
  val keys = OpenCodeLaunchIdentityKeys
  return mapOf(
    keys.HOME to home.toPersistenceWire(),
    keys.CONFIG_HOME to configHome.toPersistenceWire(),
    keys.DATA_HOME to dataHome.toPersistenceWire(),
    keys.CACHE_HOME to cacheHome.toPersistenceWire(),
    keys.STATE_HOME to stateHome.toPersistenceWire(),
    keys.CONFIG_DIRECTORY to configDirectory.toPersistenceWire(),
    keys.CONFIG_FILE to configFile.toPersistenceWire(),
    keys.DISABLE_PROJECT_CONFIG to disableProjectConfig.toPersistenceWire(),
    keys.EFFECTIVE_HOME to effectiveHome,
    keys.EFFECTIVE_CONFIG_HOME to effectiveConfigHome,
    keys.EFFECTIVE_DATA_HOME to effectiveDataHome,
    keys.EFFECTIVE_CACHE_HOME to effectiveCacheHome,
    keys.EFFECTIVE_STATE_HOME to effectiveStateHome,
    keys.EFFECTIVE_CONFIG_DIRECTORY to effectiveConfigDirectory,
    keys.EFFECTIVE_CONFIG_FILE to effectiveConfigFile,
    keys.INLINE_CONFIG_PRESENT to inlineConfigPresent,
    keys.INLINE_CONFIG_FINGERPRINT to inlineConfigFingerprint,
  )
}

private fun OpenCodeEnvironmentSelection.toPersistenceWire(): Map<String, Any?> =
  mapOf(OpenCodeLaunchIdentityKeys.SUPPLIED to supplied, OpenCodeLaunchIdentityKeys.VALUE to value)

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

internal fun decodeInputs(value: Any?): GoalQualityExecutionInputs? {
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
    openCodeIdentity = raw[keys.OPEN_CODE_IDENTITY]?.let(::decodeOpenCodeLaunchIdentity),
  )
}

private fun decodeOpenCodeLaunchIdentity(value: Any?): OpenCodeLaunchIdentity {
  val raw = value as? Map<*, *> ?: invalidLegacy("Stored OpenCode launch identity is not an object.")
  val keys = OpenCodeLaunchIdentityKeys
  val present =
    raw[keys.INLINE_CONFIG_PRESENT] as? Boolean ?: invalidLegacy(
      "Stored OpenCode inline identity is invalid.",
    )
  val fingerprint = identityString(raw, keys.INLINE_CONFIG_FINGERPRINT, nullable = true)
  if (present != (fingerprint != null) || fingerprint != null && !fingerprint.matches(Regex("[0-9a-f]{64}"))) {
    invalidLegacy("Stored OpenCode inline identity is inconsistent.")
  }
  return OpenCodeLaunchIdentity(
    home = identitySelection(raw, keys.HOME),
    configHome = identitySelection(raw, keys.CONFIG_HOME),
    dataHome = identitySelection(raw, keys.DATA_HOME),
    cacheHome = identitySelection(raw, keys.CACHE_HOME),
    stateHome = identitySelection(raw, keys.STATE_HOME),
    configDirectory = identitySelection(raw, keys.CONFIG_DIRECTORY),
    configFile = identitySelection(raw, keys.CONFIG_FILE),
    disableProjectConfig = identitySelection(raw, keys.DISABLE_PROJECT_CONFIG),
    effectiveHome = identityRequiredString(raw, keys.EFFECTIVE_HOME),
    effectiveConfigHome = identityRequiredString(raw, keys.EFFECTIVE_CONFIG_HOME),
    effectiveDataHome = identityRequiredString(raw, keys.EFFECTIVE_DATA_HOME),
    effectiveCacheHome = identityRequiredString(raw, keys.EFFECTIVE_CACHE_HOME),
    effectiveStateHome = identityRequiredString(raw, keys.EFFECTIVE_STATE_HOME),
    effectiveConfigDirectory = identityRequiredString(raw, keys.EFFECTIVE_CONFIG_DIRECTORY),
    effectiveConfigFile = identityString(raw, keys.EFFECTIVE_CONFIG_FILE, nullable = true),
    inlineConfigPresent = present,
    inlineConfigFingerprint = fingerprint,
  )
}

private fun identityRequiredString(
  raw: Map<*, *>,
  key: String,
): String = identityString(raw, key) ?: invalidLegacy("Stored OpenCode identity field is missing.")

private fun identityString(
  raw: Map<*, *>,
  key: String,
  nullable: Boolean = false,
): String? {
  if (key !in raw) invalidLegacy("Stored OpenCode identity field is missing.")
  val field = raw[key]
  if (nullable && field == null) return null
  return field as? String ?: invalidLegacy("Stored OpenCode identity field is invalid.")
}

private fun identitySelection(
  raw: Map<*, *>,
  key: String,
): OpenCodeEnvironmentSelection {
  val keys = OpenCodeLaunchIdentityKeys
  val selection = raw[key] as? Map<*, *> ?: invalidLegacy("Stored OpenCode launch selection is not an object.")
  val supplied = selection[keys.SUPPLIED] as? Boolean ?: invalidLegacy("Stored OpenCode selection is invalid.")
  if (keys.VALUE !in selection) invalidLegacy("Stored OpenCode selection value is missing.")
  val selected = selection[keys.VALUE]
  if (selected != null && selected !is String || supplied != (selected != null)) {
    invalidLegacy("Stored OpenCode selection presence is inconsistent.")
  }
  return OpenCodeEnvironmentSelection(supplied, selected)
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
          GoalQualityExecutionPayloadKeys.BUILD_PLAN to it.build,
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
    GoalQualityExecutionPayloadKeys.FINAL_VALIDATION_CONTENT_FINGERPRINT to finalValidationContentFingerprint,
    GoalQualityExecutionPayloadKeys.POST_QA_CONTENT_FINGERPRINT to postQaContentFingerprint,
    GoalQualityExecutionPayloadKeys.QA_TESTED_TREE_IDENTITY to qaTestedTreeIdentity,
    GoalQualityExecutionPayloadKeys.SKIP_REASON to skipReason,
    GoalQualityExecutionPayloadKeys.VALIDATION_OCCURRENCES to
      validationOccurrences.map {
        mapOf(
          GoalQualityExecutionPayloadKeys.OCCURRENCE to it.occurrence,
          GoalQualityExecutionPayloadKeys.STATUS to it.status.wireValue,
          GoalQualityExecutionPayloadKeys.VALIDATION to it.validation.wireValue,
          GoalQualityExecutionPayloadKeys.FINAL_VALIDATION_CONTENT_FINGERPRINT to it.finalValidationContentFingerprint,
          GoalQualityExecutionPayloadKeys.PROGRESS to it.progress.toPersistenceWire(),
        )
      },
    GoalQualityExecutionPayloadKeys.VALIDATION_OCCURRENCE to validationOccurrence,
    GoalQualityExecutionPayloadKeys.PRE_QA_BUILD to preQaBuild.wireValue,
    GoalQualityExecutionPayloadKeys.PRE_QA_BUILD_CONTENT_FINGERPRINT to preQaBuildContentFingerprint,
    GoalQualityExecutionPayloadKeys.PRE_QA_BUILD_OCCURRENCES to
      preQaBuildOccurrences.map {
        mapOf(
          GoalQualityExecutionPayloadKeys.PRE_QA_BUILD to it.state.wireValue,
          GoalQualityExecutionPayloadKeys.CONTENT_FINGERPRINT to it.contentFingerprint,
          GoalQualityExecutionPayloadKeys.BUILD_PROGRESS to it.progress.toPersistenceWire(),
        )
      },
  )

internal fun GoalQualityExecutionRecord.Companion.fromPersistenceWire(
  payload: Map<
    String,
    Any?,
  >,
): GoalQualityExecutionRecord {
  if (payload[GoalQualityExecutionPayloadKeys.CONTRACT_VERSION] != GOAL_QUALITY_EXECUTION_CONTRACT_VERSION) {
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
      "Historical goal quality payloads must be read through the version-aware retained projection.",
    )
  }
  val executionMap = payload[GoalQualityExecutionPayloadKeys.EXECUTION] as? Map<*, *>
  val execution = executionMap?.let(::decodePersistedExecution)
  return persistedExecutionRecord(payload, execution)
}

private fun decodePersistedExecution(raw: Map<*, *>): GoalQualityExecution =
  GoalQualityExecution(
    parentWorkflowId = raw[GoalQualityExecutionPayloadKeys.PARENT_WORKFLOW_ID] as String,
    invocationId = raw[GoalQualityExecutionPayloadKeys.INVOCATION_ID] as String,
    baselineContentFingerprint = raw[GoalQualityExecutionPayloadKeys.BASELINE_CONTENT_FINGERPRINT] as String,
    selectionFingerprint = raw[GoalQualityExecutionPayloadKeys.SELECTION_FINGERPRINT] as String?,
    governingInputFingerprint = raw[GoalQualityExecutionPayloadKeys.GOVERNING_INPUT_FINGERPRINT] as String?,
    governingInputs = decodeInputs(raw[GoalQualityExecutionPayloadKeys.GOVERNING_INPUTS]),
    executionPlans =
      (raw[GoalQualityExecutionPayloadKeys.EXECUTION_PLANS] as? Map<*, *>)?.let { plans ->
        GoalQualityExecutionPlans(
          plans[GoalQualityExecutionPayloadKeys.BUILD_PLAN] as String,
          plans[GoalQualityExecutionPayloadKeys.QA_PLAN] as String,
          plans[GoalQualityExecutionPayloadKeys.VALIDATION_PLAN] as String,
          plans[GoalQualityExecutionPayloadKeys.PLAN_FINGERPRINT] as? String
            ?: GoalQualityExecutionPlans(
              plans[GoalQualityExecutionPayloadKeys.BUILD_PLAN] as String,
              plans[GoalQualityExecutionPayloadKeys.QA_PLAN] as String,
              plans[GoalQualityExecutionPayloadKeys.VALIDATION_PLAN] as String,
            ).fingerprint,
        )
      },
    progress = (raw[GoalQualityExecutionPayloadKeys.PROGRESS] as? Map<*, *>)?.let(::decodeProgress),
    status = GoalQualityExecutionStatus.fromWire(raw[GoalQualityExecutionPayloadKeys.STATUS] as String),
    validation =
      GoalQualityValidationDisposition.fromWire(
        raw[GoalQualityExecutionPayloadKeys.VALIDATION] as String,
      ),
    warning = raw[GoalQualityExecutionPayloadKeys.WARNING] as String?,
    settledContentFingerprint = raw[GoalQualityExecutionPayloadKeys.SETTLED_CONTENT_FINGERPRINT] as String?,
    finalValidationContentFingerprint =
      raw[GoalQualityExecutionPayloadKeys.FINAL_VALIDATION_CONTENT_FINGERPRINT] as String?,
    postQaContentFingerprint = raw[GoalQualityExecutionPayloadKeys.POST_QA_CONTENT_FINGERPRINT] as String?,
    qaTestedTreeIdentity = raw[GoalQualityExecutionPayloadKeys.QA_TESTED_TREE_IDENTITY] as String?,
    skipReason = raw[GoalQualityExecutionPayloadKeys.SKIP_REASON] as String?,
    validationOccurrences =
      (raw[GoalQualityExecutionPayloadKeys.VALIDATION_OCCURRENCES] as? List<*>)?.map { item ->
        val occurrence = item as Map<*, *>
        GoalQualityValidationOccurrence(
          (occurrence[GoalQualityExecutionPayloadKeys.OCCURRENCE] as Number).toInt(),
          GoalQualityExecutionStatus.fromWire(occurrence[GoalQualityExecutionPayloadKeys.STATUS] as String),
          GoalQualityValidationDisposition.fromWire(
            occurrence[GoalQualityExecutionPayloadKeys.VALIDATION] as String,
          ),
          occurrence[GoalQualityExecutionPayloadKeys.FINAL_VALIDATION_CONTENT_FINGERPRINT] as String?,
          decodePhaseProgress(occurrence[GoalQualityExecutionPayloadKeys.PROGRESS]),
        )
      }.orEmpty(),
    validationOccurrence =
      (raw[GoalQualityExecutionPayloadKeys.VALIDATION_OCCURRENCE] as? Number)?.toInt() ?: 0,
    preQaBuild = GoalQualityPreQaBuildState.fromWire(raw[GoalQualityExecutionPayloadKeys.PRE_QA_BUILD] as String),
    preQaBuildContentFingerprint = raw[GoalQualityExecutionPayloadKeys.PRE_QA_BUILD_CONTENT_FINGERPRINT] as String?,
    preQaBuildOccurrences =
      (raw[GoalQualityExecutionPayloadKeys.PRE_QA_BUILD_OCCURRENCES] as? List<*>)?.map { item ->
        val occurrence = item as Map<*, *>
        GoalQualityPreQaBuildOccurrence(
          GoalQualityPreQaBuildState.fromWire(occurrence[GoalQualityExecutionPayloadKeys.PRE_QA_BUILD] as String),
          occurrence[GoalQualityExecutionPayloadKeys.CONTENT_FINGERPRINT] as String,
          decodePhaseProgress(occurrence[GoalQualityExecutionPayloadKeys.BUILD_PROGRESS]),
        )
      }.orEmpty(),
  )

private fun persistedExecutionRecord(
  payload: Map<String, Any?>,
  execution: GoalQualityExecution?,
): GoalQualityExecutionRecord =
  GoalQualityExecutionRecord(
    contractVersion = payload[GoalQualityExecutionPayloadKeys.CONTRACT_VERSION] as String,
    readiness = GoalQualityReadiness.fromWire(payload[GoalQualityExecutionPayloadKeys.READINESS] as String),
    execution = execution,
    adoptionEvidence =
      (payload[GoalQualityExecutionPayloadKeys.ADOPTION_EVIDENCE] as? Map<*, *>)?.let { evidence ->
        GoalQualityAdoptionEvidence(
          evidence[GoalQualityExecutionPayloadKeys.SOURCE_CONTRACT_VERSION] as String,
          evidence[GoalQualityExecutionPayloadKeys.ORIGINAL_PAYLOAD_JSON] as String,
        )
      },
  )

private fun GoalQualityExecutionProgress.toPersistenceWire(): Map<String, Any?> =
  mapOf(
    GoalQualityExecutionPayloadKeys.BUILD_PROGRESS to build.toPersistenceWire(),
    GoalQualityExecutionPayloadKeys.QA_PROGRESS to qa.toPersistenceWire(),
    GoalQualityExecutionPayloadKeys.VALIDATION_PROGRESS to validation.toPersistenceWire(),
  )

internal fun GoalQualityPhaseProgress.toPersistenceWire(): Map<String, Any?> =
  phaseProgressCoreWire() + phaseProgressSourceWire()

private fun GoalQualityPhaseProgress.phaseProgressCoreWire(): Map<String, Any?> =
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

private fun GoalQualityPhaseProgress.phaseProgressSourceWire(): Map<String, Any?> =
  mapOf(
    GoalQualityExecutionPayloadKeys.ACCEPTED_SOURCE_OBSERVATIONS to
      acceptedSourceObservations.map {
        mapOf(
          GoalQualityExecutionPayloadKeys.PHASE_ID to it.phaseId,
          GoalQualityExecutionPayloadKeys.ATTEMPT to it.attempt,
          GoalQualityExecutionPayloadKeys.CONTENT_FINGERPRINT to it.contentFingerprint,
          GoalQualityExecutionPayloadKeys.COMPLETION_SEQUENCE_NUMBER to it.completionSequenceNumber,
          GoalQualityExecutionPayloadKeys.PROGRESS_REVISION to it.progressRevision,
          GoalQualityExecutionPayloadKeys.VALIDATION_OCCURRENCE to it.validationOccurrence,
          GoalQualityExecutionPayloadKeys.ACCEPTED_OUTPUT_FINGERPRINT to it.acceptedOutputFingerprint,
        )
      },
    GoalQualityExecutionPayloadKeys.ACCEPTED_QA_SOURCE_WITNESSES to
      acceptedQaSourceWitnesses.map {
        mapOf(
          GoalQualityExecutionPayloadKeys.ADMITTED_QA_PLAN to it.admittedQaPlan,
          GoalQualityExecutionPayloadKeys.PHASE_ID to it.phaseId,
          GoalQualityExecutionPayloadKeys.ATTEMPT to it.attempt,
          GoalQualityExecutionPayloadKeys.ACCEPTED_OUTPUT_FINGERPRINT to it.acceptedOutputFingerprint,
          GoalQualityExecutionPayloadKeys.NESTED_PROGRESS_KEY to it.nestedProgressKey,
          ValidationEvidencePayloadKeys.REPAIR_TURN to it.repairTurn,
          ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT to it.repositoryCheckpoint,
          GoalQualityExecutionPayloadKeys.CONTENT_FINGERPRINT to it.contentFingerprint,
          GoalQualityExecutionPayloadKeys.PROGRESS_REVISION to it.progressRevision,
        )
      },
    GoalQualityExecutionPayloadKeys.ATTEMPT_HIGH_WATER_MARKS to attemptHighWaterMarks,
    GoalQualityExecutionPayloadKeys.LEDGER_SEQUENCE_HIGH_WATER_MARK to ledgerSequenceHighWaterMark,
    GoalQualityExecutionPayloadKeys.CARRIED_VALIDATION_REPAIRS_USED to carriedValidationRepairsUsed,
    GoalQualityExecutionPayloadKeys.CARRIED_VALIDATION_REPAIR_OUTPUTS to
      carriedValidationRepairOutputs.map {
        mapOf(
          ValidationEvidencePayloadKeys.REPAIR_TURN to it.repairTurn,
          ValidationEvidencePayloadKeys.REPAIR_OUTPUT to it.output,
        )
      },
    GoalQualityExecutionPayloadKeys.CARRIED_BUILD_REPAIRS_USED to carriedBuildRepairsUsed,
    GoalQualityExecutionPayloadKeys.CARRIED_BUILD_REPAIR_OUTPUTS to
      carriedBuildRepairOutputs.map {
        mapOf(
          ValidationEvidencePayloadKeys.REPAIR_TURN to it.repairTurn,
          ValidationEvidencePayloadKeys.REPAIR_OUTPUT to it.output,
        )
      },
  )

private fun decodeProgress(raw: Map<*, *>): GoalQualityExecutionProgress =
  GoalQualityExecutionProgress(
    build = decodePhaseProgress(raw[GoalQualityExecutionPayloadKeys.BUILD_PROGRESS]),
    qa = decodePhaseProgress(raw[GoalQualityExecutionPayloadKeys.QA_PROGRESS]),
    validation = decodePhaseProgress(raw[GoalQualityExecutionPayloadKeys.VALIDATION_PROGRESS]),
  )

internal fun decodeHistoricalProgress(raw: Map<*, *>): GoalQualityExecutionProgress =
  GoalQualityExecutionProgress(
    qa = decodePhaseProgress(raw[GoalQualityExecutionPayloadKeys.QA_PROGRESS]),
    validation = decodePhaseProgress(raw[GoalQualityExecutionPayloadKeys.VALIDATION_PROGRESS]),
  )

internal fun decodePhaseProgress(value: Any?): GoalQualityPhaseProgress {
  val raw = value as? Map<*, *> ?: error("Stored goal quality phase progress is not an object.")
  val keys = GoalQualityExecutionPayloadKeys
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
    phaseRecords = decodeQualityPhaseRecords(raw),
    phaseLedger = phaseLedger,
    acceptedOutputs = outputs,
    acceptedEnvelopes = decodeAcceptedEnvelopes(raw),
    qaRepairState = qaRepairState,
    buildGateProgress = buildProgress,
    buildGateProgressKey = raw[keys.BUILD_GATE_PROGRESS_KEY] as String?,
    validationGateProgress = validationProgress,
    validationGateProgressKey = raw[keys.VALIDATION_GATE_PROGRESS_KEY] as String?,
    verificationCheckpoint = verification,
    verificationBoundary = boundary,
    terminalBlockedStep = raw[keys.TERMINAL_BLOCKED_STEP] as String?,
    terminalBlockedReason = raw[keys.TERMINAL_BLOCKED_REASON] as String?,
    terminalCompleted = raw[keys.TERMINAL_COMPLETED] as? Boolean ?: false,
    revision = (raw[keys.REVISION] as? Number)?.toLong() ?: 0,
    acceptedSourceObservations = decodeSourceObservations(raw),
    acceptedQaSourceWitnesses = decodeQaSourceWitnesses(raw),
    attemptHighWaterMarks = decodeAttemptHighWaterMarks(raw),
    ledgerSequenceHighWaterMark = (raw[keys.LEDGER_SEQUENCE_HIGH_WATER_MARK] as? Number)?.toInt() ?: 0,
    carriedValidationRepairsUsed = decodeRepairCount(raw, keys.CARRIED_VALIDATION_REPAIRS_USED),
    carriedValidationRepairOutputs = decodeRepairOutputs(raw, keys.CARRIED_VALIDATION_REPAIR_OUTPUTS),
    carriedBuildRepairsUsed = decodeRepairCount(raw, keys.CARRIED_BUILD_REPAIRS_USED),
    carriedBuildRepairOutputs = decodeRepairOutputs(raw, keys.CARRIED_BUILD_REPAIR_OUTPUTS),
  )
}

private fun decodeAttemptHighWaterMarks(raw: Map<*, *>): Map<String, Int> {
  val marks = raw[GoalQualityExecutionPayloadKeys.ATTEMPT_HIGH_WATER_MARKS] as? Map<*, *> ?: return emptyMap()
  return marks.entries.associate { (phase, attempt) -> (phase as String) to (attempt as Number).toInt() }
}

private fun decodeRepairCount(
  raw: Map<*, *>,
  key: String,
): Int = (raw[key] as? Number)?.toInt() ?: 0

private fun decodeRepairOutputs(
  raw: Map<*, *>,
  key: String,
): List<FeatureTaskRuntimeValidationGateRepairOutput> =
  (raw[key] as? List<*>)?.map { item ->
    val entry = item as Map<*, *>
    FeatureTaskRuntimeValidationGateRepairOutput(
      (entry[ValidationEvidencePayloadKeys.REPAIR_TURN] as Number).toInt(),
      entry[ValidationEvidencePayloadKeys.REPAIR_OUTPUT] as String,
    )
  }.orEmpty()

private fun decodeSourceObservations(raw: Map<*, *>): List<GoalQualityAcceptedSourceObservation> {
  val keys = GoalQualityExecutionPayloadKeys
  return (raw[keys.ACCEPTED_SOURCE_OBSERVATIONS] as? List<*>)?.map { item ->
    val entry = item as Map<*, *>
    GoalQualityAcceptedSourceObservation(
      entry[keys.PHASE_ID] as String,
      (entry[keys.ATTEMPT] as Number).toInt(),
      entry[keys.CONTENT_FINGERPRINT] as String,
      (entry[keys.COMPLETION_SEQUENCE_NUMBER] as? Number)?.toInt() ?: 0,
      (entry[keys.PROGRESS_REVISION] as? Number)?.toLong() ?: 0,
      (entry[keys.VALIDATION_OCCURRENCE] as? Number)?.toInt() ?: 0,
      entry[keys.ACCEPTED_OUTPUT_FINGERPRINT] as String?,
    )
  }.orEmpty()
}

private fun decodeAcceptedEnvelopes(raw: Map<*, *>): List<GoalQualityAcceptedEnvelope> {
  val keys = GoalQualityExecutionPayloadKeys
  return (raw[keys.ACCEPTED_ENVELOPES] as? List<*>)?.map { item ->
    val entry = item as Map<*, *>
    GoalQualityAcceptedEnvelope(
      entry[keys.PHASE_ID] as String,
      (entry[keys.ATTEMPT] as Number).toInt(),
      FeatureTaskRuntimeWorkflowArtifactMap.from(entry[keys.ENVELOPE]),
    )
  }.orEmpty()
}

private fun decodeQualityPhaseRecords(raw: Map<*, *>): Map<String, FeatureTaskRuntimePhaseRecord> {
  val keys = GoalQualityExecutionPayloadKeys
  return (raw[keys.PHASE_RECORDS] as? Map<*, *>)?.entries?.associate { (phase, record) ->
    val phaseId = phase as String
    phaseId to requireNotNull(decodePhaseRecordFromArtifact(record))
  }.orEmpty()
}

private fun decodeQaSourceWitnesses(raw: Map<*, *>): List<GoalQualityQaSourceWitness> {
  val keys = GoalQualityExecutionPayloadKeys
  return (raw[keys.ACCEPTED_QA_SOURCE_WITNESSES] as? List<*>)?.map { item ->
    val entry = item as Map<*, *>
    GoalQualityQaSourceWitness(
      admittedQaPlan = entry[keys.ADMITTED_QA_PLAN] as String,
      phaseId = entry[keys.PHASE_ID] as String,
      attempt = (entry[keys.ATTEMPT] as Number).toInt(),
      completionSequenceNumber = 0,
      acceptedOutputFingerprint = entry[keys.ACCEPTED_OUTPUT_FINGERPRINT] as String,
      nestedProgressKey = entry[keys.NESTED_PROGRESS_KEY] as String,
      repairTurn = (entry[ValidationEvidencePayloadKeys.REPAIR_TURN] as Number).toInt(),
      repositoryCheckpoint = entry[ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT] as String,
      contentFingerprint = entry[keys.CONTENT_FINGERPRINT] as String,
      progressRevision = (entry[keys.PROGRESS_REVISION] as Number).toLong(),
    )
  }.orEmpty()
}

internal fun decodeLegacyQualityRead(
  map: Map<String, Any?>,
  sourceVersion: String,
): GoalQualityExecutionRead {
  val original = map.toMap()
  val executionMap = map[GoalQualityExecutionPayloadKeys.EXECUTION] as? Map<*, *>
  return if (executionMap == null) {
    unstartedLegacyRead(map, sourceVersion, original)
  } else {
    executedLegacyRead(executionMap, sourceVersion, original)
  }
}

private fun unstartedLegacyRead(
  map: Map<String, Any?>,
  sourceVersion: String,
  original: Map<String, Any?>,
): GoalQualityExecutionRead {
  val readiness = GoalQualityReadiness.fromWire(map[GoalQualityExecutionPayloadKeys.READINESS] as String)
  if (readiness != GoalQualityReadiness.CLEAN_UNSTARTED) {
    return GoalQualityExecutionRead.Ambiguous(sourceVersion, original, null, false)
  }
  return GoalQualityExecutionRead.Historical(
    sourceVersion,
    original,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    null,
    false,
    0,
    null,
  )
}

private data class LegacyIdentity(
  val qaPlan: String?,
  val validationPlan: String?,
  val hasProgress: Boolean,
  val inputs: GoalQualityExecutionInputs?,
  val governingFingerprint: String?,
  val invocationId: String?,
  val oldFingerprint: String?,
  val progress: GoalQualityExecutionProgress?,
  val status: GoalQualityExecutionStatus?,
)

private fun executedLegacyRead(
  executionMap: Map<*, *>,
  sourceVersion: String,
  original: Map<String, Any?>,
): GoalQualityExecutionRead {
  val keys = GoalQualityExecutionPayloadKeys
  val plans = executionMap[keys.EXECUTION_PLANS] as? Map<*, *>
  val qaPlan = plans?.get(keys.QA_PLAN) as? String
  val validationPlan = plans?.get(keys.VALIDATION_PLAN) as? String
  val oldFingerprint = plans?.get(keys.PLAN_FINGERPRINT) as? String
  if (conflictingHistoricalPlans(
      oldFingerprint,
      qaPlan,
      validationPlan,
      legacyPlanFingerprint(qaPlan, validationPlan),
    )
  ) {
    invalidLegacy("Stored historical quality plan fingerprint conflicts with its retained QA and validation plans.")
  }
  val progressMap = executionMap[keys.PROGRESS] as? Map<*, *>
  val hasQa = progressMap?.get(keys.QA_PROGRESS) is Map<*, *>
  val hasProgress = hasQa && progressMap.get(keys.VALIDATION_PROGRESS) is Map<*, *>
  val identity =
    LegacyIdentity(
      qaPlan,
      validationPlan,
      hasProgress,
      decodeInputs(executionMap[keys.GOVERNING_INPUTS]),
      executionMap[keys.GOVERNING_INPUT_FINGERPRINT] as? String,
      executionMap[keys.INVOCATION_ID] as? String,
      oldFingerprint,
      if (hasProgress) decodeHistoricalProgress(requireNotNull(progressMap)) else null,
      (executionMap[keys.STATUS] as? String)?.let(GoalQualityExecutionStatus::fromWire),
    )
  val authoritative = legacyAuthoritative(identity, executionMap)
  if (authoritative || identity.status == GoalQualityExecutionStatus.RETIRED) {
    return historicalLegacyRead(executionMap, sourceVersion, original, identity, authoritative)
  }
  return GoalQualityExecutionRead.Ambiguous(sourceVersion, original, identity.invocationId, false)
}

private fun legacyAuthoritative(
  identity: LegacyIdentity,
  executionMap: Map<*, *>,
): Boolean {
  val plansReady = !identity.qaPlan.isNullOrBlank() && !identity.validationPlan.isNullOrBlank() && identity.hasProgress
  val inputs = identity.inputs
  val inputsMatch = inputs != null && identity.governingFingerprint == governingInputFingerprint(inputs)
  val parent = executionMap[GoalQualityExecutionPayloadKeys.PARENT_WORKFLOW_ID] as? String
  val parentReady = !parent.isNullOrBlank() && !identity.invocationId.isNullOrBlank()
  val baseline = executionMap[GoalQualityExecutionPayloadKeys.BASELINE_CONTENT_FINGERPRINT] as? String
  val selection = executionMap[GoalQualityExecutionPayloadKeys.SELECTION_FINGERPRINT] as? String
  val fingerprints = !baseline.isNullOrBlank() && !selection.isNullOrBlank()
  return plansReady && inputsMatch && parentReady && fingerprints
}

private fun historicalLegacyRead(
  executionMap: Map<*, *>,
  sourceVersion: String,
  original: Map<String, Any?>,
  identity: LegacyIdentity,
  authoritative: Boolean,
): GoalQualityExecutionRead {
  val keys = GoalQualityExecutionPayloadKeys
  return GoalQualityExecutionRead.Historical(
    sourceVersion,
    original,
    executionMap[keys.PARENT_WORKFLOW_ID] as? String,
    identity.invocationId,
    executionMap[keys.BASELINE_CONTENT_FINGERPRINT] as? String,
    executionMap[keys.SELECTION_FINGERPRINT] as? String,
    identity.governingFingerprint,
    identity.inputs,
    identity.qaPlan,
    identity.validationPlan,
    identity.oldFingerprint,
    identity.progress,
    identity.status,
    (executionMap[keys.VALIDATION] as? String)?.let(GoalQualityValidationDisposition::fromWire),
    executionMap[keys.WARNING] as? String,
    executionMap[keys.FINAL_VALIDATION_CONTENT_FINGERPRINT] as? String,
    authoritative,
    (executionMap[keys.VALIDATION_OCCURRENCE] as? Number)?.toInt() ?: 0,
    executionMap[keys.SETTLED_CONTENT_FINGERPRINT] as? String,
  )
}

private fun legacyPlanFingerprint(
  qaPlan: String?,
  validationPlan: String?,
): String =
  MessageDigest.getInstance("SHA-256")
    .digest("${qaPlan.orEmpty()}\u0000${validationPlan.orEmpty()}".toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private fun invalidLegacy(reason: String): Nothing =
  throw SkillBillRuntimeException(WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION, reason)
