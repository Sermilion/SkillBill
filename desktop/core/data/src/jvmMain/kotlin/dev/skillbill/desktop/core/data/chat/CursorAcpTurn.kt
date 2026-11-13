package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import kotlinx.coroutines.flow.Flow
import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.infrastructure.launcher.process.cursor.AcpProvider

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
): Flow<ChatEvent> = acpTurn(
  live,
  authority,
  store,
  diagnostics,
  launchFactory = { dependency -> acpTurnLaunch(AcpProvider.Cursor, shell, environment, request, saved, prompt, dependency) },
  provider = AcpProvider.Cursor,
  profileIdentity = environment[AgentHomeEnvironmentKeys.CURSOR_CONFIG_DIR] ?: environment["HOME"].orEmpty(),
  request = request,
  saved = saved,
  binding = binding,
)
