package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.data.session.DatabaseSessionComponent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.infrastructure.launcher.process.cursor.AcpProvider
import skillbill.infrastructure.launcher.process.cursor.AcpStdioMcpServer

internal fun cursorAcpTurn(
  live: LiveChatProcessLauncher,
  authority: ChatApprovalAuthority,
  store: ChatStore,
  diagnostics: Diagnostics,
  shell: String?,
  environment: Map<String, String>,
  request: ChatTurnRequest,
  saved: String?,
  binding: CursorSessionBinding?,
  prompt: String,
  provider: AcpProvider = AcpProvider.Cursor,
  openCodeBinding: OpenCodeSessionBinding? = null,
  mcpServers: List<AcpStdioMcpServer> = emptyList(),
  expectedDatabaseSession: DatabaseSessionComponent? = null,
  stopRequested: CompletableDeferred<String>? = null,
  onDisposition: (ChatEvent) -> Unit = {},
): Flow<ChatEvent> = acpTurn(
  live,
  authority,
  store,
  diagnostics,
  launchFactory = { dependency ->
    val (command, launch) = acpTurnLaunch(
      provider, shell, environment, request, saved, prompt, dependency,
      openCodeBinding?.workingDirectory ?: request.scope.repositoryRoot,
    )
    command to launch.copy(mcpServers = mcpServers)
  },
  profileIdentity = when (provider) {
    AcpProvider.OpenCode -> openCodeBinding?.profileIdentity.orEmpty()
    AcpProvider.Cursor -> environment[AgentHomeEnvironmentKeys.CURSOR_CONFIG_DIR] ?: environment["HOME"].orEmpty()
    else -> environment["HOME"].orEmpty()
  },
  request = request,
  saved = saved,
  binding = binding,
  provider = provider,
  openCodeBinding = openCodeBinding,
  expectedDatabaseSession = expectedDatabaseSession,
  stopRequested = stopRequested,
  onDisposition = onDisposition,
)
