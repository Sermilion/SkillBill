package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.usage.UsageProfileResolver
import dev.skillbill.desktop.core.domain.usage.UsageObservationSink
import dev.skillbill.desktop.core.domain.usage.model.UsageProfileLookup
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.ports.approval.claudeProfileIdentity
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock

/**
 * Keeps one `claude -p` process per chat session in the execution's worktree, with permission requests answered through a
 * broker approval card instead of skipped, and writes each turn to it as a stream-json message. The process outlives the turn, so a background command the agent
 * started keeps running, and the reply the agent starts when it finishes reaches [unprompted]. A turn cancelled before its
 * reply ends stops the process; [end] stops it between turns. A resumed session runs with the config directory it came from,
 * else the one the settings select.
 */
@SingleIn(AppScope::class)
class ClaudeChatTransport internal constructor(
  private val host: HostEnvironment,
  private val settings: AppSettingsRepository,
  private val launcher: LiveChatProcessLauncher,
  private val approvals: ChatApprovalOpener,
  private val diagnostics: Diagnostics,
  appScope: ApplicationCoroutineScope,
  private val usageProfiles: UsageProfileResolver,
  private val usageSink: UsageObservationSink,
  private val clock: Clock,
) : ChatTransport {
  @Inject
  constructor(
    host: HostEnvironment,
    settings: AppSettingsRepository,
    launcher: LiveChatProcessLauncher,
    approvals: ChatApprovalAuthority,
    diagnostics: Diagnostics,
    appScope: ApplicationCoroutineScope,
    usageProfiles: UsageProfileResolver,
    usageSink: UsageObservationSink,
    clock: Clock,
  ) : this(host, settings, launcher, ChatApprovalOpener(approvals::open), diagnostics, appScope, usageProfiles, usageSink, clock)

  override val agentId = "claude"
  override val approvalCapable = true

  private val scope = appScope.scope
  private val sessions = ConcurrentHashMap<String, ClaudeLiveSession>()
  private val stopping = ConcurrentHashMap<String, Job>()
  private val unpromptedEvents = MutableSharedFlow<Pair<String, ChatEvent>>(extraBufferCapacity = UNPROMPTED_BUFFER)
  private val processGenerations = AtomicLong()

  override fun run(request: ChatTurnRequest): Flow<ChatEvent> = flow {
    val selectedProfile = request.agent.profile ?: settings.settings.first().claudeConfigDir
    val profile = usageProfiles.claude(selectedProfile)
    val command = ChatCommand(
      argv = agentCommand("claude", claudeArguments(request), host.variables["SHELL"]),
      workingDirectory = request.scope.repositoryRoot,
      environment = profile.environment,
      stdin = "",
    )
    val uuid = UUID.randomUUID().toString()
    val sink = Channel<ChatEvent>(Channel.UNLIMITED)
    val binding = ChatProcessBinding(request.mode, profile.environment[AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR].orEmpty())
    // A live process serves only the mode and profile its generation was opened for: a changed turn replaces it, and
    // the replaced generation's outstanding requests are cancelled when its job ends. `--resume` keeps the conversation.
    // It also keeps the directory it started in, so a turn in another directory needs a new process.
    sessions[request.sessionId]
      ?.takeIf { it.binding != binding || it.workingDirectory != command.workingDirectory }
      ?.let { stop(request.sessionId, it) }
    awaitStopped(request.sessionId)
    val session = registered(request.sessionId, command, binding, uuid, sink, profile.lookup)
    session.send(uuid, request.prompt, request.images)
    var answered = false
    try {
      for (event in sink) {
        emit(event)
        if (event is ChatEvent.Completed || event is ChatEvent.Failed) answered = true
      }
    } finally {
      if (!answered) stop(request.sessionId, session)
    }
  }

  override fun unprompted(sessionId: String): Flow<ChatEvent> =
    unpromptedEvents.filter { it.first == sessionId }.map { it.second }

  override fun end(sessionId: String) {
    sessions[sessionId]?.let { stop(sessionId, it) }
  }

  private fun registered(
    sessionId: String,
    command: ChatCommand,
    binding: ChatProcessBinding,
    uuid: String,
    sink: Channel<ChatEvent>,
    profile: UsageProfileLookup,
  ): ClaudeLiveSession {
    while (true) {
      scope.ensureActive()
      var created: ClaudeLiveSession? = null
      val session = sessions.computeIfAbsent(sessionId) { open(sessionId, command, binding, profile).also { created = it } }
      if (session.register(uuid, sink)) {
        created?.job?.start()
        return session
      }
      sessions.remove(sessionId, session)
    }
  }

  private fun open(
    sessionId: String,
    command: ChatCommand,
    binding: ChatProcessBinding,
    profile: UsageProfileLookup,
  ): ClaudeLiveSession {
    val processGeneration = processGenerations.incrementAndGet()
    usageSink.bindSession(sessionId, profile, processGeneration)
    val session = ClaudeLiveSession(
      binding,
      command.workingDirectory,
      diagnostics,
      sessionId,
      processGeneration,
      usageSink,
      clock,
    ) { event -> unpromptedEvents.tryEmit(sessionId to event) }
    val opening = approvals.open(
      sessionId = sessionId,
      repositoryRoot = command.workingDirectory,
      mode = binding.mode,
      profileIdentity = claudeProfileIdentity(
        command.environment[AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR],
        runCatching { Path.of(host.userHome) }.getOrNull(),
      ),
      writeLine = { line -> session.input.writeAndAwaitBlocking(line) },
      stopProcess = { stop(sessionId, session) },
      onCard = session::showCard,
    )
    session.job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
      var watchdog: Job? = null
      try {
        when (opening) {
          is ChatApprovalOpening.Refused -> session.accept(ChatProcessOutput.StartFailed(opening.reason))
          is ChatApprovalOpening.Opened -> {
            session.approval = opening.approval
            // The database session closed after the generation opened and before the process started: never start it.
            if (opening.approval.isReleased) {
              session.accept(ChatProcessOutput.StartFailed(SESSION_CHANGED_REASON))
            } else {
              val process = launch(start = CoroutineStart.LAZY) {
                launcher.openAcknowledged(command, session.input).collect(session::accept)
              }
              process.start()
              if (!session.input.writeAndAwait(opening.approval.initializeFrame())) {
                session.failStartup("Claude initialization delivery is uncertain; it will not be replayed.")
              }
              // No user frame is sent before the provider answers initialize; silence means an incompatible CLI.
              watchdog = launch {
                val answered = withTimeoutOrNull(INITIALIZE_WAIT_MS) { session.initializedSignal.await() }
                if (answered == null) session.failStartup(INITIALIZE_TIMEOUT_MESSAGE)
              }
              process.join()
            }
          }
        }
      } finally {
        watchdog?.cancel()
        sessions.remove(sessionId, session)
      }
    }
    session.job.invokeOnCompletion {
      usageSink.revokeSession(sessionId, processGeneration)
      session.abandon()
    }
    return session
  }

  private fun stop(sessionId: String, session: ClaudeLiveSession) {
    usageSink.revokeSession(sessionId, session.processGeneration)
    val job = session.job
    stopping[sessionId] = job
    job.invokeOnCompletion { stopping.remove(sessionId, job) }
    sessions.remove(sessionId, session)
    job.cancel()
  }

  private suspend fun awaitStopped(sessionId: String) {
    val ending = stopping[sessionId] ?: return
    if (withTimeoutOrNull(STOP_WAIT_MS) { ending.join() } == null) diagnostics.record("chat.stop_wait_expired")
  }

  private companion object {
    const val UNPROMPTED_BUFFER = 1024
    const val STOP_WAIT_MS = 5000L
    const val INITIALIZE_WAIT_MS = 60_000L
    const val INITIALIZE_TIMEOUT_MESSAGE =
      "The installed Claude CLI did not answer the host permission initialize request. " +
        "Update Claude Code; the chat was not started."
  }
}

