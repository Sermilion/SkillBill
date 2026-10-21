package skillbill.engine.featuretask.persist.acceptance

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
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
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteKind
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
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
import skillbill.workflow.taskruntime.model.audit.acceptanceCriterionIdentity
import skillbill.workflow.taskruntime.model.audit.originalAcceptanceCriterionAlias
import skillbill.workflow.taskruntime.model.audit.qaReauditRefs
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttemptStatus
import java.nio.file.Path
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

@Inject
class AcceptanceCriterionRepairPhaseRecorder(
  private val persistence: AcceptanceCriterionRepairPersistence,
  private val gitOperations: WorkflowGitOperations,
  private val workflows: FeatureTaskRuntimeWorkflowPersistence,
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
    val envelope = phase.normalizedOutput?.envelopeWireMap()
    if (envelope == null) return rejected(phase, "The normalized phase output is unavailable.")
    val state = invocationState(scope, phase) ?: scope.snapshot
    if (state?.stage == AcceptanceCriterionRepairStage.ACCEPTED) return null
    return if (state == null) {
      completeDiscovery(
        session,
        phase,
        envelope,
      )
    } else {
      completeExisting(session, phase, envelope, state)
    }
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
    if (gitOperations.repositoryFingerprint(session.root).let {
        !it.ok || it.value.isBlank()
      }
    ) {
      return rejected(phase, "The repository fingerprint is unavailable.")
    }
    val mutation = discover(session.accounting)
    return commit(
      session,
      phase,
      mutation,
      CommitContext(auditEvidence = auditEvidence(mutation, envelope), expectedState = null),
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
      gitOperations.repositoryFingerprint(session.root).takeIf { it.ok && it.value.isNotBlank() }?.value
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
        )
    val clarification = needsClarification(conclusion)
    val evidence =
      CompletionEvidence(
        ref,
        digest,
        prose,
        tree,
        checkpoint,
        satisfied,
        clarification,
        conclusion == FeatureTaskRuntimeVerdict.PROGRESS.wireValue,
      )
    return completionMutation(state, phase.phaseId, evidence, session.steps)?.let { mutation ->
      commit(
        session,
        phase,
        mutation,
        CommitContext(
          auditEvidence(mutation, envelope),
          expectedState = state,
          attemptStatus =
            FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE.takeIf {
              phase.phaseId == session.steps.repair && mutation is AcceptanceCriterionRepairMutation.ContinueRepair
            },
        ),
      )
    } ?: rejected(phase, "The audit result does not match the active repair stage.")
  }

  private fun needsClarification(conclusion: String?): Boolean =
    conclusion !in
      setOf(
        FeatureTaskRuntimeVerdict.SATISFIED.wireValue,
        FeatureTaskRuntimeVerdict.NO_CHANGE_CONFIRMED.wireValue,
        FeatureTaskRuntimeVerdict.NO_CHANGE_REJECTED.wireValue,
        FeatureTaskRuntimeVerdict.ADVANCE.wireValue,
      )

  private data class CompletionEvidence(
    val ref: String,
    val digest: String,
    val prose: String,
    val tree: String,
    val checkpoint: String,
    val satisfied: Boolean,
    val clarification: Boolean,
    val continuing: Boolean,
  )

  private data class CommitContext(
    val auditEvidence: AcceptanceCriterionRepairAuditEvidence? = null,
    val expectedState: AcceptanceCriterionRepairState? = null,
    val attemptStatus: FeatureTaskRuntimeImplementationAttemptStatus? = null,
  )

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
        clarification -> AcceptanceCriterionRepairMutation.Clarification(prose)
        state.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION -> {
          val live = state.reservation ?: return@with null
          val accepted = satisfied && state.admittedTreeDigest == tree
          AcceptanceCriterionRepairMutation.Outcome(
            AcceptanceCriterionRepairOutcome(
              ref,
              live.reservationId,
              live.criterionRef,
              digest,
              if (accepted) {
                AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS
              } else {
                AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE
              },
              prose,
            ),
          )
        }
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
    state.currentCriterionRef.takeUnless { state.mode == AcceptanceCriterionRepairMode.INITIAL_DISCOVERY }

  private fun repairCompletionMutation(
    state: AcceptanceCriterionRepairState,
    evidence: CompletionEvidence,
  ): AcceptanceCriterionRepairMutation? =
    with(evidence) {
      val live = state.reservation ?: return@with null
      if (continuing) {
        AcceptanceCriterionRepairMutation.ContinueRepair(live.reservationId, prose)
      } else if (satisfied) {
        AcceptanceCriterionRepairMutation.RepairCompleted(live.reservationId, prose, tree, checkpoint)
      } else {
        AcceptanceCriterionRepairMutation.Clarification(
          "Read-only repair-report admission. Inspect the report and current source for this criterion. " +
            "Report progress when production work remains and can continue, " +
            "including an agent stopping after a partial edit. " +
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
      if (continuing) {
        AcceptanceCriterionRepairMutation.ContinueRepair(live.reservationId, prose)
      } else if (clarification) {
        AcceptanceCriterionRepairMutation.Clarification(prose)
      } else if (satisfied) {
        AcceptanceCriterionRepairMutation.RepairCompleted(
          live.reservationId,
          pendingClarificationQuestion,
          tree,
          checkpoint,
        )
      } else {
        AcceptanceCriterionRepairMutation.Outcome(
          AcceptanceCriterionRepairOutcome(
            ref,
            live.reservationId,
            live.criterionRef,
            digest,
            AcceptanceCriterionRepairOutcomeKind.INCOMPLETE,
            prose,
          ),
        )
      }
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

  private fun serialNextSteps(
    steps: AcceptanceCriterionRepairStepRoles,
    mutation: AcceptanceCriterionRepairMutation,
  ): Map<AcceptanceCriterionRepairStage, String> =
    mapOf(
      AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED to
        steps.planning,
      AcceptanceCriterionRepairStage.REPAIR_RESERVED to
        if (mutation is AcceptanceCriterionRepairMutation.Clarification) steps.audit else steps.repair,
      AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED to
        if (mutation is AcceptanceCriterionRepairMutation.Outcome &&
          mutation.outcome.kind == AcceptanceCriterionRepairOutcomeKind.INCOMPLETE
        ) {
          steps.planning
        } else {
          steps.audit
        },
      AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION to
        steps.audit,
    )

  private fun commit(
    session: AcceptanceCriterionRepairSession,
    phase: FeatureTaskRuntimePhaseStateRequest,
    mutation: AcceptanceCriterionRepairMutation,
    context: CommitContext = CommitContext(expectedState = session.accounting.snapshot),
  ): RequiredPhaseWrite {
    val additionalPhaseId =
      session.steps.repair.takeIf {
        phase.phaseId == session.steps.audit && (
          mutation is AcceptanceCriterionRepairMutation.ContinueRepair ||
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
          serialNextSteps(session.steps, mutation),
          additionalPhaseId,
          FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE.takeIf { additionalPhaseId != null },
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
}
