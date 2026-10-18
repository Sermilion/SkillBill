package skillbill.ports.db.model

sealed interface FencedTransactionResult<out T> {
  data class Committed<T>(val value: T) : FencedTransactionResult<T>

  data object ChangedTarget : FencedTransactionResult<Nothing>
}

sealed interface OwningTransactionResult<out T> {
  data class Committed<T>(val value: T) : OwningTransactionResult<T>

  data class Refused(val reason: Refusal) : OwningTransactionResult<Nothing>

  enum class Refusal {
    ACTIVE_SESSION,
    UNSUPPORTED,
  }
}
