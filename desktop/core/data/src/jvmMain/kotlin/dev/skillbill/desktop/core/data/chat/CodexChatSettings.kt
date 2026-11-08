package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import skillbill.infrastructure.launcher.process.codex.CodexThreadSettings

/** Uses the same native bypass settings for new and resumed operator chats. */
internal fun codexChatSettings(command: ChatCommand, request: ChatTurnRequest): CodexThreadSettings {
  return CodexThreadSettings(
    cwd = command.workingDirectory,
    model = request.agent.model,
    effort = request.agent.effort,
  )
}
