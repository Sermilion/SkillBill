package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.usage.cursorEnvironment
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.model.hasSameConversationIdentity
import dev.skillbill.desktop.core.domain.model.SessionMoveResult
import dev.skillbill.desktop.core.domain.model.SavedChatProjection
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Agents whose CLI keeps its own session store; the rest only resume chats the app saved. */
private val sessionStoreAgents = setOf("claude", "codex", "cursor")

/** Agents whose transport resumes through a session id it learned from the stream, which [ChatStore] keeps. */
private val handleAgents = setOf("codex", "cursor")

/**
 * The chats `/resume` offers: every profile's own sessions of claude, codex and cursor in one worktree, and the app's saved chats
 * of agents without a session store. A session the app started shows the lines the app saved, without the snapshot each prompt
 * carried. Blocking file reads; callers run them off the UI thread.
 */
@Inject
@SingleIn(AppScope::class)
class AgentSessionCatalog(
  private val host: HostEnvironment,
  private val settings: AppSettingsRepository,
  private val store: ChatStore,
  private val diagnostics: Diagnostics,
) {
  private val listed = ConcurrentHashMap<String, AgentSession>()
  private val moveLock = Mutex()

  internal suspend fun list(
    executionId: String,
    root: String,
    currentSessionId: String,
    agentFor: suspend (String) -> ChatAgent,
  ): SavedChatProjection {
    val excluded = setOfNotNull(currentSessionId, store.agentSession(currentSessionId))
    val sessions = sessionsIn(root).filter { it.id !in excluded }.sortedByDescending { it.updatedAt }.take(LISTED_SESSIONS)
    sessions.forEach { listed[it.id] = it }
    val native = sessions.map { session ->
      val saved = store.savedFor(session.id)
      val agent = (saved?.agent ?: agentFor(session.agentId)).copy(profile = session.profile.path)
      ChatHistoryEntry(
        session.id, saved?.executionId, agent, session.updatedAt,
        saved?.title ?: session.title ?: "${session.agentId} session", saved?.lines.orEmpty(),
        summary = saved?.summary,
        appSessionId = saved?.sessionId,
        executionCheckoutRoot = root,
      )
    }
    val appSaved = store.savedChats(executionId)
    val appOnly = appSaved.entries.filter {
      it.agent.agentId !in sessionStoreAgents && it.sessionId !in excluded && store.agentSession(it.sessionId) !in excluded
    }
    val recoveries = appSaved.recoveries.filterNot { it.sessionId in excluded }
    return SavedChatProjection((native + appOnly).sortedByDescending { it.updatedAt }, recoveries)
  }

  internal fun open(entry: ChatHistoryEntry): List<ChatHistoryLine> {
    if (entry.agent.agentId == "devin") {
      val saved = store.savedEntry(entry.sessionId)
      val state = store.devinSavedState(entry.sessionId)
      val root = entry.executionCheckoutRoot ?: entry.newTask?.workingDirectory
      val storedRoot = saved?.executionCheckoutRoot ?: saved?.newTask?.workingDirectory
      if (saved == null || state !is DevinSavedState.Readable || state.handle.isNullOrBlank() ||
        saved.executionId != entry.executionId || saved.agent != entry.agent || !saved.newTask.hasSameConversationIdentity(entry.newTask) ||
        root == null || root != storedRoot || !File(root).isDirectory
      ) {
        diagnostics.record("chat.devin_saved_open_refused")
        val path = (state as? DevinSavedState.Unusable)?.path
        throw IOException(
          "The saved Devin chat cannot be resumed safely. ${path?.let { "Original data remains at $it. " }.orEmpty()}Preserve or repair the saved record, or start a separate new chat.",
        )
      }
      return saved.lines
    }
    if (entry.agent.agentId == "opencode") {
      val saved = store.savedEntry(entry.sessionId)
      val state = store.openCodeSavedState(entry.sessionId)
      if (saved == null || state !is OpenCodeSavedState.Readable || state.agentId != "opencode" ||
        state.handle.isNullOrBlank() || state.binding == null || saved.executionId != entry.executionId ||
        saved.agent != entry.agent || saved.newTask != entry.newTask) {
        diagnostics.record("chat.opencode_saved_open_refused")
        val path = (state as? OpenCodeSavedState.Unusable)?.path
        throw IOException(
          "The saved OpenCode chat cannot be resumed safely. ${path?.let { "Original data remains at $it. " }.orEmpty()}Preserve or repair the saved record, or start a separate new chat.",
        )
      }
      return saved.lines
    }
    if (entry.agent.agentId == "codex" && store.agentSession(entry.sessionId) == null) {
      if (!store.saveChat(entry) || !store.saveAgentSession(entry.sessionId, entry.sessionId)) {
        throw IOException("Could not save the provider thread for this chat. Try opening it again.")
      }
    } else if (entry.agent.agentId == "cursor") {
      val storageId = entry.appSessionId ?: entry.sessionId
      val environment = cursorEnvironment(host.variables, entry.agent.profile)
      val profile = environment[AgentHomeEnvironmentKeys.CURSOR_CONFIG_DIR]
        ?: environment["HOME"]
        ?: host.userHome
      val existingBinding = store.cursorSessionBinding(storageId)
      if (existingBinding != null) {
        if (!store.saveAgentSession(storageId, entry.sessionId)) {
          throw IOException("Could not save the provider thread for this chat. Try opening it again.")
        }
      } else {
        val root = entry.executionCheckoutRoot
          ?: throw IOException("The saved Cursor checkout is unknown. Start an explicit new chat; history was not replaced.")
        val binding = CursorSessionBinding(profile, Path.of(root).toAbsolutePath().normalize().toString())
        if (!store.saveCursorSession(storageId, entry.sessionId, binding)) {
          throw IOException("Could not save the provider thread for this chat. Try opening it again.")
        }
      }
    }
    if (entry.lines.isNotEmpty()) return entry.lines
    val file = listed[entry.sessionId]?.file ?: return emptyList()
    return readSafely("chat.session_lines_unreadable") {
      when (entry.agent.agentId) {
        "claude" -> claudeLines(file)
        "codex" -> codexLines(file)
        else -> emptyList()
      }
    }
  }

  /**
   * Moves the Claude sessions of [fromRoot] to [toRoot] and rewrites the cwd of its Codex sessions, in every profile; Cursor chats
   * are not touched. A profile or session that cannot be moved counts as failed. Moves run one at a time across every window, so a
   * second window's move finds nothing left instead of racing the first one's file moves.
   */
  internal suspend fun move(fromRoot: String, toRoot: String): SessionMoveResult = moveLock.withLock {
    val profiles = profiles()
    var failed = 0
    fun moveIn(profiles: List<File>, move: (File, String, String) -> StoreMove): Int = profiles.sumOf { profile ->
      try {
        move(profile, fromRoot, toRoot).also { failed += it.failed }.moved
      } catch (_: IOException) {
        failed++
        0
      } catch (_: SecurityException) {
        failed++
        0
      }
    }
    val claudeMoved = moveIn(profiles.claude, ::moveClaudeSessions)
    val codexMoved = moveIn(profiles.codex, ::rewriteCodexSessions)
    relocateListed(fromRoot, toRoot)
    if (failed > 0) diagnostics.record("chat.session_move_failed")
    SessionMoveResult(claudeMoved, codexMoved, failed)
  }

  /** Points listed Claude sessions of [fromRoot] at their moved file, so a resume picked from an earlier listing still reads its lines. */
  private fun relocateListed(fromRoot: String, toRoot: String) {
    val source = "projects/${claudeProjectName(fromRoot)}"
    val target = "projects/${claudeProjectName(toRoot)}"
    listed.replaceAll { _, session ->
      val file = session.file
      if (session.agentId != "claude" || file == null || file.parentFile != File(session.profile, source) || file.isFile) {
        return@replaceAll session
      }
      val moved = File(session.profile, "$target/${file.name}")
      if (moved.isFile) session.copy(file = moved) else session
    }
  }

  private suspend fun sessionsIn(root: String): List<AgentSession> {
    val profiles = profiles()
    fun read(profiles: List<File>, sessions: (File, String) -> List<AgentSession>) =
      profiles.flatMap { profile -> readSafely("chat.session_store_unreadable") { sessions(profile, root) } }
    return read(profiles.claude, ::claudeSessions) + read(profiles.codex, ::codexSessions) + read(profiles.cursor, ::cursorSessions)
  }

  private class Profiles(val claude: List<File>, val codex: List<File>, val cursor: List<File>)

  private suspend fun profiles(): Profiles {
    val home = File(host.userHome)
    val claudeSelected = settings.settings.first().claudeConfigDir
    val variables = host.variables
    val xdgCursor = variables["XDG_CONFIG_HOME"]?.takeIf(String::isNotBlank)?.let { File(it, "cursor").path }
    return Profiles(
      claude = agentProfiles(home, "claude", "projects", listOf(claudeSelected, variables[AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR])),
      codex = agentProfiles(home, "codex", "sessions", listOf(variables[AgentHomeEnvironmentKeys.CODEX_HOME])),
      cursor = agentProfiles(home, "cursor", "chats", listOf(variables[AgentHomeEnvironmentKeys.CURSOR_CONFIG_DIR], xdgCursor)),
    )
  }

  private fun <T> readSafely(category: String, read: () -> List<T>): List<T> = try {
    read()
  } catch (_: IOException) {
    diagnostics.record(category)
    emptyList()
  }
}