/** A later turn resumes the session in a new process only when the one that held it ended. */
internal fun claudeArguments(request: ChatTurnRequest): List<String> = buildList {
  addAll(listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose"))
  addAll(listOf("--include-partial-messages", "--replay-user-messages"))
  request.agent.model?.let { addAll(listOf("--model", it)) }
  request.agent.effort?.let { addAll(listOf("--effort", it)) }
  addAll(listOf(if (request.firstTurn) "--session-id" else "--resume", request.sessionId))
  // Permission requests go to the host as `can_use_tool` control requests on stdio; the operator answers them on an
  // approval card. Without the stdio prompt tool the CLI has no host handler and denies every prompt with its ask reason.
  addAll(listOf("--permission-prompts", "host"))
  addAll(listOf("--permission-prompt-tool", "stdio"))
  add("--dangerously-skip-permissions")
  addAll(listOf("--system-prompt-snapshot", "off"))
  addAll(listOf("--append-system-prompt", scopeSystemPrompt(request.scope)))
}

/** An empty `CLAUDE_CONFIG_DIR` means the default account, which only works with the variable unset. */
internal fun claudeEnvironment(variables: Map<String, String>, homes: Map<String, String>): Map<String, String> {
  val environment = variables + homes
  return if (homes[AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR]?.isEmpty() == true) {
    environment - AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR
  } else {
    environment
  }
}
