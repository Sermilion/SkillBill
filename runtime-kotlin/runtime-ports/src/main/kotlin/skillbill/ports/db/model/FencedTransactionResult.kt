package skillbill.ports.db.model

sealed interface FencedTransactionResult<out T> {
  data class Committed<T>(val value: T) : FencedTransactionResult<T>

  data object ChangedTarget : FencedTransactionResult<Nothing>
}
