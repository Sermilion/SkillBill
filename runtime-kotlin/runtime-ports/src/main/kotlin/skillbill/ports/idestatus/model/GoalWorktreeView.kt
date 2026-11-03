package skillbill.ports.idestatus.model

data class GoalWorktreeView(
  val path: String,
  val state: GoalWorktreeState,
  val retentionReason: String? = null,
  val retentionDetail: String? = null,
)

enum class GoalWorktreeState { ACTIVE, RETAINED, REMOVED }
