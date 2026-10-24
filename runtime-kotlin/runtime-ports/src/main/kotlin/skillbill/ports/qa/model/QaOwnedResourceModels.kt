package skillbill.ports.qa.model

private const val ENVIRONMENT_NAME_PREFIX = "sbqa-"
private const val ENVIRONMENT_NAME_MAX_LENGTH = 63
private val NON_ENVIRONMENT_CHARACTER = Regex("[^a-z0-9]")

enum class QaOwnedResourceKind(val wireValue: String) {
  COMPOSE_PROJECT("compose_project"),
  CONTAINER("container"),
  VOLUME("volume"),
  NETWORK("network"),
  PROCESS("process"),
  DIRECTORY("directory"),
  ;

  companion object {
    fun fromWire(value: String): QaOwnedResourceKind? = entries.firstOrNull { it.wireValue == value }
  }
}

data class QaOwnedResource(
  val kind: QaOwnedResourceKind,
  val identity: String,
)

sealed interface QaResourceRegistration {
  data object Registered : QaResourceRegistration

  data class Conflict(val ownerInvocationId: String) : QaResourceRegistration

  data class Rejected(val reason: String) : QaResourceRegistration
}

sealed interface QaResourceReleaseOutcome {
  data class Released(val resource: QaOwnedResource) : QaResourceReleaseOutcome

  data class NotHeld(val resource: QaOwnedResource) : QaResourceReleaseOutcome

  data class Failed(
    val resource: QaOwnedResource,
    val detail: String,
  ) : QaResourceReleaseOutcome
}

fun qaEnvironmentName(invocationId: String): String =
  (ENVIRONMENT_NAME_PREFIX + invocationId.lowercase().replace(NON_ENVIRONMENT_CHARACTER, "-"))
    .take(ENVIRONMENT_NAME_MAX_LENGTH)
