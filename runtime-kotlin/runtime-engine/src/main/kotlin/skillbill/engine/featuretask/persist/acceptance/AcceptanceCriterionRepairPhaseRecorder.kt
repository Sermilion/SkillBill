package skillbill.engine.featuretask.persist.acceptance

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.lifecycle.checkpoint.isRuntimePrivatePath
import skillbill.engine.featuretask.lifecycle.core.AcquiredFeatureTaskRuntimeWorker
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairAuditEvidence
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairCommit
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairMutation
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRefusal
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairStepRoles
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairWriteResult
import skillbill.engine.featuretask.model.audit.ExpectedAcceptanceCriterionRepairState
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.sha256Hex
import skillbill.engine.featuretask.phase.core.auditProseValue
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunEvidenceOwnership
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteKind
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionGapReportParser
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionGapStatus
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProseAudit
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.audit.AcceptanceGapVerificationResolver
import skillbill.workflow.taskruntime.model.audit.AcceptanceRepairDecision
import skillbill.workflow.taskruntime.model.audit.AcceptanceRepairStep
import skillbill.workflow.taskruntime.model.audit.REPAIR_ADMISSION_CLARIFICATION_PREFIX
import skillbill.workflow.taskruntime.model.audit.acceptanceCriterionIdentity
import skillbill.workflow.taskruntime.model.audit.auditNeedsClarification
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionEntry
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionTarget
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionTraceParse
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionTraceParser
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionTraceSettlement
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionVerdict
import skillbill.workflow.taskruntime.model.audit.interaction.recordInteractionTrace
import skillbill.workflow.taskruntime.model.audit.originalAcceptanceCriterionAlias
import skillbill.workflow.taskruntime.model.audit.qaReauditRefs
import skillbill.workflow.taskruntime.model.audit.resolveAcceptanceCriterionDiscovery
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttemptStatus
import java.nio.file.Path
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

