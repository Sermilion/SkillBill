package skillbill.engine.work.control

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.issuekey.TRACKER_STYLE_ISSUE_KEY_PATTERN
import skillbill.engine.goal.GoalNewWorkAdmission
import skillbill.engine.goal.GoalNewWorkAdmissionPort
import skillbill.engine.goal.GoalPreflightPort
import skillbill.engine.goal.GoalPreflightRequest
import skillbill.engine.goal.GoalPreflightVerdict
import skillbill.engine.goal.goalRepositoryIdentity
import skillbill.engine.work.control.model.NewTaskKeyAllocation
import skillbill.engine.work.control.model.NewTaskPreparation
import skillbill.engine.work.control.model.NewTaskRunKind
import skillbill.engine.work.control.model.NewTaskStartResult
import skillbill.install.model.SupportedAgent
import skillbill.model.EnvironmentContext
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.work.WorkRunLauncher
import skillbill.ports.work.model.WorkRunExit
import skillbill.ports.work.model.WorkRunLaunchKind
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
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
) {
  public fun prepareNewTask(
    repoRoot: Path,
    requirements: String,
    baseBranch: String?,
    keyChoice: String?,
    occupiedKeys: Set<String>,
  ): NewTaskPreparation {
    val parsingText = requirements.trim()
    when (val first = admission.admit(requirements, repoRoot)) {
      is GoalNewWorkAdmission.Admitted -> {
        val intake = if (requirements.any(Char::isWhitespace)) appendBase(requirements, baseBranch) else requirements
        return readyOrRefused(repoRoot, first.issueKey, intake, allocated = false)
      }
      is GoalNewWorkAdmission.Refused -> return NewTaskPreparation.Refused(first.reason)
      GoalNewWorkAdmission.NoIssueKey -> Unit
    }

    val normalizedChoice = keyChoice?.trim()?.uppercase()
    val chosenKey = normalizedChoice?.takeIf(ISSUE_KEY::matches)
    val allocation = if (chosenKey == null) allocator.allocate(repoRoot, normalizedChoice, occupiedKeys) else null
    val key =
      chosenKey ?: when (allocation) {
        is NewTaskKeyAllocation.Allocated -> allocation.key
        is NewTaskKeyAllocation.NeedsPrefix -> return NewTaskPreparation.NeedsPrefix(allocation.choices)
        null -> error("Allocation should be resolved when no complete key was supplied.")
      }
    val titleLine = parsingText.lineSequence().firstOrNull(String::isNotBlank).orEmpty()
    val title = titleLine.takeIf(ASCII_WORD::containsMatchIn) ?: "New task from desktop"
    val intake = appendBase("$key\n$title\n\n$requirements", baseBranch)
    return readyOrRefused(repoRoot, key, intake, allocated = chosenKey == null)
  }

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
  ): NewTaskStartResult {
    if (phaseId !in STANDALONE_PHASES) return NewTaskStartResult.Refused("Unsupported standalone phase '$phaseId'.")
    return launchStandaloneCommand(repoRoot, "phase", phaseId, target, agentId)
  }

  public fun startOperation(
    repoRoot: Path,
    operationId: String,
    target: String?,
    agentId: String,
  ): NewTaskStartResult {
    if (operationId !in STANDALONE_OPERATIONS) {
      return NewTaskStartResult.Refused("Unsupported standalone operation '$operationId'.")
    }
    if (operationId == "verify" && target.isNullOrBlank()) {
      return NewTaskStartResult.Refused("Verify needs an intake before it can start.")
    }
    return launchStandaloneCommand(repoRoot, "operation", operationId, target, agentId)
  }

  private fun launchStandaloneCommand(
    repoRoot: Path,
    verb: String,
    id: String,
    target: String?,
    agentId: String,
  ): NewTaskStartResult {
    val launchTarget = target?.trim()?.takeIf(String::isNotBlank)
    if (launchTarget?.startsWith("-") == true) {
      return NewTaskStartResult.Refused(
        "The target '$launchTarget' starts with a dash, so the CLI would parse it as an option.",
      )
    }
    if (agentId !in SupportedAgent.supportedIds) {
      return NewTaskStartResult.Refused("Choose a supported agent before starting this run.")
    }
    val checked =
      preflight.preflight(
        GoalPreflightRequest(
          "LOCAL-0",
          repoRoot,
          invokedAgentId = agentId,
          userHome = environment.userHome,
          environment = environment.environment,
        ),
      )
    goalStartRefusal(checked)?.let { return NewTaskStartResult.Refused(it) }
    val identity = goalRepositoryIdentity(repoRoot, repositoryEnclosingRootPort)
    val args =
      buildList {
        add(verb)
        add(id)
        launchTarget?.let(::add)
        add("--agent")
        add(agentId)
      }
    return database.transaction { unit ->
      when (
        val launched =
          launcher.start(
            WorkRunLaunchRequest(
              repoRoot,
              unit.dbPath,
              "$identity:$id:standalone",
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

  private fun appendBase(
    intake: String,
    baseBranch: String?,
  ): String = baseBranch?.takeIf(String::isNotBlank)?.let { "$intake\n\nUse $it as the base branch." } ?: intake

  private companion object {
    val ISSUE_KEY = Regex("(?i)$TRACKER_STYLE_ISSUE_KEY_PATTERN")
    val ASCII_WORD = Regex("[A-Za-z0-9]")
    val STOP_LINE = Regex("^(?:Phase '[^']+' blocked at |Operation '[^']+' (?:blocked|failed): )")
    const val MAX_INTAKE_BYTES = 30_000
    const val MAX_REASON_CHARS = 500
    val STANDALONE_PHASES = setOf("review", "validation", "qa", "pr", "monitor")
    val STANDALONE_OPERATIONS = setOf("verify", "pr-review-fix")
  }
}
