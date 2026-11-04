package skillbill.engine.work.control

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.issuekey.GOAL_INTAKE_URL_MARKER
import skillbill.contracts.issuekey.TRACKER_STYLE_ISSUE_KEY_PATTERN
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.goal.GoalNewWorkAdmission
import skillbill.engine.goal.GoalNewWorkAdmissionPort
import skillbill.engine.goal.GoalPreflightPort
import skillbill.engine.goal.GoalPreflightRequest
import skillbill.engine.goal.GoalPreflightResult
import skillbill.engine.goal.GoalPreflightVerdict
import skillbill.engine.goal.goalRepositoryIdentity
import skillbill.engine.work.control.model.NewTaskIdentityPolicy
import skillbill.engine.work.control.model.NewTaskIntakeRequest
import skillbill.engine.work.control.model.NewTaskPreparation
import skillbill.engine.work.control.model.NewTaskRunKind
import skillbill.engine.work.control.model.NewTaskStartResult
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

  private fun startRefusal(
    checked: GoalPreflightResult,
    prepared: NewTaskPreparation.Ready,
  ): String? =
    if (
      checked.verdict == GoalPreflightVerdict.TERMINAL_ONLY && prepared.reuseRequested &&
      checked.candidates.lastOrNull()?.status.workflowStatus() in
      setOf(WorkflowStatus.COMPLETED, WorkflowStatus.ABANDONED)
    ) {
      null
    } else {
      goalStartRefusal(checked)
    }

  public fun startStandalone(
    repoRoot: Path,
    phaseId: String,
    agentId: String,
    target: String? = null,
  ): NewTaskStartResult {
    if (phaseId !in STANDALONE_PHASES) return NewTaskStartResult.Refused("Unsupported standalone phase '$phaseId'.")
    standaloneIntakeRefusal(target, phaseId)?.let { return it }
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
    val ownership =
      if (launchTarget == null) "$identity:$id:standalone" else "$identity:$id:standalone:$launchTarget"
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
  }

  private fun standaloneIntakeRefusal(
    intake: String?,
    phaseId: String,
  ): NewTaskStartResult.Refused? {
    if (intake == null) return null
    val trimmed = intake.trim()
    if (trimmed.isEmpty()) return null
    return when {
      phaseId != "monitor" ->
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
    request: NewTaskIntakeRequest,
    key: String,
    intake: String,
    origin: IdentityOrigin,
  ): NewTaskPreparation {
    if (request.occupiedKeys.any { it.equals(key, ignoreCase = true) }) {
      return NewTaskPreparation.Refused("$key is already in use. Choose another key.")
    }
    if (allocator.holds(repoRoot, key)) {
      if (origin == IdentityOrigin.URL || !request.reuseRequested) {
        return NewTaskPreparation.Refused(
          if (origin == IdentityOrigin.URL) "$key is already used." else "$key already has a spec.",
        )
      }
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
            reuseRequested = request.reuseRequested && origin == IdentityOrigin.TYPED,
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

  private companion object {
    val ISSUE_KEY = Regex("(?i)$TRACKER_STYLE_ISSUE_KEY_PATTERN")
    val WHITESPACE = Regex("\\s+")
    val ASCII_WORD = Regex("[A-Za-z0-9]")
    val STOP_LINE = Regex("^(?:Phase '[^']+' blocked at |Operation '[^']+' (?:blocked|failed): )")
    const val MAX_INTAKE_BYTES = 30_000
    const val MAX_REASON_CHARS = 500
    val STANDALONE_PHASES = setOf("review", "validation", "qa", "pr", "monitor")
    val STANDALONE_OPERATIONS = setOf("verify", "pr-review-fix")
    const val MONITOR_INTAKE_MODE_PREFIX = "mode:"
    const val MONITOR_INTAKE_TARGET_PREFIX = "target:"
  }
}
