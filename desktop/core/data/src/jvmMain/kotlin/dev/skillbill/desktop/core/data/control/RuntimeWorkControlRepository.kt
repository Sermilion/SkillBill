package dev.skillbill.desktop.core.data.control

import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.runtime.SessionClosedException
import dev.skillbill.desktop.core.data.runtime.readOrProblem
import dev.skillbill.desktop.core.data.session.DatabaseSessionComponent
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.data.session.LaunchBoardTarget
import dev.skillbill.desktop.core.data.session.LaunchReservationHandle
import dev.skillbill.desktop.core.data.session.LaunchReservations
import dev.skillbill.desktop.core.data.session.ReservationAdmission
import dev.skillbill.desktop.core.data.session.launchKey
import dev.skillbill.desktop.core.domain.model.BoardQuery
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.ControlRejection
import dev.skillbill.desktop.core.domain.model.DatabaseFileIdentity
import dev.skillbill.desktop.core.domain.model.IncidentPrerequisite
import dev.skillbill.desktop.core.domain.model.OperationCardDismissOutcome
import dev.skillbill.desktop.core.domain.model.OperationCardView
import dev.skillbill.desktop.core.domain.model.OperationCardLookup
import dev.skillbill.desktop.core.domain.model.PrerequisiteInstruction
import dev.skillbill.desktop.core.domain.model.PurgeOutcome
import dev.skillbill.desktop.core.domain.model.ExportPreflightOutcome
import dev.skillbill.desktop.core.domain.model.ExportOutcome
import dev.skillbill.desktop.core.domain.model.ExportRefusalCategory
import dev.skillbill.desktop.core.domain.model.ArchiveInspectOutcome
import dev.skillbill.desktop.core.domain.model.ImportOutcome
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.RecoveryAttemptStatus
import dev.skillbill.desktop.core.domain.model.RecoveryOutcome
import dev.skillbill.desktop.core.domain.model.RecoveryPreview
import dev.skillbill.desktop.core.domain.model.RecoveryPreviewOutcome
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind
import dev.skillbill.desktop.core.domain.model.NewTaskKind
import dev.skillbill.desktop.core.domain.model.NewTaskStart
import dev.skillbill.desktop.core.domain.model.NewTaskStartOutcome
import dev.skillbill.desktop.core.domain.repository.RepositoryBranchesRepository
import dev.skillbill.desktop.core.domain.model.RepositoryBranchesRead
import dev.skillbill.desktop.core.domain.model.VerifyQaChoice
import dev.skillbill.desktop.core.domain.model.newWorkBranchProblem
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.domain.settings.HistoryWindow
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.core.domain.settings.SettingsRejection
import dev.skillbill.desktop.core.domain.qa.QaDevicePick
import dev.skillbill.desktop.core.domain.qa.QaDevicePickKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.contracts.operationcard.OperationCardProjection
import skillbill.di.core.RuntimeComponent
import skillbill.engine.work.control.WorkControlService
import skillbill.engine.operation.core.OperationCardRevalidation
import skillbill.engine.goalrunner.model.GoalRunnerPurgeRequest
import skillbill.engine.goalrunner.model.GoalRunnerExportRequest
import skillbill.engine.goalrunner.model.GoalRunnerExportRefusalCategory
import skillbill.engine.goalrunner.model.GoalRunnerImportRequest
import skillbill.engine.goalrunner.model.GoalRunnerExportPreflightResult
import skillbill.engine.goalrunner.model.GoalRunnerExportResult
import skillbill.engine.goalrunner.model.GoalRunnerArchiveInspectResult
import skillbill.engine.goalrunner.model.GoalRunnerImportResult
import skillbill.engine.work.control.model.NewTaskIdentityPolicy
import skillbill.engine.work.control.model.NewTaskIntakeRequest
import skillbill.engine.work.control.model.NewTaskPreparation
import skillbill.engine.work.control.model.NewTaskRunKind
import skillbill.engine.featuretask.model.execution.QaNamedDevice
import skillbill.engine.featuretask.model.execution.QaNamedDeviceKind
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.engine.work.control.model.WorkControlAction
import kotlinx.coroutines.future.await
import skillbill.engine.work.control.model.NewTaskStartResult
import skillbill.engine.work.control.model.OperationCardDismissal
import skillbill.engine.work.control.model.OperationStartRequest
import skillbill.engine.work.control.model.OperatorConfirmation
import skillbill.engine.work.control.model.StandaloneCheckout
import skillbill.engine.work.control.model.StandalonePhaseRequest
import skillbill.engine.work.control.model.WorkControlRejection
import skillbill.engine.work.control.model.WorkControlResult
import skillbill.engine.work.control.model.WorkControlTarget
import skillbill.engine.work.control.model.WorkRunTarget
import skillbill.ports.db.model.FencedTransactionResult
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.idestatus.model.WorkDetailRequest
import skillbill.ports.work.model.WorkRecoveryPreviewResult
import skillbill.ports.work.model.WorkStoreObservation
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentPrerequisiteKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryPreview
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Path
import kotlin.time.toJavaInstant
import dev.skillbill.desktop.core.domain.model.VerifyReviewModeChoice
import dev.skillbill.desktop.core.domain.model.VerifyStartChoices
import skillbill.engine.work.control.model.VerifyStartOptions
import skillbill.workflow.verify.model.VerifyQaSelection
import skillbill.workflow.verify.model.VerifyReviewMode

/** The unfiltered default board the reservation end rule watches. */
private val RESERVATION_BOARD = BoardQuery(HistoryWindow.DEFAULT, filter = "", pageSize = WorkPaginationLimits.DEFAULT_PAGE_SIZE)

private val NO_SESSION = ReadProblem.Runtime("The database session is not open.")

private const val DATABASE_CHANGED_REASON = "The database changed. Reload the card."
private val DATABASE_CHANGED = ReadProblem.Runtime(DATABASE_CHANGED_REASON)

