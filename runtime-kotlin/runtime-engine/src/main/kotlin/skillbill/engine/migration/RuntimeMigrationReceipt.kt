package skillbill.engine.migration

data class RuntimeMigrationReceipt(
  val sourceVersion: String,
  val targetVersion: String,
  val result: Result,
) {
  enum class Result {
    CURRENT,
    CONVERTED,
  }
}
