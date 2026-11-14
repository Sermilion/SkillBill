package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.DatabaseDispatcher
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.data.session.ComponentSessionGraphFactory
import dev.skillbill.desktop.core.data.session.DatabaseSessionComponent
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.data.session.LaunchReservations
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.testing.FakeAppSettingsRepository
import kotlinx.coroutines.CoroutineScope

internal fun chatRequest(
  agent: ChatAgent = ChatAgent("claude", null, null),
  firstTurn: Boolean = true,
  mode: ChatPermissionMode = ChatPermissionMode.Act,
) = ChatTurnRequest(
  sessionId = "session-1",
  firstTurn = firstTurn,
  agent = agent,
  scope = ChatScope.Execution("wf-1", WorkKind.Goal, "SKILL-1", "Title", "/work/skill-bill"),
  mode = mode,
  prompt = "operator prompt",
)

/** A session manager with no open database session; transports built on it refuse runtime-context checks. */
internal fun noSessionManager(scope: CoroutineScope, diagnostics: Diagnostics): DatabaseSessionManager =
  DatabaseSessionManager(
    ComponentSessionGraphFactory(object : DatabaseSessionComponent.Factory {
      override fun createDatabaseSessionComponent(): DatabaseSessionComponent = error("no open session in this test")
    }),
    FakeAppSettingsRepository(),
    DatabaseDispatcher(),
    diagnostics,
    LaunchReservations(),
    ApplicationCoroutineScope(scope),
  )