/**
 * Run controls and goal writes against the open session's runtime. Every request first checks that the item was read from
 * the database file the session holds now, so a request against a switched or replaced database is refused unsent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RuntimeWorkControlRepository(
  private val sessions: DatabaseSessionManager,
  private val settings: AppSettingsRepository,
  private val launches: LaunchReservations,
  private val diagnostics: Diagnostics,
  private val appScope: ApplicationCoroutineScope,
  private val branches: RepositoryBranchesRepository,
) : WorkControlRepository {
  init {
    appScope.scope.launch {
      combine(sessions.sessions, launches.keys) { session, keys -> session to keys.isNotEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { (session, reserved) ->
          if (!reserved || session == null) emptyFlow()
          else session.board.observeComplete(RESERVATION_BOARD)
            .mapNotNull { result -> (result as? Outcome.Success)?.value?.let { session.selection.selection.id to it } }
        }
        .onEach { (sessionId, page) -> sessions.acceptReservationBoard(sessionId, page) }
        .collect { }
    }
  }

  override val reservations: StateFlow<Set<String>> get() = launches.keys

  override suspend fun pauseAfterSubtask(item: WorkItem) = goalControl(item, WorkControlAction.PAUSE, ControlKind.PAUSE_AFTER_SUBTASK)

  override suspend fun pauseNow(item: WorkItem) = goalControl(item, WorkControlAction.STOP, ControlKind.PAUSE_NOW)

  override suspend fun cancel(item: WorkItem): ControlOutcome = runControl(item, "control_cancel") { runtime, target ->
    runtime.workControlService.request(target, WorkControlAction.CANCEL).toControlOutcome(ControlKind.CANCEL, diagnostics)
  }

  override suspend fun launch(item: WorkItem, agentId: String?): ControlOutcome {
    val session = currentSession(item) ?: return ControlOutcome.Refused(ControlRejection.CHANGED_TARGET, null)
    val qaDevice = if (item.kind == WorkKind.Goal || item.control.phaseId == SkeletonDefinition.QA.id) when (val result = readQaDevice()) {
      is Outcome.Failure -> return ControlOutcome.Failed(ReadProblem.Runtime("Could not read the saved QA device selection."))
      is Outcome.Success -> result.value
    } else null
    if (launches.isReserved(item.launchKey)) return ControlOutcome.AlreadyLaunching
    val snapshot = when (val result = session.board.completeSnapshot(RESERVATION_BOARD)) {
      is Outcome.Failure -> return when {
        !sessionIsCurrent(session, item) -> ControlOutcome.Refused(ControlRejection.CHANGED_TARGET, null)
        launches.isReserved(item.launchKey) -> ControlOutcome.AlreadyLaunching
        else -> ControlOutcome.Failed(result.error)
      }
      is Outcome.Success -> result.value
    }
    if (!sessionIsCurrent(session, item)) return ControlOutcome.Refused(ControlRejection.CHANGED_TARGET, null)
    val admission = sessions.admitReservation(
      session,
      item.launchKey,
      LaunchBoardTarget.Item(item.id, item.kind, item.control.repositoryIdentity),
      item.lifecycle,
      snapshot,
    )
    val handle = when (admission) {
      ReservationAdmission.AlreadyReserved -> return ControlOutcome.AlreadyLaunching
      ReservationAdmission.ChangedSession -> return ControlOutcome.Refused(ControlRejection.CHANGED_TARGET, null)
      is ReservationAdmission.Reserved -> admission.handle
    }
    // A run with a recorded agent keeps it; the default agent only fills a launch that names none.
    val launchAgent = agentId ?: if (item.agentId == null) readDefaultAgent() else null
    return reserved(handle) {
      runControl(item, "control_launch") { runtime, target ->
        runtime.workControlService.request(target, WorkControlAction.LAUNCH, launchAgent, qaDevice).toControlOutcome(ControlKind.LAUNCH, diagnostics)
      }
    }
  }

  override suspend fun startGoal(repoRoot: String, issueKey: String, agentId: String?): ControlOutcome {
    val session = sessions.sessions.value ?: return ControlOutcome.Failed(NO_SESSION)
    val qaDevice = when (val result = readQaDevice()) {
      is Outcome.Failure -> return ControlOutcome.Failed(ReadProblem.Runtime("Could not read the saved QA device selection."))
      is Outcome.Success -> result.value
    }
    if (!sessionIsCurrent(session)) return ControlOutcome.Refused(ControlRejection.CHANGED_TARGET, null)
    if (launches.isReserved(issueKey)) return ControlOutcome.AlreadyLaunching
    val goalAgent = agentId ?: readDefaultAgent()
    val identity = callInSession(
      session,
      "control_goal_identity",
      { Outcome.Failure(it) },
      stale = { Outcome.Failure(NO_SESSION) },
    ) { runtime -> Outcome.Success(runtime.repositoryEnclosingRootPort.repositoryIdentity(Path.of(repoRoot))) }
    val repositoryIdentity = when (identity) {
      is Outcome.Failure -> return when {
        !sessionIsCurrent(session) -> ControlOutcome.Refused(ControlRejection.CHANGED_TARGET, null)
        else -> ControlOutcome.Failed(identity.error)
      }
      is Outcome.Success -> identity.value
    }
    val snapshot = when (val result = session.board.completeSnapshot(RESERVATION_BOARD)) {
      is Outcome.Failure -> return when {
        !sessionIsCurrent(session) -> ControlOutcome.Refused(ControlRejection.CHANGED_TARGET, null)
        launches.isReserved(issueKey) -> ControlOutcome.AlreadyLaunching
        else -> ControlOutcome.Failed(result.error)
      }
      is Outcome.Success -> result.value
    }
    if (!sessionIsCurrent(session)) return ControlOutcome.Refused(ControlRejection.CHANGED_TARGET, null)
    val admission = sessions.admitReservation(
      session,
      issueKey,
      LaunchBoardTarget.Goal(issueKey, repositoryIdentity),
      baseline = null,
      page = snapshot,
    )
    val handle = when (admission) {
      ReservationAdmission.AlreadyReserved -> return ControlOutcome.AlreadyLaunching
      ReservationAdmission.ChangedSession -> return ControlOutcome.Refused(ControlRejection.CHANGED_TARGET, null)
      is ReservationAdmission.Reserved -> admission.handle
    }
    return reserved(handle) {
      callInSession(session, "control_start_goal", ControlOutcome::Failed) { runtime ->
        if (!sessionIsCurrent(session)) return@callInSession ControlOutcome.Refused(ControlRejection.CHANGED_TARGET, null)
        val observation = session.purgeIdentity.observation
          ?: return@callInSession ControlOutcome.Refused(ControlRejection.CHANGED_TARGET, null)
        when (val result = runtime.databaseSession.transactionFenced(observation) {
          if (!sessionIsCurrent(session)) null
          else runtime.workControlService.startGoal(Path.of(repoRoot), issueKey, goalAgent, qaDevice)
        }) {
          FencedTransactionResult.ChangedTarget -> ControlOutcome.Refused(ControlRejection.CHANGED_TARGET, null)
          is FencedTransactionResult.Committed -> result.value?.toControlOutcome(ControlKind.START_GOAL, diagnostics)
            ?: ControlOutcome.Refused(ControlRejection.CHANGED_TARGET, null)
        }
      }
    }
  }

  override suspend fun startNewTask(request: NewTaskStart): NewTaskStartOutcome {
    val qaDevice = if (request.usesQaDevice()) when (val result = readQaDevice()) {
      is Outcome.Failure -> return NewTaskStartOutcome.Failed(ReadProblem.Runtime("Could not read the saved QA device selection."))
      is Outcome.Success -> result.value
    } else null
    val session = request.expectedDatabaseSessionId?.let(sessions::currentComponent)
      ?: return NewTaskStartOutcome.Refused("The database changed. Try again.")
    if (!sessionIsCurrent(session)) return NewTaskStartOutcome.Refused("The database changed. Try again.")
    val readiness = callInSession(session, "control_new_task_schema", { Outcome.Failure(it) }, stale = { Outcome.Failure(NO_SESSION) }) { runtime ->
      runtime.databaseSchema.migrateExistingDatabase()
      Outcome.Success(Unit)
    }
    if (readiness is Outcome.Failure) return if (!sessionIsCurrent(session)) NewTaskStartOutcome.Refused("The database changed. Try again.")
      else NewTaskStartOutcome.Failed(readiness.error)
    when (request.kind) {
      NewTaskKind.Review, NewTaskKind.Validation, NewTaskKind.Qa, NewTaskKind.Pr, NewTaskKind.Monitor ->
        return startStandaloneTask(request, session, qaDevice)
      NewTaskKind.Verify, NewTaskKind.PrReviewFix ->
        return startOperationTask(request, session, qaDevice)
      NewTaskKind.GoalRun, NewTaskKind.PlanOnly -> Unit
    }
    when (val read = branches.branches(request.repoRoot)) {
      is RepositoryBranchesRead.Listed -> read.branches.newWorkBranchProblem(request.baseBranch)?.let {
        diagnostics.record("desktop_new_work_branch_choice_required")
        return NewTaskStartOutcome.Refused(it)
      }
      is RepositoryBranchesRead.NotARepository -> return NewTaskStartOutcome.Refused("${read.path} is not a git repository.")
      is RepositoryBranchesRead.Unavailable -> return NewTaskStartOutcome.Failed(read.problem)
    }
    val identity = callInSession(
      session,
      "control_new_task_identity",
      { Outcome.Failure(it) },
      stale = { Outcome.Failure(NO_SESSION) },
    ) { runtime -> Outcome.Success(runtime.repositoryEnclosingRootPort.repositoryIdentity(Path.of(request.repoRoot))) }
    val repositoryIdentity = when (identity) {
      is Outcome.Failure -> return if (!sessionIsCurrent(session)) NewTaskStartOutcome.Refused("The database changed. Try again.")
        else NewTaskStartOutcome.Failed(identity.error)
      is Outcome.Success -> identity.value
    }
    val snapshot = when (val result = session.board.completeSnapshot(RESERVATION_BOARD)) {
      is Outcome.Failure -> return if (!sessionIsCurrent(session)) NewTaskStartOutcome.Refused("The database changed. Try again.")
        else NewTaskStartOutcome.Failed(result.error)
      is Outcome.Success -> result.value
    }
    if (!sessionIsCurrent(session)) return NewTaskStartOutcome.Refused("The database changed. Try again.")
    val kind = if (request.kind == NewTaskKind.GoalRun) NewTaskRunKind.GOAL else NewTaskRunKind.PLAN
    val triedKeys = mutableSetOf<String>()
    repeat(3) { attempt ->
      val preparedResult = callInSession(session, "control_prepare_new_task", { Outcome.Failure(it) }, stale = { Outcome.Failure(NO_SESSION) }) { runtime ->
        Outcome.Success(
          runtime.workControlService.prepareNewTask(
            Path.of(request.repoRoot),
            NewTaskIntakeRequest(
              request.requirements,
              request.title,
              request.baseBranch,
              request.keyChoice,
              launches.keys.value + triedKeys,
              request.reuseRequested,
              NewTaskIdentityPolicy.RESOLVED_DESKTOP_IDENTITY,
            ),
          ),
        )
      }
      val prepared = when (preparedResult) {
        is Outcome.Failure -> return if (!sessionIsCurrent(session)) NewTaskStartOutcome.Refused("The database changed. Try again.") else NewTaskStartOutcome.Failed(preparedResult.error)
        is Outcome.Success -> preparedResult.value
      }
      when (prepared) {
        is NewTaskPreparation.NeedsKey -> return NewTaskStartOutcome.NeedsKey(prepared.choices)
        is NewTaskPreparation.Refused -> return NewTaskStartOutcome.Refused(prepared.reason)
        is NewTaskPreparation.Ready -> {
          triedKeys += prepared.key
          val target = if (kind == NewTaskRunKind.GOAL) LaunchBoardTarget.Goal(prepared.key, repositoryIdentity)
            else LaunchBoardTarget.PlanPhase(prepared.key, repositoryIdentity)
          val admission = sessions.admitReservation(session, prepared.key, target, null, snapshot)
          val handle = when (admission) {
            ReservationAdmission.ChangedSession -> return NewTaskStartOutcome.Refused("The database changed. Try again.")
            ReservationAdmission.AlreadyReserved -> {
              if (prepared.allocated && attempt < 2) return@repeat
              if (prepared.allocated) return NewTaskStartOutcome.Refused("Couldn't assign a free issue key. Try again.")
              return NewTaskStartOutcome.Refused("${prepared.key} is already in use. Choose another key.")
            }
            is ReservationAdmission.Reserved -> admission.handle
          }
          val outcome = try {
            var keyWasTaken = false
            callInSession(
              session,
              "control_start_new_task",
              NewTaskStartOutcome::Failed,
              stale = { NewTaskStartOutcome.Refused("The database changed. Try again.") },
            ) { runtime ->
              if (!sessionIsCurrent(session)) return@callInSession NewTaskStartOutcome.Refused("The database changed. Try again.")
              val observation = session.purgeIdentity.observation
                ?: return@callInSession NewTaskStartOutcome.Refused("The database changed. Try again.")
              when (val result = runtime.databaseSession.transactionFenced(observation) {
                if (!sessionIsCurrent(session)) null
                else runtime.workControlService.startNewTask(Path.of(request.repoRoot), prepared, kind, request.agentId, qaDevice)
              }) {
                FencedTransactionResult.ChangedTarget -> NewTaskStartOutcome.Refused("The database changed. Try again.")
                is FencedTransactionResult.Committed -> when (val started = result.value) {
                  null -> NewTaskStartOutcome.Refused("The database changed. Try again.")
                  NewTaskStartResult.KeyTaken -> {
                    keyWasTaken = true
                    NewTaskStartOutcome.Refused("${prepared.key} is already in use. Choose another key.")
                  }
                  is NewTaskStartResult.Refused -> NewTaskStartOutcome.Refused(started.reason)
                  is NewTaskStartResult.Running -> {
                    launches.releaseOnExit(handle, started.failure)
                    NewTaskStartOutcome.Started(prepared.key, request.kind, awaitFailure = { started.failure.await() })
                  }
                  NewTaskStartResult.Started -> NewTaskStartOutcome.Started(prepared.key, request.kind)
                  is NewTaskStartResult.AwaitingConfirmation -> NewTaskStartOutcome.Refused("A goal run cannot wait for a confirmation.")
                }
              }
            }.let { it to keyWasTaken }
          } catch (failure: Throwable) {
            launches.release(handle)
            throw failure
          }
          val (startOutcome, keyWasTaken) = outcome
          if (startOutcome is NewTaskStartOutcome.Started) launches.settle(handle, true) else launches.release(handle)
          if (keyWasTaken && prepared.allocated) {
            if (attempt < 2) return@repeat
            return NewTaskStartOutcome.Refused("Couldn't assign a free issue key. Try again.")
          }
          return startOutcome
        }
      }
    }
    return NewTaskStartOutcome.Refused("Couldn't assign a free issue key. Try again.")
  }

  private suspend fun startStandaloneTask(
    request: NewTaskStart,
    session: DatabaseSessionComponent,
    qaDevice: QaNamedDevice?,
  ): NewTaskStartOutcome {
    val phaseId = when (request.kind) {
      NewTaskKind.Review -> NewTaskActionKind.Review.wire
      NewTaskKind.Validation -> NewTaskActionKind.Validation.wire
      NewTaskKind.Qa -> NewTaskActionKind.Qa.wire
      NewTaskKind.Pr -> NewTaskActionKind.Pr.wire
      NewTaskKind.Monitor -> NewTaskActionKind.Monitor.wire
      else -> return NewTaskStartOutcome.Refused("Choose a standalone phase.")
    }
    return startReservedStandalone(request, session, phaseId, settleOnStarted = true) { runtime ->
      runtime.workControlService.startStandalone(
        Path.of(request.repoRoot),
        StandalonePhaseRequest(phaseId, request.target),
        request.agentId,
        request.standaloneCheckout(),
        if (request.kind == NewTaskKind.Qa) qaDevice else null,
      )
    }
  }

  /** The default agent setting, or null when it is unset or unreadable so the runtime's own rule applies. */
  private suspend fun readDefaultAgent(): String? = try {
    settings.settings.first().defaultAgentId
  } catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    diagnostics.record("desktop_default_agent_unavailable")
    null
  }

  private suspend fun readQaDevice(): Outcome<QaNamedDevice?, SettingsRejection> =
    when (val result = settings.readQaDevice()) {
      is Outcome.Failure -> result
      is Outcome.Success -> Outcome.Success(result.value?.toRuntimeDevice())
    }

  private fun VerifyStartChoices.toRuntimeOptions(qaDevice: QaNamedDevice?): VerifyStartOptions = VerifyStartOptions(
    qa = when (qa) {
      VerifyQaChoice.On -> VerifyQaSelection.ON
      VerifyQaChoice.Off -> VerifyQaSelection.OFF
      null -> null
    },
    mode = when (reviewMode) {
      VerifyReviewModeChoice.Inline -> VerifyReviewMode.INLINE
      VerifyReviewModeChoice.Delegated -> VerifyReviewMode.DELEGATED
      null -> null
    },
    qaDevice = qaDevice.takeIf { qa == VerifyQaChoice.On },
  )

  private fun QaDevicePick.toRuntimeDevice(): QaNamedDevice = QaNamedDevice(
    if (kind == QaDevicePickKind.Serial) QaNamedDeviceKind.SERIAL else QaNamedDeviceKind.AVD,
    value,
  )

  private fun NewTaskStart.usesQaDevice(): Boolean =
    kind == NewTaskKind.Qa || kind == NewTaskKind.GoalRun || (kind == NewTaskKind.Verify && verify.qa == VerifyQaChoice.On)

  private suspend fun startOperationTask(
    request: NewTaskStart,
    session: DatabaseSessionComponent,
    qaDevice: QaNamedDevice?,
  ): NewTaskStartOutcome {
    val operationId = when (request.kind) {
      NewTaskKind.Verify -> NewTaskActionKind.Verify.wire
      NewTaskKind.PrReviewFix -> NewTaskActionKind.PrReviewFix.wire
      else -> return NewTaskStartOutcome.Refused("Choose a standalone operation.")
    }
    return startReservedStandalone(request, session, operationId, settleOnStarted = false) { runtime ->
      runtime.workControlService.startOperation(
        Path.of(request.repoRoot),
        OperationStartRequest(
          operationId,
          request.target,
          request.agentId,
          request.profile,
          request.standaloneCheckout(),
          request.confirmation?.let { OperatorConfirmation(it.token, it.selection) },
          proposalChanges = request.proposalChanges,
          verifyOptions = request.verify.toRuntimeOptions(qaDevice),
        ),
      )
    }
  }

  override suspend fun operationCardForLaunch(launchId: String): OperationCardView? =
    readOperationCard("control_operation_card_for_launch") { it.operationCardForLaunch(launchId) }

  override suspend fun operationCard(token: String): OperationCardView? =
    readOperationCard("control_operation_card") { it.operationCard(token) }

  override suspend fun revalidateOperationCard(token: String): OperationCardLookup {
    val session = sessions.sessions.value ?: return OperationCardLookup.Unavailable(DATABASE_CHANGED)
    return callInSession(
      session,
      "control_revalidate_operation_card",
      { OperationCardLookup.Unavailable(it) },
      stale = { OperationCardLookup.Unavailable(DATABASE_CHANGED) },
    ) { runtime ->
      if (!sessionIsCurrent(session)) return@callInSession OperationCardLookup.Unavailable(DATABASE_CHANGED)
      when (val result = runtime.workControlService.revalidateOperationCard(token)) {
        OperationCardRevalidation.Missing -> OperationCardLookup.Missing
        is OperationCardRevalidation.Present -> OperationCardLookup.Present(
          result.card.toView(result.canConfirm, result.reason),
        )
      }
    }
  }

  override suspend fun dismissOperationCard(token: String): OperationCardDismissOutcome {
    val session = sessions.sessions.value ?: return OperationCardDismissOutcome.Stale(DATABASE_CHANGED_REASON)
    return callInSession(
      session,
      "control_dismiss_operation_card",
      { OperationCardDismissOutcome.Stale(DATABASE_CHANGED_REASON) },
      stale = { OperationCardDismissOutcome.Stale(DATABASE_CHANGED_REASON) },
    ) { runtime ->
      when (val dismissed = runtime.workControlService.dismissOperationCard(token)) {
        OperationCardDismissal.Dismissed -> OperationCardDismissOutcome.Dismissed
        is OperationCardDismissal.Stale -> OperationCardDismissOutcome.Stale(dismissed.reason)
      }
    }
  }

  private suspend fun readOperationCard(
    name: String,
    read: (WorkControlService) -> OperationCardProjection?,
  ): OperationCardView? {
    val session = sessions.sessions.value ?: return null
    return callInSession(session, name, { null }, stale = { null }) { runtime ->
      read(runtime.workControlService)?.toView()
    }
  }

  private suspend fun startReservedStandalone(
    request: NewTaskStart,
    session: DatabaseSessionComponent,
    id: String,
    settleOnStarted: Boolean,
    start: (RuntimeComponent) -> NewTaskStartResult,
  ): NewTaskStartOutcome {
    if (!sessionIsCurrent(session)) return NewTaskStartOutcome.Refused("The database changed. Try again.")
    val identity = callInSession(session, "control_standalone_identity", { Outcome.Failure(it) }, stale = { Outcome.Failure(NO_SESSION) }) { runtime ->
      Outcome.Success(runtime.repositoryEnclosingRootPort.repositoryIdentity(Path.of(request.repoRoot)))
    }
    val repositoryIdentity = when (identity) {
      is Outcome.Failure -> return if (!sessionIsCurrent(session)) NewTaskStartOutcome.Refused("The database changed. Try again.") else NewTaskStartOutcome.Failed(identity.error)
      is Outcome.Success -> identity.value
    }
    val snapshot = when (val result = session.board.completeSnapshot(RESERVATION_BOARD)) {
      is Outcome.Failure -> return if (!sessionIsCurrent(session)) NewTaskStartOutcome.Refused("The database changed. Try again.") else NewTaskStartOutcome.Failed(result.error)
      is Outcome.Success -> result.value
    }
    val key = request.standaloneBranch()?.let { "$repositoryIdentity:$id:standalone:branch:$it" } ?: "$repositoryIdentity:$id:standalone"
    val admission = sessions.admitReservation(session, key, LaunchBoardTarget.StandalonePhase(id, repositoryIdentity), null, snapshot)
    val handle = when (admission) {
      ReservationAdmission.AlreadyReserved -> return NewTaskStartOutcome.Refused("$id is already starting in this repository.")
      ReservationAdmission.ChangedSession -> return NewTaskStartOutcome.Refused("The database changed. Try again.")
      is ReservationAdmission.Reserved -> admission.handle
    }
    val outcome = try {
      callInSession(
        session,
        "control_start_standalone",
        NewTaskStartOutcome::Failed,
        stale = { NewTaskStartOutcome.Refused("The database changed. Try again.") },
      ) { runtime ->
        if (!sessionIsCurrent(session)) return@callInSession NewTaskStartOutcome.Refused("The database changed. Try again.")
        val observation = session.purgeIdentity.observation
          ?: return@callInSession NewTaskStartOutcome.Refused("The database changed. Try again.")
        when (val result = runtime.databaseSession.transactionFenced(observation) {
          if (!sessionIsCurrent(session)) null else start(runtime)
        }) {
          FencedTransactionResult.ChangedTarget -> NewTaskStartOutcome.Refused("The database changed. Try again.")
          is FencedTransactionResult.Committed -> when (val started = result.value) {
            null -> NewTaskStartOutcome.Refused("The database changed. Try again.")
            is NewTaskStartResult.Refused -> NewTaskStartOutcome.Refused(started.reason)
            is NewTaskStartResult.Running -> {
              launches.releaseOnExit(handle, started.failure)
              NewTaskStartOutcome.Started(id, request.kind, { started.failure.await() }, started.launchId)
            }
            is NewTaskStartResult.AwaitingConfirmation ->
              NewTaskStartOutcome.AwaitingConfirmation(id, request.kind, started.launchId)
            else -> NewTaskStartOutcome.Started(id, request.kind)
          }
        }
      }
    } catch (failure: Throwable) {
      launches.release(handle)
      throw failure
    }
    if (settleOnStarted && outcome is NewTaskStartOutcome.Started) {
      if (request.kind == NewTaskKind.Monitor) launches.release(handle) else launches.settle(handle, true)
    } else {
      launches.release(handle)
    }
    return outcome
  }

  override suspend fun recoveryPreview(item: WorkItem, confirmedPrerequisites: Set<IncidentPrerequisite>): RecoveryPreviewOutcome {
    val session = currentSession(item)
      ?: return RecoveryPreviewOutcome.Refused(ControlRejection.CHANGED_TARGET.name)
    val target = item.runTarget() ?: return RecoveryPreviewOutcome.Refused(ControlRejection.UNKNOWN_TARGET.name)
    if (!sessionIsCurrent(session, item)) return RecoveryPreviewOutcome.Refused(ControlRejection.CHANGED_TARGET.name)
    return callInSession(session, "recovery_preview", RecoveryPreviewOutcome::Failed, stale = {
      RecoveryPreviewOutcome.Refused(ControlRejection.CHANGED_TARGET.name)
    }) { runtime ->
      if (!sessionIsCurrent(session, item)) return@callInSession RecoveryPreviewOutcome.Refused(ControlRejection.CHANGED_TARGET.name)
      val incident = when (val fenced = runtime.databaseSession.transactionFenced(target.databaseFileObservation) {
        if (!sessionIsCurrent(session, item)) null else runtime.ideStatusService.detail(WorkDetailRequest(item.id)).item?.incident
      }) {
        FencedTransactionResult.ChangedTarget ->
          return@callInSession RecoveryPreviewOutcome.Refused(ControlRejection.CHANGED_TARGET.name)
        is FencedTransactionResult.Committed -> fenced.value
          ?: return@callInSession if (sessionIsCurrent(session, item)) RecoveryPreviewOutcome.Unavailable
          else RecoveryPreviewOutcome.Refused(ControlRejection.CHANGED_TARGET.name)
      }
      val kinds = confirmedPrerequisites.mapTo(mutableSetOf()) { it.toRuntime() }
      when (val result = runtime.workControlService.recoveryPreview(target, incident.incidentToken, kinds)) {
        is WorkRecoveryPreviewResult.Available -> RecoveryPreviewOutcome.Ready(result.preview.toDomain(confirmedPrerequisites))
        is WorkRecoveryPreviewResult.NeedsPrerequisites -> RecoveryPreviewOutcome.NeedsPrerequisites(
          result.instructions.map { PrerequisiteInstruction(it.kind.toDomain(), it.instruction) },
          result.plannedPreview?.toDomain(confirmedPrerequisites),
        )
        is WorkRecoveryPreviewResult.ExistingAttempt -> RecoveryPreviewOutcome.ExistingAttempt(result.attemptId, result.status.toDomain())
        is WorkRecoveryPreviewResult.Rejected -> RecoveryPreviewOutcome.Refused(result.detail ?: result.reason)
      }
    }
  }

  override suspend fun recover(item: WorkItem, preview: RecoveryPreview): RecoveryOutcome {
    val session = currentSession(item)
      ?: return RecoveryOutcome.Refused(ControlRejection.CHANGED_TARGET, ControlRejection.CHANGED_TARGET.name)
    val target = item.runTarget() ?: return RecoveryOutcome.Refused(ControlRejection.UNKNOWN_TARGET, ControlRejection.UNKNOWN_TARGET.name)
    if (!sessionIsCurrent(session, item)) {
      return RecoveryOutcome.Refused(ControlRejection.CHANGED_TARGET, ControlRejection.CHANGED_TARGET.name)
    }
    return callInSession(session, "recover", RecoveryOutcome::Failed, stale = {
      RecoveryOutcome.Refused(ControlRejection.CHANGED_TARGET, ControlRejection.CHANGED_TARGET.name)
    }) { runtime ->
      if (!sessionIsCurrent(session, item)) {
        return@callInSession RecoveryOutcome.Refused(ControlRejection.CHANGED_TARGET, ControlRejection.CHANGED_TARGET.name)
      }
      val kinds = preview.confirmedPrerequisites.mapTo(mutableSetOf()) { it.toRuntime() }
      runtime.workControlService.recover(target, preview.incidentToken, preview.incidentRevision, kinds).toRecoveryOutcome(diagnostics)
    }
  }

  override suspend fun purge(issueKey: String, repoRoot: String?): PurgeOutcome {
    if (issueKey.isBlank()) return PurgeOutcome.Refused("issue key required")
    val session = sessions.sessions.value ?: return PurgeOutcome.Failed(NO_SESSION)
    if (!sessionIsCurrent(session)) return PurgeOutcome.Refused(ControlRejection.CHANGED_TARGET.name)
    return callInSession(session, "purge", PurgeOutcome::Failed, stale = {
      PurgeOutcome.Refused(ControlRejection.CHANGED_TARGET.name)
    }) { runtime ->
      if (!sessionIsCurrent(session)) return@callInSession PurgeOutcome.Refused(ControlRejection.CHANGED_TARGET.name)
      val observation = session.purgeIdentity.observation
        ?: return@callInSession PurgeOutcome.Refused("The selected database identity is unavailable. Reopen the database before purging.")
      val result = runtime.goalRunnerStatusService.purge(
        GoalRunnerPurgeRequest(issueKey, repoRoot?.let(Path::of)),
        observation,
      )
      when {
        result.refusalReason != null && (result.removedPaths.isNotEmpty() || result.removedRowCounts.values.any { it > 0 } ||
          result.specBundleActions.isNotEmpty() || result.checkpointRefsPruned > 0) ->
          PurgeOutcome.Incomplete(result.leftovers + checkNotNull(result.refusalReason))
        result.refusalReason != null -> PurgeOutcome.Refused(checkNotNull(result.refusalReason))
        result.leftovers.isNotEmpty() -> PurgeOutcome.Incomplete(result.leftovers)
        result.nothingToRemove -> PurgeOutcome.NothingToRemove
        else -> PurgeOutcome.Purged(result.removedRowCounts.values.sum(), result.removedPaths.size, result.specBundleActions.size)
      }
    }
  }

  override suspend fun preflightExport(issueKey: String, repoRoot: String?): ExportPreflightOutcome {
    if (issueKey.isBlank()) return ExportPreflightOutcome.Refused("issue key required")
    val session = sessions.sessions.value ?: return ExportPreflightOutcome.Failed(NO_SESSION)
    if (!sessionIsCurrent(session)) return ExportPreflightOutcome.Refused(ControlRejection.CHANGED_TARGET.name)
    return callInSession(session, "export_preflight", ExportPreflightOutcome::Failed, stale = {
      ExportPreflightOutcome.Refused(ControlRejection.CHANGED_TARGET.name)
    }) { runtime ->
      if (!sessionIsCurrent(session)) {
        return@callInSession ExportPreflightOutcome.Refused(ControlRejection.CHANGED_TARGET.name)
      }
      val observation = session.purgeIdentity.observation
        ?: return@callInSession ExportPreflightOutcome.Refused(
          "The selected database identity is unavailable. Reopen the database before exporting.",
        )
      when (
        val result = runtime.goalRunnerStatusService.preflightExport(
          issueKey,
          repoRoot?.let(Path::of),
          observation,
        )
      ) {
        is GoalRunnerExportPreflightResult.Ready -> ExportPreflightOutcome.Ready(result.featureBranch, result.commitSha)
        is GoalRunnerExportPreflightResult.Refused -> ExportPreflightOutcome.Refused(result.reason, result.category.toDesktopCategory())
        is GoalRunnerExportPreflightResult.Failed -> ExportPreflightOutcome.Failed(ReadProblem.Runtime(result.message))
      }
    }
  }

  override suspend fun export(issueKey: String, repoRoot: String?, outputPath: String): ExportOutcome {
    if (issueKey.isBlank()) return ExportOutcome.Refused("issue key required")
    if (outputPath.isBlank()) return ExportOutcome.Refused("output path required")
    val session = sessions.sessions.value ?: return ExportOutcome.Failed(NO_SESSION)
    if (!sessionIsCurrent(session)) return ExportOutcome.Refused(ControlRejection.CHANGED_TARGET.name)
    return callInSession(session, "export", ExportOutcome::Failed, stale = {
      ExportOutcome.Refused(ControlRejection.CHANGED_TARGET.name)
    }) { runtime ->
      if (!sessionIsCurrent(session)) {
        return@callInSession ExportOutcome.Refused(ControlRejection.CHANGED_TARGET.name)
      }
      val observation = session.purgeIdentity.observation
        ?: return@callInSession ExportOutcome.Refused(
          "The selected database identity is unavailable. Reopen the database before exporting.",
        )
      val request = GoalRunnerExportRequest(issueKey, repoRoot?.let(Path::of), Path.of(outputPath))
      when (val result = runtime.goalRunnerStatusService.export(request, observation)) {
        is GoalRunnerExportResult.Exported ->
          ExportOutcome.Exported(
            result.issueKey,
            result.featureBranch,
            result.commitSha,
            result.archivePath.toString(),
          )
        is GoalRunnerExportResult.Refused -> ExportOutcome.Refused(result.reason, result.category.toDesktopCategory())
        is GoalRunnerExportResult.Failed -> ExportOutcome.Failed(ReadProblem.Runtime(result.message))
      }
    }
  }

  private fun GoalRunnerExportRefusalCategory.toDesktopCategory(): ExportRefusalCategory =
    when (this) {
      GoalRunnerExportRefusalCategory.LIVENESS -> ExportRefusalCategory.LIVENESS
      GoalRunnerExportRefusalCategory.BRANCH -> ExportRefusalCategory.BRANCH
      GoalRunnerExportRefusalCategory.OTHER -> ExportRefusalCategory.OTHER
    }

  override suspend fun inspectArchive(archivePath: String, repoRoot: String?): ArchiveInspectOutcome {
    if (archivePath.isBlank()) return ArchiveInspectOutcome.Refused("archive path required")
    val session = sessions.sessions.value ?: return ArchiveInspectOutcome.Failed(NO_SESSION)
    if (!sessionIsCurrent(session)) return ArchiveInspectOutcome.Refused(ControlRejection.CHANGED_TARGET.name)
    return callInSession(session, "inspect_archive", ArchiveInspectOutcome::Failed, stale = {
      ArchiveInspectOutcome.Refused(ControlRejection.CHANGED_TARGET.name)
    }) { runtime ->
      if (!sessionIsCurrent(session)) {
        return@callInSession ArchiveInspectOutcome.Refused(ControlRejection.CHANGED_TARGET.name)
      }
      when (val result = runtime.goalRunnerStatusService.inspectArchive(Path.of(archivePath))) {
        is GoalRunnerArchiveInspectResult.Ready ->
          ArchiveInspectOutcome.Ready(
            result.issueKey,
            result.featureBranch,
            result.commitSha,
            result.fingerprint,
          )
        is GoalRunnerArchiveInspectResult.Refused -> ArchiveInspectOutcome.Refused(result.reason)
        is GoalRunnerArchiveInspectResult.Failed -> ArchiveInspectOutcome.Failed(ReadProblem.Runtime(result.message))
      }
    }
  }

  override suspend fun import(archivePath: String, repoRoot: String?, fingerprint: String): ImportOutcome {
    if (archivePath.isBlank()) return ImportOutcome.Refused("archive path required")
    val session = sessions.sessions.value ?: return ImportOutcome.Failed(NO_SESSION)
    if (!sessionIsCurrent(session)) return ImportOutcome.Refused(ControlRejection.CHANGED_TARGET.name)
    return callInSession(session, "import_archive", ImportOutcome::Failed, stale = {
      ImportOutcome.Refused(ControlRejection.CHANGED_TARGET.name)
    }) { runtime ->
      if (!sessionIsCurrent(session)) return@callInSession ImportOutcome.Refused(ControlRejection.CHANGED_TARGET.name)
      val observation = session.purgeIdentity.observation
        ?: return@callInSession ImportOutcome.Refused(
          "The selected database identity is unavailable. Reopen the database before importing.",
        )
      val request = GoalRunnerImportRequest(Path.of(archivePath), repoRoot?.let(Path::of), fingerprint)
      when (val result = runtime.goalRunnerStatusService.import(request, observation)) {
        is GoalRunnerImportResult.Imported ->
          ImportOutcome.Imported(result.issueKey, result.featureBranch, result.commitSha)
        is GoalRunnerImportResult.Incomplete ->
          ImportOutcome.Incomplete(result.missingSpecPaths)
        is GoalRunnerImportResult.Refused -> ImportOutcome.Refused(result.reason)
        is GoalRunnerImportResult.Failed -> ImportOutcome.Failed(ReadProblem.Runtime(result.message))
      }
    }
  }

  private suspend fun goalControl(item: WorkItem, action: WorkControlAction, kind: ControlKind): ControlOutcome {
    val session = currentSession(item) ?: return ControlOutcome.Refused(ControlRejection.CHANGED_TARGET, null)
    val target = item.controlTarget() ?: return ControlOutcome.Refused(ControlRejection.UNKNOWN_TARGET, null)
    if (!sessionIsCurrent(session, item)) return ControlOutcome.Refused(ControlRejection.CHANGED_TARGET, null)
    return callInSession(session, "control_${kind.name.lowercase()}", ControlOutcome::Failed) { runtime ->
      if (!sessionIsCurrent(session, item)) return@callInSession ControlOutcome.Refused(ControlRejection.CHANGED_TARGET, null)
      if (target.databaseFileObservation == null || runtime.databaseSession.read { it.databaseFileObservation } != target.databaseFileObservation) return@callInSession ControlOutcome.Refused(ControlRejection.CHANGED_TARGET, null)
      val storeIsCurrent = {
        sessionIsCurrent(session, item) &&
          target.databaseFileObservation == runtime.databaseSession.read { it.databaseFileObservation }
      }
      runtime.workControlService.request(target, action, storeIsCurrent).toControlOutcome(kind, diagnostics)
    }
  }

  private suspend fun runControl(item: WorkItem, name: String, block: (RuntimeComponent, WorkRunTarget) -> ControlOutcome): ControlOutcome {
    val session = currentSession(item) ?: return ControlOutcome.Refused(ControlRejection.CHANGED_TARGET, null)
    val target = item.runTarget() ?: return ControlOutcome.Refused(ControlRejection.UNKNOWN_TARGET, null)
    if (!sessionIsCurrent(session, item)) return ControlOutcome.Refused(ControlRejection.CHANGED_TARGET, null)
    return callInSession(session, name, ControlOutcome::Failed) { runtime ->
      if (!sessionIsCurrent(session, item)) return@callInSession ControlOutcome.Refused(ControlRejection.CHANGED_TARGET, null)
      if (runtime.databaseSession.read { it.databaseFileObservation } != target.databaseFileObservation) return@callInSession ControlOutcome.Refused(ControlRejection.CHANGED_TARGET, null)
      block(runtime, target)
    }
  }

  private fun currentSession(item: WorkItem): DatabaseSessionComponent? =
    sessions.sessions.value?.takeIf { sessionIsCurrent(it, item) }

  private fun sessionIsCurrent(session: DatabaseSessionComponent, item: WorkItem): Boolean =
    item.control.sessionId == session.selection.selection.id && sessionIsCurrent(session)

  private fun sessionIsCurrent(session: DatabaseSessionComponent): Boolean = sessions.isCurrent(session)

  /** Holds [handle] until failure, a board change, or a fresh check finds that the launch did not start. */
  private suspend fun reserved(handle: LaunchReservationHandle, request: suspend () -> ControlOutcome): ControlOutcome {
    val outcome = try {
      request()
    } catch (failure: Throwable) {
      launches.release(handle)
      throw failure
    }
    val succeeded = outcome !is ControlOutcome.Refused && outcome !is ControlOutcome.Failed
    launches.settle(handle, succeeded)
    if (succeeded) appScope.scope.launch {
      val unconfirmed = launches.awaitStart(handle) {
        val session = sessions.currentComponent(handle.sessionId) ?: return@awaitStart null
        when (val result = session.board.completeSnapshot(RESERVATION_BOARD)) {
          is Outcome.Success -> result.value.takeIf { sessions.isCurrent(session) }?.also { session.board.refresh() }
          is Outcome.Failure -> {
            diagnostics.record("desktop_launch_confirmation_read_failed")
            null
          }
        }
      }
      if (unconfirmed) diagnostics.record("desktop_launch_not_started")
    }
    return outcome
  }

  /** Runs [block] as a write on the open session; a runtime failure becomes [failed]'s outcome. Cancellation is rethrown. */
  private suspend fun <T> call(name: String, failed: (ReadProblem) -> T, block: (RuntimeComponent) -> T): T {
    val session = sessions.sessions.value ?: return failed(NO_SESSION)
    return when (val result = readOrProblem(session.runtime, diagnostics) { session.reads.write(name, block) }) {
      is Outcome.Success -> result.value
      is Outcome.Failure -> failed(result.error)
    }
  }

  private suspend fun <T> callInSession(
    session: DatabaseSessionComponent,
    name: String,
    failed: (ReadProblem) -> T,
    stale: (() -> T)? = null,
    block: (RuntimeComponent) -> T,
  ): T {
    var closed = false
    return when (val result = readOrProblem(session.runtime, diagnostics) {
      try {
        session.reads.write(name, block)
      } catch (failure: SessionClosedException) {
        closed = true
        throw failure
      }
    }) {
      is Outcome.Success -> result.value
      is Outcome.Failure -> if (closed) stale?.invoke() ?: failed(result.error) else failed(result.error)
    }
  }
}

