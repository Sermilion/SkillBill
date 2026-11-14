package dev.skillbill.desktop.core.domain.model

data class ChatModel(val id: String, val label: String)

sealed interface ChatModelsRead {
  data class Loaded(val models: List<ChatModel>) : ChatModelsRead

  /** A stored list shown while discovery refreshes; [refreshFailure] is set when that refresh failed. */
  data class Cached(val models: List<ChatModel>, val refreshFailure: String? = null) : ChatModelsRead

  data class Failed(val message: String) : ChatModelsRead
}
