package dev.skillbill.desktop.core.testing

import dev.skillbill.desktop.core.domain.model.ChatModel
import dev.skillbill.desktop.core.domain.repository.ChatModelCache

/** In-memory [ChatModelCache]; [stored] seeds reads and records writes. */
class FakeChatModelCache : ChatModelCache {
  val stored = mutableMapOf<Pair<String, String?>, List<ChatModel>>()
  var writes = 0

  override suspend fun read(agentId: String, profile: String?): List<ChatModel>? = stored[agentId to profile]

  override suspend fun write(agentId: String, profile: String?, models: List<ChatModel>) {
    writes++
    stored[agentId to profile] = models
  }
}
