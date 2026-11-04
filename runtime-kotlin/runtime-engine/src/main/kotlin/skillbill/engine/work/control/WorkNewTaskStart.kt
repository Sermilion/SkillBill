package skillbill.engine.work.control

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.issuekey.TRACKER_STYLE_ISSUE_KEY_PATTERN
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.goal.GoalNewWorkAdmission
import skillbill.engine.goal.GoalNewWorkAdmissionPort
import skillbill.engine.goal.GoalPreflightPort
import skillbill.engine.goal.GoalPreflightRequest
import skillbill.engine.goal.GoalPreflightVerdict
import skillbill.engine.goal.goalRepositoryIdentity
import skillbill.engine.work.control.model.NewTaskIntakeRequest
import skillbill.engine.work.control.model.NewTaskKeyAllocation
import skillbill.engine.work.control.model.NewTaskPreparation
import skillbill.engine.work.control.model.NewTaskRunKind
import skillbill.engine.work.control.model.NewTaskStartResult
import skillbill.engine.work.control.model.StandaloneCheckout
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
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeRelease
import skillbill.ports.workflow.goalworktree.GoalWorktreeResources
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
) {
  public fun prepareNewTask(
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
        return readyOrRefused(repoRoot, first.issueKey, intake, allocated = false)
      }
      is GoalNewWorkAdmission.Refused -> return NewTaskPreparation.Refused(first.reason)
      GoalNewWorkAdmission.NoIssueKey -> Unit
    }

    val chosen =
      when (val choice = chooseKey(repoRoot, requirements, request.keyChoice, request.occupiedKeys)) {
        is KeyChoice.Ask -> return NewTaskPreparation.NeedsKey(choice.choices)
        is KeyChoice.Chosen -> choice
      }
    val titleLine = parsingText.lineSequence().firstOrNull(String::isNotBlank).orEmpty()
    val title = agentTitle ?: titleLine.takeIf(ASCII_WORD::containsMatchIn) ?: "New task from desktop"
    val intake = appendBase("${chosen.key}\n$title\n\n$requirements", request.baseBranch)
    return readyOrRefused(repoRoot, chosen.key, intake, chosen.allocated)
  }

  private fun chooseKey(
    repoRoot: Path,
    requirements: String,
    keyChoice: String?,
    occupiedKeys: Set<String>,
  ): KeyChoice {
    val normalizedChoice = keyChoice?.trim()?.uppercase()?.takeIf(String::isNotBlank)
    if (normalizedChoice != null && ISSUE_KEY.matches(normalizedChoice)) {
      return KeyChoice.Chosen(normalizedChoice, false)
    }
    val prefixes = allocator.prefixes(repoRoot)
    if (normalizedChoice != null) {
      val allocation = allocator.allocate(repoRoot, normalizedChoice, occupiedKeys)
      if (allocation is NewTaskKeyAllocation.Allocated) return KeyChoice.Chosen(allocation.key, true)
    }
    val linked = linkedKeys(requirements)
    val named = namedKeys(requirements, prefixes)
    val inferred = linked.singleOrNull() ?: named.singleOrNull()?.takeIf { linked.isEmpty() }
    if (normalizedChoice == null && inferred != null) return KeyChoice.Chosen(inferred, false)
    val nextFree =
      prefixes.mapNotNull { prefix ->
        (allocator.allocate(repoRoot, prefix, occupiedKeys) as? NewTaskKeyAllocation.Allocated)?.key
      }
    return KeyChoice.Ask((linked + named + nextFree).distinct())
  }

  private fun linkedKeys(text: String): List<String> =
    URL.findAll(text)
      .flatMap { url -> url.value.split('/', '?', '#', '&', '=').asSequence() }
      .filter(MENTIONED_KEY::matches)
      .distinct()
      .toList()

  private fun namedKeys(
    text: String,
    prefixes: List<String>,
  ): List<String> =
    text.split(WHITESPACE)
      .map { token -> token.trim { it in TOKEN_PUNCTUATION } }
      .filter { MENTIONED_KEY.matches(it) && it.substringBeforeLast('-') in prefixes }
      .distinct()

  public fun startNewTask(
    repoRoot: Path,
    prepared: NewTaskPreparation.Ready,
    kind: NewTaskRunKind,
    agentId: String,
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
    val refusal = goalStartRefusal(checked)
    if (refusal != null) {
      val occupied =
        checked.verdict in
          setOf(
            GoalPreflightVerdict.ALREADY_RUNNING,
            GoalPreflightVerdict.RESUMABLE,
            GoalPreflightVerdict.GOAL_CONTINUATION,
            GoalPreflightVerdict.TERMINAL_ONLY,
          )
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
    return database.transaction { unit ->
      when (
        val launched =
          launcher.start(
            WorkRunLaunchRequest(
              repoRoot,
              unit.dbPath,
              ownership,
              WorkRunLaunchKind.RuntimeCommand(args),
              emptyMap(),
            ),
          )
      ) {
        is WorkRunLaunchResult.Refused -> NewTaskStartResult.Refused(launched.reason)
        is WorkRunLaunchResult.Started -> NewTaskStartResult.Started
      }
    }
  }

  public fun startStandalone(
    repoRoot: Path,
    phaseId: String,
    agentId: String,
    target: String? = null,
    checkout: StandaloneCheckout = StandaloneCheckout(),
  ): NewTaskStartResult {
    if (phaseId !in STANDALONE_PHASES) return NewTaskStartResult.Refused("Unsupported standalone phase '$phaseId'.")
    standaloneIntakeRefusal(target, phaseId)?.let { return it }
    val place = if (phaseId == MONITOR_PHASE) checkout.copy(branch = null) else checkout
    val run = StandaloneRun("phase", BranchWorktreeHolder(BranchWorktreeHolderKind.PHASE, phaseId))
    return launchStandaloneCommand(repoRoot, run, target, agentId, place)
  }

  public fun startOperation(
    repoRoot: Path,
    operationId: String,
    target: String?,
    agentId: String,
    checkout: StandaloneCheckout = StandaloneCheckout(),
  ): NewTaskStartResult {
    if (operationId !in STANDALONE_OPERATIONS) {
      return NewTaskStartResult.Refused("Unsupported standalone operation '$operationId'.")
    }
    if (operationId == "verify" && target.isNullOrBlank()) {
      return NewTaskStartResult.Refused("Verify needs an intake before it can start.")
    }
    val run = StandaloneRun("operation", BranchWorktreeHolder(BranchWorktreeHolderKind.OPERATION, operationId))
    return launchStandaloneCommand(repoRoot, run, target, agentId, checkout)
  }

  private fun launchStandaloneCommand(
    repoRoot: Path,
    run: StandaloneRun,
    target: String?,
    agentId: String,
    checkout: StandaloneCheckout,
  ): NewTaskStartResult {
    val launchTarget = target?.trim()?.takeIf(String::isNotBlank)
    val branch = checkout.branch?.trim()?.takeIf(String::isNotBlank)
    standaloneLaunchRefusal(repoRoot, launchTarget, branch, agentId)?.let { return it }
    val workspace =
      when (val resolved = branch?.let { branchCheckout(repoRoot, it, run.holder) }) {
        null -> StandaloneWorkspace(checkout.checkoutRoot ?: repoRoot, held = false)
        is BranchCheckout.Ready -> resolved.workspace
        is BranchCheckout.Refused -> return NewTaskStartResult.Refused(resolved.reason)
      }
    val identity = goalRepositoryIdentity(repoRoot, repositoryEnclosingRootPort)
    val ownership =
      listOfNotNull("$identity:${run.holder.id}:standalone", launchTarget, branch?.let { "branch:$it" })
        .joinToString(":")
    val args = listOfNotNull(run.verb, run.holder.id, launchTarget, "--agent", agentId)
    val result = launchRuntimeCommand(workspace.root, ownership, args)
    if (result is NewTaskStartResult.Refused && workspace.held) releaseAfterRefusal(workspace.root, run.holder)
    return result
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
  ): NewTaskStartResult =
    database.transaction { unit ->
      when (
        val launched =
          launcher.start(
            WorkRunLaunchRequest(
              workingDirectory,
              unit.dbPath,
              ownership,
              WorkRunLaunchKind.RuntimeCommand(args),
              emptyMap(),
            ),
          )
      ) {
        is WorkRunLaunchResult.Refused -> NewTaskStartResult.Refused(launched.reason)
        is WorkRunLaunchResult.Started ->
          launched.exit?.let { NewTaskStartResult.Running(runFailure(it, launched.logPath)) }
            ?: NewTaskStartResult.Started
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
    return when {
      phaseId != MONITOR_PHASE ->
        NewTaskStartResult.Refused("Intake is supported only for standalone monitor runs.")
      trimmed.contains(' ') || trimmed.any { it.isISOControl() } ->
        NewTaskStartResult.Refused("Monitor intake must be a single token naming the target.")
      trimmed.startsWith(MONITOR_INTAKE_MODE_PREFIX) || trimmed.startsWith(MONITOR_INTAKE_TARGET_PREFIX) ->
        NewTaskStartResult.Refused("Monitor intake must name the target directly, not as a phase option.")
      else -> null
    }
  }

  private fun runFailure(
    exit: CompletionStage<WorkRunExit>,
    log: Path,
  ): CompletionStage<String?> =
    exit.handle { finished, failure ->
      if (failure == null && finished.code == 0) {
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
    key: String,
    intake: String,
    allocated: Boolean,
  ): NewTaskPreparation {
    if (intake.toByteArray(Charsets.UTF_8).size > MAX_INTAKE_BYTES) {
      return NewTaskPreparation.Refused(
        "This chat is too long to start a run from. Discard it and describe the work in one message.",
      )
    }
    return when (val checked = admission.admit(intake, repoRoot)) {
      is GoalNewWorkAdmission.Admitted ->
        if (checked.issueKey == key) {
          NewTaskPreparation.Ready(key, intake, allocated)
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

  private data class StandaloneRun(
    val verb: String,
    val holder: BranchWorktreeHolder,
  )

  private data class StandaloneWorkspace(
    val root: Path,
    val held: Boolean,
  )

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
    val MENTIONED_KEY = Regex("[A-Z][A-Z0-9]*-\\d+(?:\\.\\d+)?")
    val URL = Regex("https?://[^\\s<>()\"'`\\]]+")
    val WHITESPACE = Regex("\\s+")
    const val TOKEN_PUNCTUATION = "()[]{}<>.,;:!?\"'`*_"
    val ASCII_WORD = Regex("[A-Za-z0-9]")
    val STOP_LINE = Regex("^(?:Phase '[^']+' blocked at |Operation '[^']+' (?:blocked|failed): )")
    const val MAX_INTAKE_BYTES = 30_000
    const val MAX_REASON_CHARS = 500
    const val MONITOR_PHASE = "monitor"
    val STANDALONE_PHASES = setOf("review", "validation", "qa", "pr", MONITOR_PHASE)
    val STANDALONE_OPERATIONS = setOf("verify", "pr-review-fix")
    const val MONITOR_INTAKE_MODE_PREFIX = "mode:"
    const val MONITOR_INTAKE_TARGET_PREFIX = "target:"
  }
}