private fun DatabaseFileIdentity.toObservation() = DatabaseFileObservation(Path.of(path), fileKey)

private fun NewTaskStart.standaloneBranch(): String? =
  branch?.trim()?.takeIf { it.isNotBlank() && kind != NewTaskKind.Monitor }

private fun NewTaskStart.standaloneCheckout(): StandaloneCheckout =
  StandaloneCheckout(checkoutRoot?.trim()?.takeIf(String::isNotBlank)?.let(Path::of), standaloneBranch())

/** Null when the item is not a goal or was read without its database file; the runtime cannot address it. */
internal fun WorkItem.controlTarget(): WorkControlTarget? {
  val control = control
  val workflowId = control.workflowId ?: return null
  val issueKey = issueKey ?: return null
  val file = control.databaseFile ?: return null
  return WorkControlTarget(
    workflowId = workflowId,
    repositoryIdentity = control.repositoryIdentity,
    repositoryRoot = Path.of(repositoryRoot),
    issueKey = issueKey,
    executionId = control.executionId,
    statusRevision = control.statusRevision,
    statusStoreId = control.statusStoreId,
    runSequence = control.runSequence,
    updatedAt = control.updatedAt.toJavaInstant(),
    leaseOwnerToken = control.controlLeaseOwner,
    leaseGeneration = control.controlLeaseGeneration,
    observation = control.storeId?.let { id -> control.storeRevision?.let { WorkStoreObservation(id, it) } },
    databaseFileObservation = file.toObservation(),
  )
}