@Inject
public class AcceptanceCriterionRepairPhaseRecorder(
  private val persistence: AcceptanceCriterionRepairPersistence,
  private val gitOperations: WorkflowGitOperations,
  private val workflows: FeatureTaskRuntimeWorkflowPersistence,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun session(
    scope: AcceptanceCriterionRepairRunState?,
    worker: AcquiredFeatureTaskRuntimeWorker?,
    root: Path,
    steps: AcceptanceCriterionRepairStepRoles?,
  ): AcceptanceCriterionRepairSession? =
    if (scope != null && worker != null && steps != null) {
      AcceptanceCriterionRepairSession(scope, worker, root, steps, this)
    } else {
      null
    }

  internal fun start(
    session: AcceptanceCriterionRepairSession,
    phase: FeatureTaskRuntimePhaseStateRequest,
  ): RequiredPhaseWrite? {
    if (!session.worker.ownsAuditStep(phase.phaseId)) return null
    val snapshot = session.accounting.snapshot
    return when {
      snapshot == null -> RequiredPhaseWrite.Acknowledged
      snapshot.stage == AcceptanceCriterionRepairStage.ACCEPTED -> startQaReaudit(session, phase, snapshot)
      else -> startRepair(session, phase, snapshot)
    }
  }

  private fun startRepair(
    session: AcceptanceCriterionRepairSession,
    phase: FeatureTaskRuntimePhaseStateRequest,
    state: AcceptanceCriterionRepairState,
  ): RequiredPhaseWrite {
    val scope = session.accounting
    val mutation =
      startMutation(state, session.steps, phase)
        ?: return rejected(phase, "Acceptance repair cannot start from stage '${state.stage.wireValue}'.")
    val committed =
      if (mutation == AcceptanceCriterionRepairMutation.KeepProgress &&
        state.mode == AcceptanceCriterionRepairMode.INITIAL_DISCOVERY
      ) {
        RequiredPhaseWrite.Acknowledged
      } else {
        commit(session, phase, mutation)
      }
    if (committed == RequiredPhaseWrite.Acknowledged) scope.acknowledgeStarted(phase)
    return committed
  }

  private fun startQaReaudit(
    session: AcceptanceCriterionRepairSession,
    phase: FeatureTaskRuntimePhaseStateRequest,
    state: AcceptanceCriterionRepairState,
  ): RequiredPhaseWrite {
    if (phase.phaseId != session.steps.audit) return RequiredPhaseWrite.Acknowledged
    val scope = workflows.readQaRepairState(phase.workflowId)?.openFindingCriteria.orEmpty()
    val refs =
      state.qaReauditRefs(scope)
        ?: return rejected(phase, "The QA findings do not match the admitted acceptance criteria.")
    if (refs.isEmpty()) return RequiredPhaseWrite.Acknowledged
    val committed =
      commit(session, phase, AcceptanceCriterionRepairMutation.QaReaudit(refs))
    if (committed == RequiredPhaseWrite.Acknowledged) session.accounting.acknowledgeStarted(phase)
    return committed
  }

  private fun startMutation(
    state: AcceptanceCriterionRepairState,
    steps: AcceptanceCriterionRepairStepRoles,
    phase: FeatureTaskRuntimePhaseStateRequest,
  ): AcceptanceCriterionRepairMutation? =
    when {
      phase.phaseId == steps.repair ->
        when (state.stage) {
          AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED -> {
            val cycle = "${phase.workflowId}:${state.currentCriterionRef}:${state.revision + 1}"
            AcceptanceCriterionRepairMutation.Reserve(cycle)
          }
          AcceptanceCriterionRepairStage.REPAIR_RESERVED -> AcceptanceCriterionRepairMutation.KeepProgress
          else -> null
        }
      else -> AcceptanceCriterionRepairMutation.KeepProgress
    }

  internal fun complete(
    session: AcceptanceCriterionRepairSession,
    phase: FeatureTaskRuntimePhaseStateRequest,
  ): RequiredPhaseWrite? {
    val scope = session.accounting
    val inactive = !session.worker.ownsAuditStep(phase.phaseId)
    if (inactive || phase.phaseId == session.steps.planning) return null
    val envelope =
      phase.normalizedOutput?.envelopeWireMap()
        ?: return rejected(phase, "The normalized phase output is unavailable.")
    val state =
      invocationState(scope, phase) ?: scope.snapshot
        ?: return completeDiscovery(session, phase, envelope)
    return when (state.stage) {
      AcceptanceCriterionRepairStage.ACCEPTED -> prepareInteractionTrace(session, phase, state)
      AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED ->
        if (phase.phaseId == session.steps.audit) {
          completeInteractionTrace(session, phase, envelope, state)
        } else {
          rejected(phase, "Interaction tracing requires the audit phase.")
        }
      else -> completeExisting(session, phase, envelope, state)
    }
  }

  private fun prepareInteractionTrace(
    session: AcceptanceCriterionRepairSession,
    phase: FeatureTaskRuntimePhaseStateRequest,
    state: AcceptanceCriterionRepairState,
  ): RequiredPhaseWrite? {
    val trace = state.interactionTrace
    val untraced =
      phase.phaseId == session.steps.audit &&
        (trace.tracedSpecDigest != state.specDigest || trace.pendingRechecks.isNotEmpty())
    if (!untraced) return null
    return commit(
      session,
      phase,
      AcceptanceCriterionRepairMutation.PrepareInteractionTrace,
      CommitContext(expectedState = state),
    )
  }

  private fun completeDiscovery(
    session: AcceptanceCriterionRepairSession,
    phase: FeatureTaskRuntimePhaseStateRequest,
    envelope: FeatureTaskRuntimeWorkflowArtifactMap,
  ): RequiredPhaseWrite {
    if (phase.phaseId != session.steps.audit) {
      return rejected(
        phase,
        "Initial acceptance discovery requires the audit phase.",
      )
    }
    val checkpoint =
      sourceFingerprint(session.root).takeIf { it.ok && it.value.isNotBlank() }?.value
        ?: return rejected(phase, "The repository fingerprint is unavailable.")
    val tree = sha256Hex(checkpoint)
    val prose = auditProseValue(envelope) ?: phase.outputArtifact.orEmpty()
    if (prose.isBlank()) return rejected(phase, "Initial acceptance discovery requires an audit report.")
    val conclusion = envelope[SharedPayloadKeys.VERDICT] as? String
    val clarification = auditNeedsClarification(conclusion)
    val audit =
      if (clarification) {
        null
      } else {
        val satisfied =
          conclusion in
            setOf(
              FeatureTaskRuntimeVerdict.SATISFIED.wireValue,
              FeatureTaskRuntimeVerdict.NO_CHANGE_CONFIRMED.wireValue,
            )
        AcceptanceCriterionProseAudit(
          id = "${phase.workflowId}:${phase.phaseId}:${phase.attemptCount}:0",
          revision = 1,
          criterionRef = null,
          satisfied = satisfied,
          digest = sha256Hex("${phase.workflowId}:${phase.phaseId}:${phase.attemptCount}:0:$prose:$tree"),
          prose = prose,
          treeDigest = tree,
          checkpointId = checkpoint,
        )
      }
    val mutation =
      discover(session.accounting).copy(
        initialAudit = audit,
        initialClarification = prose.takeIf { clarification },
      )
    return commit(
      session,
      phase,
      mutation,
      CommitContext(
        auditEvidence = auditEvidence(mutation, envelope),
        expectedState = null,
        discoveryDiagnosticFacts = discoveryDiagnosticFacts(null, mutation),
      ),
    )
  }

  private fun completeExisting(
    session: AcceptanceCriterionRepairSession,
    phase: FeatureTaskRuntimePhaseStateRequest,
    envelope: FeatureTaskRuntimeWorkflowArtifactMap,
    state: AcceptanceCriterionRepairState,
  ): RequiredPhaseWrite {
    val prose = auditProseValue(envelope) ?: phase.outputArtifact.orEmpty()
    val checkpoint =
      sourceFingerprint(session.root).takeIf { it.ok && it.value.isNotBlank() }?.value
        ?: return rejected(phase, "The repository fingerprint is unavailable.")
    val tree = sha256Hex(checkpoint)
    val digest = sha256Hex("${phase.workflowId}:${phase.phaseId}:${phase.attemptCount}:${state.revision}:$prose:$tree")
    val ref = "${phase.workflowId}:${phase.phaseId}:${phase.attemptCount}:${state.revision}"
    val conclusion = envelope[SharedPayloadKeys.VERDICT] as? String
    val satisfied =
      conclusion in
        setOf(
          FeatureTaskRuntimeVerdict.SATISFIED.wireValue,
          FeatureTaskRuntimeVerdict.NO_CHANGE_CONFIRMED.wireValue,
        ) || phase.phaseId == session.steps.repair &&
        conclusion == FeatureTaskRuntimeVerdict.COMPLETED.wireValue
    val clarification = auditNeedsClarification(conclusion)
    val manifest = repairManifest(session, phase, state)
    val evidence =
      CompletionEvidence(
        ref,
        digest,
        prose,
        tree,
        checkpoint,
        satisfied,
        clarification,
        conclusion == FeatureTaskRuntimeVerdict.PROGRESS.wireValue ||
          conclusion == FeatureTaskRuntimeVerdict.NO_PROGRESS.wireValue,
        manifest.first,
        manifest.second,
      )
    val mutation =
      completionMutation(state, phase.phaseId, evidence, session.steps)
        ?: return rejected(phase, "The audit result does not match the active repair stage.")
    return commit(
      session,
      phase,
      mutation,
      CommitContext(
        auditEvidence(mutation, envelope),
        expectedState = state,
        attemptStatus =
          FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE.takeIf {
            phase.phaseId == session.steps.repair && mutation is AcceptanceCriterionRepairMutation.Outcome &&
              mutation.outcome.kind == AcceptanceCriterionRepairOutcomeKind.INCOMPLETE
          },
        discoveryDiagnosticFacts = discoveryDiagnosticFacts(state, mutation),
        verificationGapIds =
          if (
            state.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION &&
            mutation is AcceptanceCriterionRepairMutation.Outcome
          ) {
            state.criteria.singleOrNull { it.canonicalRef == mutation.outcome.criterionRef }
              ?.gaps?.filter { it.status == AcceptanceCriterionGapStatus.OPEN }?.map { it.id }.orEmpty()
          } else {
            emptyList()
          },
        repairManifestUnavailable = evidence.repairManifestUnavailable,
      ),
    )
  }

  private fun repairManifest(
    session: AcceptanceCriterionRepairSession,
    phase: FeatureTaskRuntimePhaseStateRequest,
    state: AcceptanceCriterionRepairState,
  ): Pair<List<String>, Boolean> {
    val repairAdmission =
      state.stage == AcceptanceCriterionRepairStage.REPAIR_RESERVED &&
        phase.phaseId == session.steps.audit && state.pendingClarificationQuestion != null
    val repairPhaseRecord =
      if (repairAdmission) {
        workflows.readArtifacts(phase.workflowId)?.let(::decodePhaseRecords)?.get(session.steps.repair)
      } else {
        null
      }
    val changedPaths =
      if (repairAdmission) repairPhaseRecord?.fileManifestIntroduced.orEmpty() else phase.fileManifestIntroduced
    return changedPaths to (repairAdmission && repairPhaseRecord == null)
  }

  private fun completeInteractionTrace(
    session: AcceptanceCriterionRepairSession,
    phase: FeatureTaskRuntimePhaseStateRequest,
    envelope: FeatureTaskRuntimeWorkflowArtifactMap,
    state: AcceptanceCriterionRepairState,
  ): RequiredPhaseWrite {
    val prose = auditProseValue(envelope) ?: phase.outputArtifact.orEmpty()
    val checkpoint =
      sourceFingerprint(session.root).takeIf { it.ok && it.value.isNotBlank() }?.value
        ?: return rejected(phase, "The repository fingerprint is unavailable.")
    val tree = sha256Hex(checkpoint)
    val outputId = "${phase.workflowId}:${phase.phaseId}:${phase.attemptCount}:${state.revision}"
    val targets = AcceptanceCriterionInteractionTargets.pending(state, requireNotNull(session.accounting.binding))
    val rechecks = state.interactionTrace.pendingRechecks
    val parsed =
      when (
        val result =
          AcceptanceCriterionInteractionTraceParser.parse(
            prose,
            targets,
            rechecks,
            outputRef = outputId,
          )
      ) {
        is AcceptanceCriterionInteractionTraceParse.Parsed -> result
        is AcceptanceCriterionInteractionTraceParse.Malformed ->
          degradedTrace(session, phase, state, targets, result)
            ?: return rejected(phase, AcceptanceCriterionInteractionTraceRound.rejection(result, state))
      }
    val settlement =
      AcceptanceCriterionInteractionTraceSettlement(
        outputId,
        sha256Hex("$outputId:$prose:$tree"),
        tree,
        checkpoint,
        parsed.entries,
        parsed.rechecks,
      )
    val predicted =
      state.recordInteractionTrace(settlement)
        ?: return rejected(phase, "The interaction trace does not match the pending scenarios.")
    if (parsed.ignoredRefs.isNotEmpty()) {
      AcceptanceCriterionTraceDiagnostics.warn(
        diagnostics,
        phase.workflowId,
        AcceptanceCriterionTraceDiagnostics.Warning(
          "out_of_scope",
          "pending_refs",
          "ignored",
          "refs=${parsed.ignoredRefs.joinToString(",")}".take(AcceptanceCriterionTraceDiagnostics.MAX_REASON),
        ),
      )
    }
    val evidence = AcceptanceCriterionInteractionTraceRound.evidence(settlement, predicted)
    val used = JsonCodec.valueToJsonString(evidence).toByteArray(Charsets.UTF_8).size
    val limit = FeatureTaskRuntimeHandoffProjectionBudget.PLANNING_PROJECTION.maxUtf8Bytes
    return if (used > limit) {
      rejected(
        phase,
        "Interaction trace evidence uses $used UTF-8 bytes; limit is $limit. Use concise references.",
      )
    } else {
      commit(
        session,
        phase,
        AcceptanceCriterionRepairMutation.InteractionTrace(settlement),
        CommitContext(
          AcceptanceCriterionRepairAuditEvidence(evidence),
          expectedState = state,
        ),
      )
    }
  }

  private fun degradedTrace(
    session: AcceptanceCriterionRepairSession,
    phase: FeatureTaskRuntimePhaseStateRequest,
    state: AcceptanceCriterionRepairState,
    targets: List<AcceptanceCriterionInteractionTarget>,
    malformed: AcceptanceCriterionInteractionTraceParse.Malformed,
  ): AcceptanceCriterionInteractionTraceParse.Parsed? {
    if (session.accounting.traceReaskScope != AcceptanceCriterionInteractionTraceRound.scopeKey(state)) return null
    if (state.interactionTrace.pendingRechecks.isNotEmpty()) return null
    AcceptanceCriterionTraceDiagnostics.warn(
      diagnostics,
      phase.workflowId,
      AcceptanceCriterionTraceDiagnostics.Warning(
        "degraded",
        malformed.code.name,
        "unknown",
        "reason=${malformed.reason.take(AcceptanceCriterionTraceDiagnostics.MAX_REASON)}",
      ),
    )
    val entries =
      targets.map {
        AcceptanceCriterionInteractionEntry(
          it.scenarioRef,
          AcceptanceCriterionInteractionVerdict.UNKNOWN,
          null,
          DEGRADED_UNKNOWN_REASON,
        )
      }
    return AcceptanceCriterionInteractionTraceParse.Parsed(entries, emptyList(), emptyList())
  }

  private data class CompletionEvidence(
    val ref: String,
    val digest: String,
    val prose: String,
    val tree: String,
    val checkpoint: String,
    val satisfied: Boolean,
    val clarification: Boolean,
    val unfinished: Boolean,
    val changedPaths: List<String>,
    val repairManifestUnavailable: Boolean,
  )

  private fun sourceFingerprint(root: Path): WorkflowGitOperationResult {
    val tracked =
      when (val result = gitOperations.trackedPaths(root)) {
        is WorkflowGitNameListResult.Listed -> result.names
        is WorkflowGitNameListResult.Failed -> return WorkflowGitOperationResult.Failed(error = result.error)
      }
    val dirty =
      when (val result = gitOperations.repositoryOwnedPaths(root)) {
        is WorkflowGitNameListResult.Listed -> result.names
        is WorkflowGitNameListResult.Failed -> return WorkflowGitOperationResult.Failed(error = result.error)
      }
    val paths =
      (tracked + dirty)
        .filterNot { isRuntimePrivatePath(it) || FeatureTaskRuntimeRunEvidenceOwnership.isRunEvidencePath(it) }
    return gitOperations.scopedContentFingerprint(root, paths)
  }

  private data class CommitContext(
    val auditEvidence: AcceptanceCriterionRepairAuditEvidence? = null,
    val expectedState: AcceptanceCriterionRepairState? = null,
    val attemptStatus: FeatureTaskRuntimeImplementationAttemptStatus? = null,
    val discoveryDiagnosticFacts: DiscoveryDiagnosticFacts = DiscoveryDiagnosticFacts(),
    val verificationGapIds: List<String> = emptyList(),
    val repairManifestUnavailable: Boolean = false,
  )

  private data class DiscoveryDiagnosticFacts(
    val fallbackCriteria: List<String> = emptyList(),
    val strayCriteria: List<String> = emptyList(),
  )

  private fun discoveryDiagnosticFacts(
    state: AcceptanceCriterionRepairState?,
    mutation: AcceptanceCriterionRepairMutation,
  ): DiscoveryDiagnosticFacts {
    val prose: String
    val eligible: Set<String>
    when (mutation) {
      is AcceptanceCriterionRepairMutation.Discover -> {
        prose = mutation.initialAudit?.prose ?: return DiscoveryDiagnosticFacts()
        eligible =
          mutation.admission.criteria.filter {
            it.sourceDisposition == AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED &&
              it.gaps.none { gap -> gap.status == AcceptanceCriterionGapStatus.OPEN }
          }.map { it.canonicalRef }.toSet()
      }
      is AcceptanceCriterionRepairMutation.ProseAudit -> {
        if (mutation.audit.criterionRef != null || state == null ||
          AcceptanceRepairDecision.auditKind(state) != AcceptanceRepairStep.Discover
        ) {
          return DiscoveryDiagnosticFacts()
        }
        prose = mutation.audit.prose
        eligible = AcceptanceRepairDecision.discoveryRefs(state)
      }
      else -> return DiscoveryDiagnosticFacts()
    }
    val named = AcceptanceCriterionGapReportParser.openCriteria(prose)?.intersect(eligible) ?: eligible
    val resolution = resolveAcceptanceCriterionDiscovery(prose, named)
    return DiscoveryDiagnosticFacts(resolution.fallbackCriteria.toList(), resolution.strayCriteria.toList())
  }

  private fun completionMutation(
    state: AcceptanceCriterionRepairState,
    phaseId: String,
    evidence: CompletionEvidence,
    steps: AcceptanceCriterionRepairStepRoles,
  ): AcceptanceCriterionRepairMutation? =
    with(evidence) {
      val pendingClarificationQuestion = state.pendingClarificationQuestion
      val auditedCriterion = auditedCriterion(state)
      when {
        phaseId == steps.repair -> repairCompletionMutation(state, evidence)
        state.stage == AcceptanceCriterionRepairStage.REPAIR_RESERVED && phaseId == steps.audit &&
          pendingClarificationQuestion != null -> repairAdmissionMutation(state, evidence, pendingClarificationQuestion)
        state.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION -> {
          val live = state.reservation ?: return@with null
          val criterion = state.criteria.singleOrNull { it.canonicalRef == live.criterionRef } ?: return@with null
          val verification =
            AcceptanceGapVerificationResolver.resolve(
              state,
              criterion,
              prose,
              treeMatches = state.admittedTreeDigest == tree,
              legacySatisfied = satisfied,
            )
          AcceptanceCriterionRepairMutation.Outcome(
            AcceptanceCriterionRepairOutcome(
              ref,
              live.reservationId,
              live.criterionRef,
              digest,
              verification.outcomeKind,
              prose,
              treeDigest = tree,
              checkpointId = checkpoint,
            ),
          )
        }
        clarification -> AcceptanceCriterionRepairMutation.Clarification(prose)
        else ->
          AcceptanceCriterionRepairMutation.ProseAudit(
            AcceptanceCriterionProseAudit(
              ref,
              state.revision + 1,
              auditedCriterion,
              satisfied,
              digest,
              prose,
              tree,
              checkpoint,
            ),
          )
      }
    }

  private fun auditedCriterion(state: AcceptanceCriterionRepairState): String? =
    state.currentCriterionRef.takeUnless {
      AcceptanceRepairDecision.auditKind(state) == AcceptanceRepairStep.Discover
    }

  private fun repairCompletionMutation(
    state: AcceptanceCriterionRepairState,
    evidence: CompletionEvidence,
  ): AcceptanceCriterionRepairMutation? =
    with(evidence) {
      val live = state.reservation ?: return@with null
      if (unfinished) {
        incompleteRepairOutcome(state, evidence)
      } else if (satisfied) {
        AcceptanceCriterionRepairMutation.RepairCompleted(
          live.reservationId,
          prose,
          tree,
          checkpoint,
          changedPaths,
        )
      } else {
        AcceptanceCriterionRepairMutation.Clarification(
          "$REPAIR_ADMISSION_CLARIFICATION_PREFIX Inspect the report and current source for this criterion. " +
            "Report progress when production work remains, including after a partial edit. " +
            "An unfinished implementation consumes one unsuccessful repair attempt. " +
            "Report satisfied only when the repair is complete. Report advance only for a concrete external " +
            "obstacle that prevents further repair, naming the missing input or external action. " +
            "Task size and unfinished implementation are not external obstacles. " + prose,
        )
      }
    }

  private fun repairAdmissionMutation(
    state: AcceptanceCriterionRepairState,
    evidence: CompletionEvidence,
    pendingClarificationQuestion: String,
  ): AcceptanceCriterionRepairMutation? =
    with(evidence) {
      val live = state.reservation ?: return@with null
      if (unfinished) {
        incompleteRepairOutcome(state, evidence)
      } else if (clarification) {
        AcceptanceCriterionRepairMutation.Clarification(prose)
      } else if (satisfied) {
        AcceptanceCriterionRepairMutation.RepairCompleted(
          live.reservationId,
          pendingClarificationQuestion,
          tree,
          checkpoint,
          changedPaths,
        )
      } else {
        incompleteRepairOutcome(state, evidence)
      }
    }

  private fun incompleteRepairOutcome(
    state: AcceptanceCriterionRepairState,
    evidence: CompletionEvidence,
  ): AcceptanceCriterionRepairMutation.Outcome? {
    val live = state.reservation ?: return null
    return AcceptanceCriterionRepairMutation.Outcome(
      AcceptanceCriterionRepairOutcome(
        evidence.ref,
        live.reservationId,
        live.criterionRef,
        evidence.digest,
        AcceptanceCriterionRepairOutcomeKind.INCOMPLETE,
        evidence.prose,
      ),
    )
  }

  internal fun unsuccessful(
    session: AcceptanceCriterionRepairSession,
    phase: FeatureTaskRuntimePhaseStateRequest,
    kind: AcceptanceCriterionRepairOutcomeKind,
  ): Boolean? {
    if (phase.phaseId != session.steps.repair) return null
    val scope = session.accounting
    if (scope.snapshot == null) return null
    val state = invocationState(scope, phase)
    val live = state?.reservation
    if (state == null || live == null) return false
    val prose =
      phase.normalizedOutput?.envelopeWireMap()?.let(::auditProseValue)
        ?: phase.blockedReason ?: phase.outputArtifact.orEmpty()
    val outcome =
      AcceptanceCriterionRepairOutcome(
        "${live.reservationId}:outcome",
        live.reservationId,
        live.criterionRef,
        sha256Hex("${live.reservationId}:$kind:$prose"),
        kind,
        prose,
      )
    val attemptStatus =
      if (kind == AcceptanceCriterionRepairOutcomeKind.INCOMPLETE) {
        FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE
      } else {
        FeatureTaskRuntimeImplementationAttemptStatus.BLOCKED
      }
    return commit(
      session,
      phase,
      AcceptanceCriterionRepairMutation.Outcome(outcome),
      CommitContext(expectedState = state, attemptStatus = attemptStatus),
    ) == RequiredPhaseWrite.Acknowledged
  }

  private fun invocationState(
    scope: AcceptanceCriterionRepairRunState,
    phase: FeatureTaskRuntimePhaseStateRequest,
  ): AcceptanceCriterionRepairState? =
    scope.invocation?.takeIf {
      it.phaseId == phase.phaseId && it.attempt == phase.attemptCount
    }?.accounting

  private fun discover(scope: AcceptanceCriterionRepairRunState): AcceptanceCriterionRepairMutation.Discover {
    val binding = requireNotNull(scope.binding)
    val criteria =
      binding.authoredCriteria.mapIndexed { index, text ->
        val identity = requireNotNull(acceptanceCriterionIdentity(text, index + 1))
        AcceptanceCriterionRepairCriterion(
          identity.canonicalRef,
          originalAcceptanceCriterionAlias(text),
          text,
          index + 1,
          AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
          AcceptanceCriterionValidationObligation.DEFERRED,
          true,
          binding.specDigest,
        )
      }
    return AcceptanceCriterionRepairMutation.Discover(
      AcceptanceCriterionRepairAdmission(
        binding.catalogId,
        binding.executionId,
        binding.specDigest,
        binding.authoredCriteria,
        criteria,
        emptyList(),
      ),
    )
  }

  private fun commit(
    session: AcceptanceCriterionRepairSession,
    phase: FeatureTaskRuntimePhaseStateRequest,
    mutation: AcceptanceCriterionRepairMutation,
    context: CommitContext = CommitContext(expectedState = session.accounting.snapshot),
  ): RequiredPhaseWrite {
    val additionalPhaseId =
      session.steps.repair.takeIf {
        phase.phaseId == session.steps.audit && (
          mutation is AcceptanceCriterionRepairMutation.Outcome &&
            mutation.outcome.kind == AcceptanceCriterionRepairOutcomeKind.INCOMPLETE
        )
      }
    val scope = session.accounting
    val state = context.expectedState
    val result =
      persistence.write(
        session.worker,
        requireNotNull(scope.binding),
        AcceptanceCriterionRepairCommit(
          ExpectedAcceptanceCriterionRepairState(
            state?.revision,
            state?.currentCriterionRef,
            state?.reservation?.reservationId,
          ),
          mutation,
          phase,
          context.auditEvidence,
          context.attemptStatus,
          stepRoles = session.steps,
          additionalPhaseId = additionalPhaseId,
          additionalAttemptStatus =
            FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE.takeIf {
              additionalPhaseId != null
            },
          repairChangedPaths =
            phase.fileManifestIntroduced.takeIf {
              phase.phaseId == session.steps.repair && phase.finished
            }.orEmpty(),
          discoveryFallbackCriteria = context.discoveryDiagnosticFacts.fallbackCriteria,
          discoveryStrayCriteria = context.discoveryDiagnosticFacts.strayCriteria,
          verificationGapIds = context.verificationGapIds,
          repairManifestUnavailable = context.repairManifestUnavailable,
        ),
        session.root,
      )
    val committed =
      when (result) {
        is AcceptanceCriterionRepairWriteResult.Committed -> result.state
        is AcceptanceCriterionRepairWriteResult.CommittedWithProjectionFailure -> result.state
        is AcceptanceCriterionRepairWriteResult.Refused -> {
          val detail =
            if (result.reason == AcceptanceCriterionRepairRefusal.SPEC_SOURCE_CHANGED) {
              "The bound specification '${session.worker.governedSpecPath}' changed or is unreadable. " +
                "The producer output is retained. Restore the admitted spec or resume with scoped replanning."
            } else {
              "Acceptance repair commit refused: ${result.reason.wireValue}. The producer output is retained."
            }
          return rejected(phase, detail)
        }
      }
    scope.acknowledgeCommitted(committed)
    return RequiredPhaseWrite.Acknowledged
  }

  private fun rejected(
    phase: FeatureTaskRuntimePhaseStateRequest,
    detail: String,
  ): RequiredPhaseWrite.Rejected =
    RequiredPhaseWrite.Rejected(
      if (phase.status == WorkflowStepStatus.RUNNING.wireValue) {
        RequiredPhaseWriteKind.START
      } else {
        RequiredPhaseWriteKind.COMPLETION
      },
      phase.workflowId,
      phase.phaseId,
      phase.attemptCount,
      detail = detail,
    )

  private fun auditEvidence(
    mutation: AcceptanceCriterionRepairMutation,
    envelope: FeatureTaskRuntimeWorkflowArtifactMap,
  ): AcceptanceCriterionRepairAuditEvidence? {
    val requiresEvidence =
      mutation is AcceptanceCriterionRepairMutation.Discover ||
        mutation is AcceptanceCriterionRepairMutation.Outcome && mutation.outcome.kind in
        setOf(
          AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS,
          AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE,
        )
    if (!requiresEvidence) return null
    val outputs = JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS]) ?: return null
    val payload = JsonCodec.anyToStringAnyMap(outputs[Keys.ACCEPTANCE_CRITERION_AUDIT_RESULT]) ?: return null
    return AcceptanceCriterionRepairAuditEvidence(
      FeatureTaskRuntimeWorkflowArtifactMap.from(payload),
    )
  }

  private companion object {
    const val DEGRADED_UNKNOWN_REASON = "malformed audit output"
  }
}
