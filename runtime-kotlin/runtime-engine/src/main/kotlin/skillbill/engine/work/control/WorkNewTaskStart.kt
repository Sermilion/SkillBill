package skillbill.engine.work.control

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.issuekey.GOAL_INTAKE_URL_MARKER
import skillbill.contracts.issuekey.TRACKER_STYLE_ISSUE_KEY_PATTERN
import skillbill.contracts.operationcard.OperationCardExitCodes
import skillbill.contracts.operationcard.OperationCardOperationIds
import skillbill.contracts.operationcard.OperationCardProjection
import skillbill.contracts.operationcard.OperationCardStatus
import skillbill.contracts.operationcard.OperationLaunchContext
import skillbill.contracts.operationcard.OperationLaunchRetention
import skillbill.contracts.qa.QaDeviceLaunchTokens.QA_DEVICE_FLAG
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.execution.QaNamedDevice
import skillbill.engine.featuretask.model.execution.launchArguments
import skillbill.engine.goal.GoalNewWorkAdmission
import skillbill.engine.goal.GoalNewWorkAdmissionPort
import skillbill.engine.goal.GoalPreflightPort
import skillbill.engine.goal.GoalPreflightRequest
import skillbill.engine.goal.GoalPreflightResult
import skillbill.engine.goal.GoalPreflightVerdict
import skillbill.engine.goal.goalRepositoryIdentity
import skillbill.engine.operation.core.OperationCardRevalidation
import skillbill.engine.work.control.model.NewTaskIdentityPolicy
import skillbill.engine.work.control.model.NewTaskIntakeRequest
import skillbill.engine.work.control.model.NewTaskPreparation
import skillbill.engine.work.control.model.NewTaskRunKind
import skillbill.engine.work.control.model.NewTaskStartResult
import skillbill.engine.work.control.model.OperationCardDismissal
import skillbill.engine.work.control.model.OperationStartRequest
import skillbill.engine.work.control.model.OperatorConfirmation
import skillbill.engine.work.control.model.StandaloneCheckout
import skillbill.engine.work.control.model.StandalonePhaseRequest
import skillbill.install.model.SupportedAgent
import skillbill.model.EnvironmentContext
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.work.WorkRunLauncher
import skillbill.ports.work.model.WorkRunExit
import skillbill.ports.work.model.WorkRunLaunchKind
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import skillbill.ports.workflow.branchworktree.BranchWorktreeResources
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeCreation
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolder
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolderKind
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeObservation
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeRelease
import skillbill.ports.workflow.goalworktree.GoalWorktreeResources
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.workflowStatus
import java.nio.file.Path
import java.util.concurrent.CompletionStage