internal fun WorkItem.runTarget(): WorkRunTarget? {
  val file = control.databaseFile ?: return null
  return WorkRunTarget(
    id = id,
    repositoryIdentity = control.repositoryIdentity,
    repositoryRoot = Path.of(repositoryRoot),
    executionId = control.executionId,
    statusRevision = control.statusRevision,
    updatedAt = control.updatedAt.toJavaInstant(),
    databaseFileObservation = file.toObservation(),
  )
}

private fun WorkControlResult.toRecoveryOutcome(diagnostics: Diagnostics): RecoveryOutcome = when (this) {
  is WorkControlResult.Rejected ->
    if (reason == WorkControlRejection.STALE_INCIDENT) {
      RecoveryOutcome.PreviewStale
    } else {
      RecoveryOutcome.Refused(reason.toDomain(), detail ?: reason.name)
    }
  is WorkControlResult.Recovered ->
    if (status == BlockIncidentRecoveryAttemptStatus.FAILED) {
      RecoveryOutcome.AttemptFailed(attemptId, failure?.wireValue ?: status.wireValue)
    } else {
      RecoveryOutcome.Applied(attemptId, status.toDomain(), launch?.toControlOutcome(ControlKind.LAUNCH, diagnostics))
    }
  is WorkControlResult.LaunchFailed -> RecoveryOutcome.Failed(ReadProblem.Runtime(reason))
  WorkControlResult.Accepted, WorkControlResult.IntentRecorded, WorkControlResult.ExitConfirmed, WorkControlResult.TerminationAttempted -> {
    diagnostics.record("desktop_recovery_unexpected_run_control")
    RecoveryOutcome.Failed(ReadProblem.Runtime("The runtime returned a run control result for a recovery."))
  }
}

