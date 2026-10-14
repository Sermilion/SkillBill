package skillbill.ports.db.model

sealed interface FencedTransactionResult<out T> {
  data class Committed<T>(val value: T) : FencedTransactionResult<T>

  data object ChangedTarget : FencedTransactionResult<Nothing>
}

sealed interface OwningTransactionResult<out T> {
  data class Committed<T>(val value: T) : OwningTransactionResult<T>

  data object ActiveSession : OwningTransactionResult<Nothing>
}
