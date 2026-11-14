package dev.skillbill.desktop.core.domain.model

sealed interface ChatStoreRead<out T> {
  data object Missing : ChatStoreRead<Nothing>
  data class Value<T>(val value: T) : ChatStoreRead<T>
  data class Failed(val failure: ChatStoreReadFailure) : ChatStoreRead<Nothing>
}

data class ChatStoreReadFailure(
  val storageIdentity: String,
  val sourcePath: String,
  val reason: String,
  val recovery: String,
)

data class ChatHistoryRead(
  val entries: List<ChatHistoryEntry>,
  val failures: List<ChatStoreReadFailure>,
)
