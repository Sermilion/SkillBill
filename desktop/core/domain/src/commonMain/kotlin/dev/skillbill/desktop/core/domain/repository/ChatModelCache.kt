package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.domain.model.ChatModel

/** The last model list each agent profile reported, so a picker can render it while discovery runs again. */
interface ChatModelCache {
  suspend fun read(agentId: String, profile: String?): List<ChatModel>?

  suspend fun write(agentId: String, profile: String?, models: List<ChatModel>)
}