private fun BlockIncidentRecoveryPreview.toDomain(confirmed: Set<IncidentPrerequisite>) =
  RecoveryPreview(action, label, effects, targetWorkflowId, phaseId, incidentToken, incidentRevision, confirmed)

private fun IncidentPrerequisite.toRuntime(): BlockIncidentPrerequisiteKind = when (this) {
  IncidentPrerequisite.CREDENTIALS_CONFIGURED -> BlockIncidentPrerequisiteKind.CREDENTIALS_CONFIGURED
  IncidentPrerequisite.CONFIGURATION_FIXED -> BlockIncidentPrerequisiteKind.CONFIGURATION_FIXED
  IncidentPrerequisite.PROJECT_EDIT_COMPLETED -> BlockIncidentPrerequisiteKind.PROJECT_EDIT_COMPLETED
  IncidentPrerequisite.EXTERNAL_SERVICE_AVAILABLE -> BlockIncidentPrerequisiteKind.EXTERNAL_SERVICE_AVAILABLE
}

private fun BlockIncidentPrerequisiteKind.toDomain(): IncidentPrerequisite = when (this) {
  BlockIncidentPrerequisiteKind.CREDENTIALS_CONFIGURED -> IncidentPrerequisite.CREDENTIALS_CONFIGURED
  BlockIncidentPrerequisiteKind.CONFIGURATION_FIXED -> IncidentPrerequisite.CONFIGURATION_FIXED
  BlockIncidentPrerequisiteKind.PROJECT_EDIT_COMPLETED -> IncidentPrerequisite.PROJECT_EDIT_COMPLETED
  BlockIncidentPrerequisiteKind.EXTERNAL_SERVICE_AVAILABLE -> IncidentPrerequisite.EXTERNAL_SERVICE_AVAILABLE
}

private fun BlockIncidentRecoveryAttemptStatus.toDomain(): RecoveryAttemptStatus = when (this) {
  BlockIncidentRecoveryAttemptStatus.REQUESTED -> RecoveryAttemptStatus.REQUESTED
  BlockIncidentRecoveryAttemptStatus.APPLYING -> RecoveryAttemptStatus.APPLYING
  BlockIncidentRecoveryAttemptStatus.APPLIED -> RecoveryAttemptStatus.APPLIED
  BlockIncidentRecoveryAttemptStatus.RESUMING -> RecoveryAttemptStatus.RESUMING
  BlockIncidentRecoveryAttemptStatus.RESUMED -> RecoveryAttemptStatus.RESUMED
  BlockIncidentRecoveryAttemptStatus.FAILED -> RecoveryAttemptStatus.FAILED
}
