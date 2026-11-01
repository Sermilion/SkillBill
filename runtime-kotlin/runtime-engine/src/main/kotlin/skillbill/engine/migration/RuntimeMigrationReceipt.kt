package skillbill.engine.migration

internal data class RuntimeMigrationReceipt(
  val sourceVersion: String,
  val targetVersion: String,
  val result: Result,
) {
  enum class Result {
    CURRENT,
    CONVERTED,
  }
}
