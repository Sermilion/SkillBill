package dev.skillbill.tray.infrastructure.cli

import dev.skillbill.tray.application.BoardSource
import dev.skillbill.tray.application.Outcome
import dev.skillbill.tray.application.eligibleForControl
import dev.skillbill.tray.domain.*
import dev.skillbill.tray.domain.Retention
import kotlinx.coroutines.channels.Channel

class CliBoardSource(
  private val resolver: CliResolver = CliResolver(),
  private val pollRunner: ProcessRunner = ProcessRunner(),
  private val detailRunner: ProcessRunner = ProcessRunner(),
  private val pauseRunner: ProcessRunner = ProcessRunner(),
  private val stopRunner: ProcessRunner = ProcessRunner(),
) : BoardSource {
  private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
  @Volatile private var executableOverride: String? = null
  private val boardLock = Any()
  private var acceptedBoard: Board? = null

  override fun setExecutableOverride(path: String?) {
    synchronized(boardLock) {
      if (executableOverride != path) acceptedBoard = null
      executableOverride = path
    }
  }

  override suspend fun board(retention: Retention): Outcome<Board> = invoke(pollRunner, executableOverride,
    listOf("work", "board", "--format", "json", "--finished-within", retention.cliValue)) { WorkPayloadDecoder.board(it) }

  override fun acceptBoard(board: Board) { synchronized(boardLock) { acceptedBoard = board } }
  override fun invalidateBoard() { synchronized(boardLock) { acceptedBoard = null } }

  override suspend fun detail(identity: WorkIdentity): Outcome<Detail> {
    return when (val outcome = invoke(detailRunner, executableOverride, listOf("work", "detail", identity.id, "--format", "json")) { WorkPayloadDecoder.detail(it, identity) }) {
      is Outcome.Failure -> outcome
      is Outcome.Value -> outcome.value.item?.let { Outcome.Value(it) } ?: Outcome.Failure(outcome.value.problem ?: Problem("unknown_work_item", "This work item is no longer available."))
    }
  }

  override suspend fun refresh() { refreshRequests.trySend(Unit) }
  override suspend fun nextRefresh() { refreshRequests.receive() }

  override suspend fun pause(item: WorkItem): Outcome<Unit> = mutate(pauseRunner, "pause", item.identity)
  override suspend fun stop(item: WorkItem): Outcome<Unit> = mutate(stopRunner, "stop", item.identity)

  private suspend fun mutate(runner: ProcessRunner, command: String, identity: WorkIdentity): Outcome<Unit> {
    val item = synchronized(boardLock) { acceptedBoard?.items?.singleOrNull { it.identity == identity } }
      ?: return Outcome.Failure(Problem("unavailable", "This action is unavailable."))
    if (!eligibleForControl(item) || item.freshness != Freshness.FRESH || (command == "pause" && item.pauseRequested)) return Outcome.Failure(Problem("unavailable", "This action is unavailable."))
    return invoke(runner, executableOverride, listOf("goal", command, item.issueKey!!, "--repo-root", item.repositoryRoot), processGate = { start ->
      synchronized(boardLock) {
        val current = acceptedBoard?.items?.singleOrNull { it.identity == identity }
        if (current != null && eligibleForControl(current) && current.freshness == Freshness.FRESH &&
          (command != "pause" || !current.pauseRequested) && current.issueKey == item.issueKey && current.repositoryRoot == item.repositoryRoot
        ) start(listOf("goal", command, current.issueKey!!, "--repo-root", current.repositoryRoot)) else {
          RuntimeDiagnostics.record("goal_control_invalidated")
          null
        }
      }
    }) { Unit }
  }

  private suspend fun <T> invoke(runner: ProcessRunner, override: String?, arguments: List<String>, processGate: (((List<String>) -> Process) -> Process?)? = null, decode: (String) -> T): Outcome<T> {
    val executable = resolver.resolve(override)
    if (!override.isNullOrBlank() && executable == null) {
      return Outcome.Failure(Problem("invalid_cli_override", "The configured Skill Bill CLI path is unavailable."))
    }
    val command = executable
      ?: return Outcome.Failure(Problem("cli_not_found", "Skill Bill CLI was not found.").also { RuntimeDiagnostics.record("cli_not_found") })
    val batch = isWindowsBatch(command.first())
    val rawCommand = command + arguments
    if (batch && rawCommand.any { argument -> argument.any { it == '"' || it == '\r' || it == '\n' } }) {
      RuntimeDiagnostics.record("invalid_batch_argument")
      return Outcome.Failure(Problem("unavailable", "This request is unavailable."))
    }
    val environment = if (batch) rawCommand.mapIndexed { index, argument -> "SKILL_BILL_DESKTOP_ARG_$index" to argument }.toMap() else emptyMap()
    val batchLine = environment.keys.joinToString(" ") { key -> "\"%$key%\"" }
    val fullCommand = if (batch) listOf("cmd.exe", "/d", "/v:off", "/s", "/c", "\"$batchLine\"") else rawCommand
    val result = if (processGate == null) runner.run(fullCommand, environment) else runner.run(fullCommand, environment, processGate = { start ->
      processGate { actionArguments -> start(if (batch) fullCommand else command + actionArguments) }
    })
    return when (result) {
      is ProcessResult.Success -> try { Outcome.Value(decode(result.output)) } catch (error: IncompatibleContractException) {
        RuntimeDiagnostics.record("incompatible_contract")
        Outcome.Failure(Problem("incompatible_contract", error.message.orEmpty()))
      } catch (_: Exception) {
        RuntimeDiagnostics.record("malformed_response")
        Outcome.Failure(Problem("malformed_response", "Skill Bill returned an invalid response."))
      }
      ProcessResult.Busy -> Outcome.Failure(Problem("busy", "A Skill Bill request is still running."))
      ProcessResult.Timeout -> Outcome.Failure(Problem("timeout", "Skill Bill did not respond before the request deadline."))
      ProcessResult.OutputLimit -> Outcome.Failure(Problem("output_limit", "Skill Bill returned too much data."))
      ProcessResult.Failed -> Outcome.Failure(Problem("command_failed", "Skill Bill could not complete the request."))
      ProcessResult.Rejected -> Outcome.Failure(Problem("unavailable", "This action is unavailable."))
    }
  }

  private fun isWindowsBatch(path: String) = path.endsWith(".bat", true) || path.endsWith(".cmd", true)
}

object RuntimeDiagnostics {
  private val logger = System.getLogger("dev.skillbill.tray")
  fun record(category: String) { logger.log(System.Logger.Level.WARNING, "desktop_cli:$category") }
}
