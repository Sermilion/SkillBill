package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatHistoryEntry
import dev.skillbill.desktop.core.domain.model.ChatHistoryLine
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import kotlinx.coroutines.flow.first
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.File
import java.io.IOException
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

  internal suspend fun list(
    executionId: String,
    root: String,
    currentSessionId: String,
    agentFor: suspend (String) -> ChatAgent,
  ): List<ChatHistoryEntry> {
    val excluded = setOfNotNull(currentSessionId, store.agentSession(currentSessionId))
    val sessions = sessionsIn(root).filter { it.id !in excluded }.sortedByDescending { it.updatedAt }.take(LISTED_SESSIONS)
    sessions.forEach { listed[it.id] = it }
    val native = sessions.map { session ->
      val saved = store.savedFor(session.id)
      val agent = (saved?.agent ?: agentFor(session.agentId)).copy(profile = session.profile.path)
      ChatHistoryEntry(
        session.id, saved?.executionId, agent, session.updatedAt,
        saved?.title ?: session.title ?: "${session.agentId} session", saved?.lines.orEmpty(),
      )
    }
    val appOnly = store.savedChats(executionId).filter { it.agent.agentId !in sessionStoreAgents && it.sessionId !in excluded }
    return (native + appOnly).sortedByDescending { it.updatedAt }
  }

  internal fun open(entry: ChatHistoryEntry): List<ChatHistoryLine> {
    if (entry.agent.agentId in handleAgents) store.saveAgentSession(entry.sessionId, entry.sessionId)
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

  private suspend fun sessionsIn(root: String): List<AgentSession> {
    val home = File(host.userHome)
    val claudeSelected = settings.settings.first().claudeConfigDir
    val variables = host.variables
    val xdgCursor = variables["XDG_CONFIG_HOME"]?.takeIf(String::isNotBlank)?.let { File(it, "cursor").path }
    val claude = agentProfiles(home, "claude", "projects", listOf(claudeSelected, variables[AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR]))
    val codex = agentProfiles(home, "codex", "sessions", listOf(variables[AgentHomeEnvironmentKeys.CODEX_HOME]))
    val cursor = agentProfiles(home, "cursor", "chats", listOf(variables[CURSOR_CONFIG_DIR], xdgCursor))
    fun read(profiles: List<File>, sessions: (File, String) -> List<AgentSession>) =
      profiles.flatMap { profile -> readSafely("chat.session_store_unreadable") { sessions(profile, root) } }
    return read(claude, ::claudeSessions) + read(codex, ::codexSessions) + read(cursor, ::cursorSessions)
  }

  private fun <T> readSafely(category: String, read: () -> List<T>): List<T> = try {
    read()
  } catch (_: IOException) {
    diagnostics.record(category)
    emptyList()
  }
}
