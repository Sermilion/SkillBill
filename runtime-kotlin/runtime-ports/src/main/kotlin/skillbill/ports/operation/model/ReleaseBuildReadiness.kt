package skillbill.ports.operation.model

sealed interface ReleaseBuildReadiness {
  data class Passed(val branch: String) : ReleaseBuildReadiness

  data class NotReady(val reason: String) : ReleaseBuildReadiness

  data class Unavailable(val reason: String) : ReleaseBuildReadiness
}
