package skillbill.goalrunner.model

data class GoalRunnerLaunchFacts(
  val timedOut: Boolean = false,
  val interrupted: Boolean = false,
  val spawnFailed: Boolean = false,
  val exitStatus: Int? = null,
  val liveness: GoalRunnerLivenessSnapshot? = null,
  val stderrExcerpt: String? = null,
  val launchRefusal: GoalRunnerLaunchRefusal? = null,
) {
  companion object {
    const val STDERR_EXCERPT_MAX_CHARS: Int = 3_000

    const val DIAGNOSTIC_CLASS_CONFIRMED_ALIVE_KILL = "supervisor_killed_confirmed_alive"
  }
}

data class GoalRunnerLaunchRefusal(
  val providerId: String,
  val phaseId: String,
  val reason: String,
)

enum class GoalRunnerLivenessState(
  val wireValue: String,
) {
  WORKING("working"),
  PROGRESSING("progressing"),
  IDLE("idle"),
  UNRESPONSIVE("unresponsive"),
  ;

  companion object {
    fun fromWire(value: String?): GoalRunnerLivenessState? =
      value?.trim()?.let { candidate -> entries.firstOrNull { it.wireValue == candidate } }
  }

  val armsIdleTimeout: Boolean
    get() = this == IDLE
}

data class GoalRunnerLivenessInputs(
  val processAlive: Boolean,
  val operationActive: Boolean,
  val operationExpectedLong: Boolean,
  val durableAdvanceWithinInterval: Boolean,
  val operationDeadlineOverrun: Boolean,
  val wallClockCapExceeded: Boolean,
)

data class GoalRunnerLivenessDecision(
  val state: GoalRunnerLivenessState,
  val armIdleTimeout: Boolean,
) {
  val disarmIdleTimeout: Boolean get() = !armIdleTimeout
}

enum class GoalRunnerProcessState(
  val wireValue: String,
) {
  UNKNOWN("unknown"),
  KILLED("killed"),
  EXITED("exited"),
  CONFIRMED_ALIVE("confirmed_alive"),
  PROGRESSING("progressing"),
  IDLE("idle"),
  ;

  companion object {
    fun fromWire(value: String?): GoalRunnerProcessState? =
      value?.trim()?.let { candidate -> entries.firstOrNull { it.wireValue == candidate } }
  }
}

enum class GoalRunnerContinuationMode(
  val wireValue: String,
) {
  KILLED_UNRESPONSIVE_CHILD("killed_unresponsive_child"),
  KILLED_BY_PARENT_INTERRUPT("killed_by_parent_interrupt"),
  CONTINUE_INLINE("continue_inline"),
  NONE("none"),
  ;

  companion object {
    fun fromWire(value: String?): GoalRunnerContinuationMode? =
      value?.trim()?.let { candidate -> entries.firstOrNull { it.wireValue == candidate } }
  }
}

object GoalRunnerLivenessClassifier {
  fun classify(inputs: GoalRunnerLivenessInputs): GoalRunnerLivenessDecision {
    val state =
      when {
        !inputs.processAlive -> GoalRunnerLivenessState.UNRESPONSIVE
        inputs.operationActive && inputs.operationDeadlineOverrun -> GoalRunnerLivenessState.UNRESPONSIVE
        inputs.wallClockCapExceeded -> GoalRunnerLivenessState.UNRESPONSIVE
        inputs.operationActive && inputs.operationExpectedLong -> GoalRunnerLivenessState.WORKING
        inputs.durableAdvanceWithinInterval -> GoalRunnerLivenessState.PROGRESSING
        else -> GoalRunnerLivenessState.IDLE
      }
    return GoalRunnerLivenessDecision(state = state, armIdleTimeout = state.armsIdleTimeout)
  }
}

data class GoalRunnerLivenessSnapshot(
  val phase: String,
  val reason: String,
  val processState: GoalRunnerProcessState,
  val workflowId: String? = null,
  val workflowStep: String? = null,
  val lastDurableProgressAt: String? = null,
  val lastDurableProgressLabel: String? = null,
  val lastWorkflowSnapshotAt: String? = null,
  val lastFileActivityAt: String? = null,
  val lastFileActivityLabel: String? = null,
  val lastOutputAt: String? = null,
  val livenessState: GoalRunnerLivenessState? = null,
  val aliveAtKill: Boolean = false,
)

data class GoalRunnerSupervisionEvent(
  val phase: String,
  val reason: String,
  val continuationMode: GoalRunnerContinuationMode,
  val processState: GoalRunnerProcessState,
  val workflowId: String?,
  val stepId: String?,
  val lastDurableProgress: String?,
  val lastWorkflowSnapshotAt: String?,
  val lastFileActivityAt: String?,
  val lastOutputAt: String?,
)
