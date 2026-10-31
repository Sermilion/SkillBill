package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.WorkKind

internal fun chatRequest(
  agent: ChatAgent = ChatAgent("claude", null, null),
  firstTurn: Boolean = true,
  mode: ChatPermissionMode = ChatPermissionMode.Act,
) = ChatTurnRequest(
  sessionId = "session-1",
  firstTurn = firstTurn,
  agent = agent,
  scope = ChatScope("wf-1", WorkKind.Goal, "SKILL-1", "Title", "/work/skill-bill"),
  mode = mode,
  prompt = "operator prompt",
)
