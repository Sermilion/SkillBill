package skillbill.goalrunner.model

data class GoalPlanningCheckoutRecovery(
  val generation: String,
  val previousGitIdentity: String,
  val state: State,
) {
  fun permits(
    nextState: State,
    replacementGitIdentity: String?,
  ): Boolean =
    when (nextState) {
      State.FENCED -> false
      State.PROVEN -> state == State.FENCED
      State.CONSUMED ->
        !replacementGitIdentity.isNullOrBlank() &&
          (state == State.PROVEN || (state == State.FENCED && replacementGitIdentity == previousGitIdentity))
    }

  enum class State(val wireValue: String) {
    FENCED("fenced"),
    PROVEN("proven"),
    CONSUMED("consumed"),
    ;

    companion object {
      fun fromWire(value: String): State? = entries.singleOrNull { it.wireValue == value }
    }
  }
}
