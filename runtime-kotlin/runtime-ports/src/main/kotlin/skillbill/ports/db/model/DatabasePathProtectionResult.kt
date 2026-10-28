package skillbill.ports.db.model

sealed interface DatabasePathProtectionResult {
  data class Acquired(val protection: AutoCloseable) : DatabasePathProtectionResult

  data class Unavailable(val reason: String) : DatabasePathProtectionResult
}
