package skillbill.workflow.taskruntime.model.review

enum class FeatureTaskRuntimeReviewSeverity(val wireValue: String) {
  BLOCKER("blocker"),
  MAJOR("major"),
  MINOR("minor"),
  NIT("nit"),
  ;

  val blocksAdvance: Boolean
    get() = this == BLOCKER || this == MAJOR

  val requiresRemediation: Boolean
    get() = this == BLOCKER || this == MAJOR

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeReviewSeverity =
      entries.firstOrNull { it.wireValue == value.trim().lowercase() }
        ?: throw IllegalArgumentException(
          "Unknown feature-task-runtime review severity '$value'. " +
            "Allowed: ${entries.joinToString { it.wireValue }}.",
        )
  }
}