@Inject
public class WorkNewTaskStart(
  private val database: DatabaseSessionFactory,
  private val admission: GoalNewWorkAdmissionPort,
  private val allocator: NewTaskIssueKeyAllocator,
  private val preflight: GoalPreflightPort,
  private val launcher: WorkRunLauncher,
  private val environment: EnvironmentContext,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val diagnostics: RuntimeDiagnostics,
  private val goalWorktrees: GoalWorktreeResources,
  private val branchWorktrees: BranchWorktreeResources,
  private val launches: OperationCardLaunches,
) {
  public fun prepareNewTask(
    repoRoot: Path,
    request: NewTaskIntakeRequest,
  ): NewTaskPreparation =
    if (request.identityPolicy == NewTaskIdentityPolicy.RESOLVED_DESKTOP_IDENTITY) {
      prepareResolvedDesktopIdentity(repoRoot, request)
    } else {
      prepareLegacyIdentity(repoRoot, request)
    }

  private fun prepareLegacyIdentity(
    repoRoot: Path,
    request: NewTaskIntakeRequest,
  ): NewTaskPreparation {
    val requirements = request.requirements
    val agentTitle = usableTitle(request.title)
    val parsingText = requirements.trim()
    when (val first = admission.admit(requirements, repoRoot)) {
      is GoalNewWorkAdmission.Admitted -> {
        val intake =
          if (requirements.any(Char::isWhitespace)) {
            appendBase(keyedIntake(first.issueKey, requirements, agentTitle), request.baseBranch)
          } else {
            requirements
          }
        val firstToken = requirements.trim().split(WHITESPACE).firstOrNull().orEmpty()
        val origin =
          if (firstToken.contains(GOAL_INTAKE_URL_MARKER)) {
            IdentityOrigin.URL
          } else {
            IdentityOrigin.TYPED
          }
        return readyOrRefused(repoRoot, request, first.issueKey, intake, origin)
      }
      is GoalNewWorkAdmission.Refused -> return NewTaskPreparation.Refused(first.reason)
      GoalNewWorkAdmission.NoIssueKey -> Unit
    }

    val choice = request.keyChoice?.trim().orEmpty()
    val selected =
      when {
        choice.contains(GOAL_INTAKE_URL_MARKER) ->
          extractUrlKey(choice)?.let { it to IdentityOrigin.URL }
        ISSUE_KEY.matches(choice.uppercase()) -> choice.uppercase() to IdentityOrigin.TYPED
        else -> null
      } ?: return NewTaskPreparation.NeedsKey(allocator.prefixes(repoRoot))
    val titleLine = parsingText.lineSequence().firstOrNull(String::isNotBlank).orEmpty()
    val title = agentTitle ?: titleLine.takeIf(ASCII_WORD::containsMatchIn) ?: "New task from desktop"
    val intake = appendBase("${selected.first}\n$title\n\n$requirements", request.baseBranch)
    return readyOrRefused(repoRoot, request, selected.first, intake, selected.second)
  }

  private fun prepareResolvedDesktopIdentity(
    repoRoot: Path,
    request: NewTaskIntakeRequest,
  ): NewTaskPreparation {
    val choice = request.keyChoice?.trim().orEmpty()
    val selected =
      when {
        choice.contains(GOAL_INTAKE_URL_MARKER) -> extractDesktopUrlKey(choice)?.let { it to IdentityOrigin.URL }
        ISSUE_KEY.matches(choice) -> choice.uppercase() to IdentityOrigin.TYPED
        else -> null
      } ?: return NewTaskPreparation.NeedsKey(allocator.prefixes(repoRoot))
    val requirements = request.requirements
    val agentTitle = usableTitle(request.title)
    val titleLine = requirements.trim().lineSequence().firstOrNull(String::isNotBlank).orEmpty()
    val title = agentTitle ?: titleLine.takeIf(ASCII_WORD::containsMatchIn) ?: "New task from desktop"
    val intake = appendBase("${selected.first}\n$title\n\n$requirements", request.baseBranch)
    return readyOrRefused(repoRoot, request, selected.first, intake, selected.second)
  }

  private fun extractUrlKey(value: String): String? =
    value.substringAfter(GOAL_INTAKE_URL_MARKER)
      .substringBefore('?')
      .substringBefore('#')
      .split('/')
      .firstOrNull { ISSUE_KEY.matches(it) }
      ?.uppercase()

  private fun extractDesktopUrlKey(value: String): String? =
    value.substringAfter(GOAL_INTAKE_URL_MARKER)
      .substringBefore('?')
      .substringBefore('#')
      .split('/')
      .drop(1)
      .firstOrNull { ISSUE_KEY.matches(it) }
      ?.uppercase()

  public fun startNewTask(
    repoRoot: Path,
    prepared: NewTaskPreparation.Ready,
    kind: NewTaskRunKind,
    agentId: String,
    qaDevice: QaNamedDevice? = null,
  ): NewTaskStartResult {
    if (agentId !in SupportedAgent.supportedIds) {
      return NewTaskStartResult.Refused("Choose a supported agent before starting this run.")
    }
    if (prepared.allocated && allocator.holds(repoRoot, prepared.key)) return NewTaskStartResult.KeyTaken
    val checked =
      preflight.preflight(
        GoalPreflightRequest(
          prepared.key,
          repoRoot,
          invokedAgentId = agentId,
          userHome = environment.userHome,
          environment = environment.environment,
        ),
      )
    val refusal = startRefusal(checked, prepared)
    if (refusal != null) {
      val occupied = checked.verdict in OCCUPIED_VERDICTS
      return if (prepared.allocated && occupied) NewTaskStartResult.KeyTaken else NewTaskStartResult.Refused(refusal)
    }
    val key = checked.issueKey
    val identity = goalRepositoryIdentity(repoRoot, repositoryEnclosingRootPort)
    val ownership: String
    val args: List<String>
    when (kind) {
      NewTaskRunKind.GOAL -> {
        ownership = "$identity:$key:goal"
        args =
          listOf(
            "goal",
            prepared.intake,
            "--agent",
            agentId,
            "--repo-root",
            repoRoot.toString(),
            "--no-live-output",
          )
      }
      NewTaskRunKind.PLAN -> {
        ownership = "$identity:$key:plan"
        args = listOf("phase", "plan", prepared.intake, "--agent", agentId)
      }
    }
    val deviceArguments = if (kind == NewTaskRunKind.GOAL) qaDevice.launchArguments() else emptyList()
    return launchRuntimeCommand(repoRoot, ownership, args + deviceArguments)
  }

  private fun startRefusal(
    checked: GoalPreflightResult,
    prepared: NewTaskPreparation.Ready,
  ): String? {
    val latestStatus = checked.candidates.lastOrNull()?.status.workflowStatus()
    val reusable =
      latestStatus == WorkflowStatus.ABANDONED ||
        (latestStatus == WorkflowStatus.COMPLETED && prepared.reuseRequested)
    return if (checked.verdict == GoalPreflightVerdict.TERMINAL_ONLY && reusable) null else goalStartRefusal(checked)
  }

  public fun startStandalone(
    repoRoot: Path,
    request: StandalonePhaseRequest,
    agentId: String,
    checkout: StandaloneCheckout = StandaloneCheckout(),
    qaDevice: QaNamedDevice? = null,
  ): NewTaskStartResult {
    val phaseId = request.phaseId
    val target = request.target
    if (phaseId !in STANDALONE_PHASES) return NewTaskStartResult.Refused("Unsupported standalone phase '$phaseId'.")
    if (qaDevice != null && phaseId != QA_PHASE) {
      return NewTaskStartResult.Refused("$QA_DEVICE_FLAG applies only to the $QA_PHASE phase.")
    }
    standaloneIntakeRefusal(target, phaseId)?.let { return it }
    val place = if (phaseId == MONITOR_PHASE) checkout.copy(branch = null) else checkout
    val run =
      StandaloneRun(
        "phase",
        phaseId,
        BranchWorktreeHolder(BranchWorktreeHolderKind.PHASE, phaseId),
        qaDevice = qaDevice.takeIf { phaseId == QA_PHASE },
      )
    return launchStandaloneCommand(repoRoot, run, target, agentId, place)
  }

  public fun startOperation(
    repoRoot: Path,
    request: OperationStartRequest,
  ): NewTaskStartResult {
    val operationId = request.operationId
    val confirmation = request.confirmation
    return when {
      operationId !in STANDALONE_OPERATIONS ->
        NewTaskStartResult.Refused("Unsupported standalone operation '$operationId'.")
      confirmation != null && request.proposalChanges != null ->
        NewTaskStartResult.Refused("An operation confirmation cannot change its proposal.")
      request.proposalChanges.orEmpty().toByteArray(Charsets.UTF_8).size > MAX_INTAKE_BYTES ->
        NewTaskStartResult.Refused("Proposal changes are too long. Shorten them to under $MAX_INTAKE_BYTES bytes.")
      confirmation != null -> confirmOperation(repoRoot, operationId, confirmation)
      else -> proposalTargetRefusal(operationId, request.target) ?: startProposal(repoRoot, request)
    }
  }

  private fun proposalTargetRefusal(
    operationId: String,
    target: String?,
  ): NewTaskStartResult.Refused? =
    when {
      target.orEmpty().split(WHITESPACE).any { it.startsWith(CONFIRM_PREFIX) || it.startsWith(SELECT_PREFIX) } ->
        NewTaskStartResult.Refused("An operation target cannot carry a confirm: or select: token.")
      operationId == OperationCardOperationIds.VERIFY && target.isNullOrBlank() ->
        NewTaskStartResult.Refused("Verify needs an intake before it can start.")
      else -> null
    }

  private fun startProposal(
    repoRoot: Path,
    request: OperationStartRequest,
  ): NewTaskStartResult {
    val operationId = request.operationId
    val profile =
      when (
        val resolved =
          OperationAgentHomes.effectiveProfile(
            request.agentId,
            request.profile,
            launches.agentHomes(),
            environment.userHome,
          )
      ) {
        is OperationProfileResolution.Refused -> return NewTaskStartResult.Refused(resolved.reason)
        is OperationProfileResolution.Resolved -> resolved.profile
      }
    val launchId = launches.newLaunchId().takeIf { launches.tracks(operationId) }
    val holderId = launchId?.let { launches.holderId(operationId, it) } ?: operationId
    val run =
      StandaloneRun(
        "operation",
        operationId,
        BranchWorktreeHolder(BranchWorktreeHolderKind.OPERATION, holderId),
        launchId,
        profile,
        proposalChanges = request.proposalChanges,
      )
    return launchStandaloneCommand(repoRoot, run, request.target, request.agentId, request.checkout)
  }

  public fun dismissOperationCard(token: String): OperationCardDismissal {
    val (dismissed, launch) = launches.dismiss(token)
    if (!dismissed) {
      launches.find(token)?.takeIf { it.status == OperationCardStatus.CANCELLED }?.let(launches::withdraw)
      return OperationCardDismissal.Stale("Operation card '$token' is no longer awaiting confirmation.")
    }
    launches.find(token)?.let(launches::withdraw)
    launch?.let { releaseAfterRefusal(Path.of(it.checkoutRoot), operationHolder(it)) }
    return OperationCardDismissal.Dismissed
  }

  public fun operationCard(token: String): OperationCardProjection? = launches.find(token)

  public fun operationCardForLaunch(launchId: String): OperationCardProjection? = launches.findByLaunch(launchId)

  public fun revalidateOperationCard(token: String): OperationCardRevalidation = launches.revalidate(token)

  private fun operationHolder(launch: OperationLaunchContext): BranchWorktreeHolder =
    BranchWorktreeHolder(BranchWorktreeHolderKind.OPERATION, launch.holderId)

  private fun confirmOperation(
    repoRoot: Path,
    operationId: String,
    confirmation: OperatorConfirmation,
  ): NewTaskStartResult {
    val selection = confirmation.selection?.trim()?.takeIf(String::isNotEmpty)
    val refusal = confirmationRefusal(operationId, selection) ?: revalidationRefusal(confirmation.token)
    if (refusal != null) return NewTaskStartResult.Refused(refusal)
    return when (val admitted = launches.admit(confirmation.token, operationId, selection)) {
      is CardAdmission.Refused -> NewTaskStartResult.Refused(admitted.reason)
      is CardAdmission.Admitted ->
        launchConfirmation(repoRoot, ConfirmationRun(confirmation.token, admitted.executionId), selection, admitted)
    }
  }

  private fun confirmationRefusal(
    operationId: String,
    selection: String?,
  ): String? =
    when {
      !launches.tracks(operationId) -> "Operation '$operationId' has no confirmation card."
      operationId == OperationCardOperationIds.PR_REVIEW_FIX && selection == null ->
        "Select the threads to fix before confirming."
      selection != null && (selection.any(Char::isISOControl) || selection.startsWith("-")) ->
        "The selection '$selection' is not valid."
      else -> null
    }

  private fun revalidationRefusal(token: String): String? =
    when (val revalidation = launches.revalidate(token)) {
      is OperationCardRevalidation.Missing -> "The operation card is missing; start a new proposal from chat."
      is OperationCardRevalidation.Present ->
        if (revalidation.canConfirm) {
          null
        } else {
          revalidation.reason ?: "The original proposal is unavailable; start a new proposal from chat."
        }
    }

  private fun launchConfirmation(
    repoRoot: Path,
    run: ConfirmationRun,
    selection: String?,
    admitted: CardAdmission.Admitted,
  ): NewTaskStartResult {
    val launch = admitted.launch
    val operationId = admitted.card.operationId
    val checkoutRoot = Path.of(launch.checkoutRoot)
    val checkoutGone = repositoryEnclosingRootPort.optionalRealPath(checkoutRoot) == null
    if (launch.selectedAgent !in SupportedAgent.supportedIds || checkoutGone) {
      launches.childEnded(run.token, run.executionId)
      return NewTaskStartResult.Refused("The launch checkout or agent is no longer available; dismiss this card.")
    }
    val args =
      buildList {
        add("operation")
        add(operationId)
        if (operationId == OperationCardOperationIds.PR_REVIEW_FIX) addAll(launch.originalArguments)
        add("$CONFIRM_PREFIX${run.token}")
        selection?.let { add("$SELECT_PREFIX$it") }
        add("--agent")
        add(launch.selectedAgent)
      }
    val identity = goalRepositoryIdentity(repoRoot, repositoryEnclosingRootPort)
    val ownership = "$identity:$operationId:standalone:confirm:${run.token}"
    val binding =
      OperationBinding(
        launch.launchId,
        run,
        OperationAgentHomes.pinned(launch.selectedAgent, launch.selectedProfile),
      )
    return launchRuntimeCommand(checkoutRoot, ownership, args, binding)
  }

  private fun launchStandaloneCommand(
    repoRoot: Path,
    run: StandaloneRun,
    target: String?,
    agentId: String,
    checkout: StandaloneCheckout,
  ): NewTaskStartResult {
    val arguments = standaloneArguments(run, target)
    val branch = checkout.branch?.trim()?.takeIf(String::isNotBlank)
    standaloneLaunchRefusal(repoRoot, arguments.argvTarget, branch, agentId)?.let { return it }
    val workspace =
      when (val resolved = standaloneWorkspace(repoRoot, run, branch, checkout.checkoutRoot)) {
        is BranchCheckout.Ready -> resolved.workspace
        is BranchCheckout.Refused -> return NewTaskStartResult.Refused(resolved.reason)
      }
    val retention =
      when (val retained = launchRetention(run, workspace)) {
        is LaunchRetention.Kept -> retained.retention
        is LaunchRetention.Refused -> return NewTaskStartResult.Refused(retained.reason)
      }
    val identity = goalRepositoryIdentity(repoRoot, repositoryEnclosingRootPort)
    val ownedTarget = arguments.launchTarget.takeUnless { run.holder.id == QA_PHASE }
    val ownership =
      listOfNotNull("$identity:${run.holder.id}:standalone", ownedTarget, branch?.let { "branch:$it" })
        .joinToString(":")
    val args =
      listOf(run.verb, run.name) + arguments.operation + listOf("--agent", agentId) +
        run.qaDevice.launchArguments()
    if (run.launchId != null) {
      val context =
        OperationLaunchContext(
          launchId = run.launchId,
          operationId = run.name,
          repositoryIdentity = identity,
          databaseIdentity = "",
          checkoutRoot = workspace.root.toString(),
          branch = branch.orEmpty(),
          originalArguments = arguments.operation,
          selectedAgent = agentId,
          selectedProfile = run.profile,
          holderId = run.holder.id,
          retention = retention,
        )
      saveLaunch(context, workspace, run.holder)
    }
    val binding =
      run.launchId?.let { OperationBinding(it, null, OperationAgentHomes.pinned(agentId, run.profile)) }
    val result = launchRuntimeCommand(workspace.root, ownership, args, binding)
    if (result is NewTaskStartResult.Refused && workspace.held) releaseAfterRefusal(workspace.root, run.holder)
    return result
  }

  private fun standaloneArguments(
    run: StandaloneRun,
    target: String?,
  ): StandaloneArguments {
    val launchTarget = target?.trim()?.takeIf(String::isNotBlank)
    val changes =
      listOfNotNull(
        run.proposalChanges?.trim()?.takeIf(String::isNotBlank)?.let {
          "Operator requested proposal changes (later instructions take precedence):\n$it"
        },
      )
    val splitPrReviewMode =
      PR_REVIEW_FIX_MODE_TARGET.matchEntire(launchTarget.orEmpty())
        ?.takeIf { run.verb == "operation" && run.name == OperationCardOperationIds.PR_REVIEW_FIX }
        ?: return StandaloneArguments(launchTarget, launchTarget, listOfNotNull(launchTarget) + changes)
    val argvTarget = splitPrReviewMode.groupValues[2].trim().takeIf(String::isNotBlank)
    return StandaloneArguments(
      launchTarget,
      argvTarget,
      listOfNotNull("mode:${splitPrReviewMode.groupValues[1]}", argvTarget) + changes,
    )
  }

  private fun standaloneWorkspace(
    repoRoot: Path,
    run: StandaloneRun,
    branch: String?,
    checkoutRoot: Path?,
  ): BranchCheckout {
    if (branch != null) return branchCheckout(repoRoot, branch, run.holder)
    val root = checkoutRoot ?: repoRoot
    return BranchCheckout.Ready(
      StandaloneWorkspace(root, held = run.launchId != null && branchWorktrees.hold(root, run.holder)),
    )
  }

  private fun launchRetention(
    run: StandaloneRun,
    workspace: StandaloneWorkspace,
  ): LaunchRetention {
    if (run.launchId == null) return LaunchRetention.Kept(null)
    val retention =
      when (val observation = branchWorktrees.observe(workspace.root, run.holder)) {
        is BranchWorktreeObservation.CallerOwned ->
          if (workspace.held) {
            LaunchRetention.Refused("The managed checkout ownership record is missing; no proposal was launched.")
          } else {
            LaunchRetention.Kept(OperationLaunchRetention.CALLER_OWNED)
          }
        is BranchWorktreeObservation.RuntimeManaged ->
          if (observation.holderPresent) {
            LaunchRetention.Kept(OperationLaunchRetention.MANAGED_HOLDER)
          } else {
            LaunchRetention.Refused("The managed checkout does not retain this operation holder.")
          }
        is BranchWorktreeObservation.Unavailable -> LaunchRetention.Refused(observation.reason)
      }
    if (retention is LaunchRetention.Refused && workspace.held) releaseAfterRefusal(workspace.root, run.holder)
    return retention
  }

  private fun saveLaunch(
    context: OperationLaunchContext,
    workspace: StandaloneWorkspace,
    holder: BranchWorktreeHolder,
  ) {
    var saved = false
    try {
      launches.save(context)
      saved = true
    } finally {
      if (!saved && workspace.held) releaseAfterRefusal(workspace.root, holder)
    }
  }

  private fun standaloneLaunchRefusal(
    repoRoot: Path,
    launchTarget: String?,
    branch: String?,
    agentId: String,
  ): NewTaskStartResult.Refused? {
    val refusal =
      when {
        launchTarget?.startsWith("-") == true ->
          "The target '$launchTarget' starts with a dash, so the CLI would parse it as an option."
        branch?.startsWith(
          "-",
        ) == true -> "The branch '$branch' starts with a dash, so git would parse it as an option."
        agentId !in SupportedAgent.supportedIds -> "Choose a supported agent before starting this run."
        else ->
          goalStartRefusal(
            preflight.preflight(
              GoalPreflightRequest(
                "LOCAL-0",
                repoRoot,
                invokedAgentId = agentId,
                userHome = environment.userHome,
                environment = environment.environment,
              ),
            ),
          )
      }
    return refusal?.let(NewTaskStartResult::Refused)
  }

  private fun launchRuntimeCommand(
    workingDirectory: Path,
    ownership: String,
    args: List<String>,
    binding: OperationBinding? = null,
  ): NewTaskStartResult {
    val launchId = binding?.launchId
    val launched =
      database.transaction { unit ->
        launcher.start(
          WorkRunLaunchRequest(
            workingDirectory,
            unit.dbPath,
            ownership,
            WorkRunLaunchKind.RuntimeCommand(args),
            emptyMap(),
            launchId,
            binding?.agentHomes,
          ),
        )
      }
    val confirmation = binding?.confirmation
    return when (launched) {
      is WorkRunLaunchResult.Refused -> {
        confirmation?.let { launches.childEnded(it.token, it.executionId) }
        NewTaskStartResult.Refused(launched.reason)
      }
      is WorkRunLaunchResult.AwaitingConfirmation -> {
        confirmation?.let { launches.childEnded(it.token, it.executionId) }
        launchId?.let(NewTaskStartResult::AwaitingConfirmation) ?: NewTaskStartResult.Started
      }
      is WorkRunLaunchResult.Started ->
        launched.exit?.let { exit ->
          confirmation?.let { run ->
            exit.whenComplete { _, _ -> launches.childEnded(run.token, run.executionId) }
          }
          NewTaskStartResult.Running(runFailure(exit, launched.logPath), launchId)
        } ?: NewTaskStartResult.Started
    }
  }

  private fun releaseAfterRefusal(
    checkoutRoot: Path,
    holder: BranchWorktreeHolder,
  ) {
    val released = branchWorktrees.release(checkoutRoot, holder)
    if (released is BranchWorktreeRelease.Kept) {
      diagnostics.warning(
        "seam=new-task.branch-worktree outcome=kept_after_refused_launch path=${released.path} " +
          "reason=${released.detail}",
      )
    }
  }

  private fun branchCheckout(
    repoRoot: Path,
    branch: String,
    run: BranchWorktreeHolder,
  ): BranchCheckout {
    goalWorktrees.checkoutHolding(repoRoot, branch)?.let { holding ->
      return BranchCheckout.Ready(StandaloneWorkspace(holding, held = branchWorktrees.hold(holding, run)))
    }
    return when (val created = branchWorktrees.create(repoRoot, branch, run)) {
      is BranchWorktreeCreation.Created -> BranchCheckout.Ready(StandaloneWorkspace(created.path, held = true))
      BranchWorktreeCreation.Missing -> BranchCheckout.Refused("Branch $branch was not found locally or on origin.")
      is BranchWorktreeCreation.Failed -> BranchCheckout.Refused(created.reason)
    }
  }

  private fun standaloneIntakeRefusal(
    intake: String?,
    phaseId: String,
  ): NewTaskStartResult.Refused? {
    if (intake == null) return null
    val trimmed = intake.trim()
    if (trimmed.isEmpty()) return null
    val reason =
      when (phaseId) {
        MONITOR_PHASE -> monitorIntakeRefusal(trimmed)
        QA_PHASE -> qaInstructionsRefusal(trimmed)
        else -> "Intake is supported only for standalone QA and monitor runs."
      }
    return reason?.let(NewTaskStartResult::Refused)
  }

  private fun monitorIntakeRefusal(intake: String): String? =
    when {
      intake.contains(' ') || intake.any { it.isISOControl() } ->
        "Monitor intake must be a single token naming the target."
      intake.startsWithPhaseOption() -> "Monitor intake must name the target directly, not as a phase option."
      else -> null
    }

  private fun qaInstructionsRefusal(instructions: String): String? =
    when {
      instructions.startsWithPhaseOption() ->
        "QA instructions cannot start with mode: or target:, because the CLI would read them as a phase option."
      instructions.toByteArray(Charsets.UTF_8).size > MAX_INTAKE_BYTES ->
        "QA instructions are too long. Shorten them to under $MAX_INTAKE_BYTES bytes."
      else -> null
    }

  private fun String.startsWithPhaseOption(): Boolean =
    startsWith(PHASE_OPTION_MODE_PREFIX) || startsWith(PHASE_OPTION_TARGET_PREFIX)

  private fun runFailure(
    exit: CompletionStage<WorkRunExit>,
    log: Path,
  ): CompletionStage<String?> =
    exit.handle { finished, failure ->
      if (failure == null && finished.code in SUCCESSFUL_EXITS) {
        null
      } else {
        "${stopReason(finished?.failureLogLines.orEmpty())} Full log: $log"
      }
    }

  private fun stopReason(logLines: List<String>): String {
    val lines =
      logLines
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("Picked up ") }
    return (lines.lastOrNull(STOP_LINE::containsMatchIn) ?: lines.lastOrNull())?.take(MAX_REASON_CHARS)
      ?: "The run exited without a reason."
  }

  private fun readyOrRefused(
    repoRoot: Path,
    request: NewTaskIntakeRequest,
    key: String,
    intake: String,
    origin: IdentityOrigin,
  ): NewTaskPreparation {
    if (request.occupiedKeys.any { it.equals(key, ignoreCase = true) }) {
      return NewTaskPreparation.Refused("$key is already in use. Choose another key.")
    }
    val reuseRequested = request.reuseRequested && origin == IdentityOrigin.TYPED
    if (origin == IdentityOrigin.URL && allocator.holds(repoRoot, key)) {
      return NewTaskPreparation.Refused("$key is already used.")
    }
    if (intake.toByteArray(Charsets.UTF_8).size > MAX_INTAKE_BYTES) {
      return NewTaskPreparation.Refused(
        "This chat is too long to start a run from. Discard it and describe the work in one message.",
      )
    }
    return when (val checked = admission.admit(intake, repoRoot)) {
      is GoalNewWorkAdmission.Admitted ->
        if (checked.issueKey == key) {
          NewTaskPreparation.Ready(
            key,
            intake,
            allocated = false,
            reuseRequested = reuseRequested,
          )
        } else {
          NewTaskPreparation.Refused("The composed intake resolved to ${checked.issueKey}, not $key.")
        }
      GoalNewWorkAdmission.NoIssueKey -> NewTaskPreparation.Refused("The composed intake needs an issue key.")
      is GoalNewWorkAdmission.Refused -> NewTaskPreparation.Refused(checked.reason)
    }
  }

  private fun usableTitle(raw: String?): String? {
    if (raw == null) return null
    val title = raw.trim()
    val usable = title.isNotBlank() && '\n' !in title && '\r' !in title && ASCII_WORD.containsMatchIn(title)
    if (!usable) RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "seam=new-task.title outcome=ignored")
    return title.takeIf { usable }
  }

  private fun keyedIntake(
    issueKey: String,
    requirements: String,
    title: String?,
  ): String {
    val leadsWithKey = ISSUE_KEY.matches(requirements.trim().split(Regex("\\s+")).first())
    return if (title != null && leadsWithKey) "$issueKey\n$title\n\n$requirements" else requirements
  }

  private fun appendBase(
    intake: String,
    baseBranch: String?,
  ): String = baseBranch?.takeIf(String::isNotBlank)?.let { "$intake\n\nUse $it as the base branch." } ?: intake

  private enum class IdentityOrigin { TYPED, URL }

  private data class StandaloneRun(
    val verb: String,
    val name: String,
    val holder: BranchWorktreeHolder,
    val launchId: String? = null,
    val profile: String? = null,
    val qaDevice: QaNamedDevice? = null,
    val proposalChanges: String? = null,
  )

  private data class OperationBinding(
    val launchId: String?,
    val confirmation: ConfirmationRun?,
    val agentHomes: Map<String, String>?,
  )

  private data class ConfirmationRun(
    val token: String,
    val executionId: Long,
  )

  private data class StandaloneWorkspace(
    val root: Path,
    val held: Boolean,
  )

  private data class StandaloneArguments(
    val launchTarget: String?,
    val argvTarget: String?,
    val operation: List<String>,
  )

  private sealed interface LaunchRetention {
    data class Kept(
      val retention: OperationLaunchRetention?,
    ) : LaunchRetention

    data class Refused(
      val reason: String,
    ) : LaunchRetention
  }

  private sealed interface BranchCheckout {
    data class Ready(
      val workspace: StandaloneWorkspace,
    ) : BranchCheckout

    data class Refused(
      val reason: String,
    ) : BranchCheckout
  }

  private sealed interface KeyChoice {
    data class Chosen(
      val key: String,
      val allocated: Boolean,
    ) : KeyChoice

    data class Ask(
      val choices: List<String>,
    ) : KeyChoice
  }

  private companion object {
    val ISSUE_KEY = Regex("(?i)$TRACKER_STYLE_ISSUE_KEY_PATTERN")
    val WHITESPACE = Regex("\\s+")
    val ASCII_WORD = Regex("[A-Za-z0-9]")
    val STOP_LINE = Regex("^(?:Phase '[^']+' blocked at |Operation '[^']+' (?:blocked|failed): )")
    const val MAX_INTAKE_BYTES = 30_000
    const val MAX_REASON_CHARS = 500
    const val MONITOR_PHASE = "monitor"
    const val QA_PHASE = "qa"
    val STANDALONE_PHASES = setOf("review", "validation", QA_PHASE, "pr", MONITOR_PHASE)
    val STANDALONE_OPERATIONS = setOf("verify", "pr-review-fix")
    const val PHASE_OPTION_MODE_PREFIX = "mode:"
    const val PHASE_OPTION_TARGET_PREFIX = "target:"
    const val CONFIRM_PREFIX = "confirm:"
    const val SELECT_PREFIX = "select:"
    val SUCCESSFUL_EXITS = setOf(0, OperationCardExitCodes.AWAITING_CONFIRMATION)
    val OCCUPIED_VERDICTS =
      setOf(
        GoalPreflightVerdict.ALREADY_RUNNING,
        GoalPreflightVerdict.RESUMABLE,
        GoalPreflightVerdict.GOAL_CONTINUATION,
        GoalPreflightVerdict.TERMINAL_ONLY,
      )
  }
}

private val PR_REVIEW_FIX_MODE_TARGET =
  Regex("""mode:(local|remote)(?:\s+(.+))?""", RegexOption.DOT_MATCHES_ALL)
