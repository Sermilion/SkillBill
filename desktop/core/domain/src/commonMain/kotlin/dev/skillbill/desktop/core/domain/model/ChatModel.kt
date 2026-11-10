package dev.skillbill.desktop.core.domain.model

data class ChatModel(val id: String, val label: String)

sealed interface ChatModelsRead {
  data class Loaded(val models: List<ChatModel>) : ChatModelsRead
  data class Failed(val message: String) : ChatModelsRead
}
