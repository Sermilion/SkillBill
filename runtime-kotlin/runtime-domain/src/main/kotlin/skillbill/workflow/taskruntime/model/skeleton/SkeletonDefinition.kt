package skillbill.workflow.taskruntime.model.skeleton

import skillbill.error.featuretask.InvalidSkeletonDefinitionError
import skillbill.error.featuretask.UnknownSkeletonDefinitionError

enum class SkeletonRunStateKind(val wireValue: String) {
  DURABLE("durable"),
  IN_MEMORY("in_memory"),
  GOAL_PLANNING("goal_planning"),
}

enum class PhaseIntakeRequirement(val wireValue: String) {
  OPTIONAL("optional"),
  ISSUE_KEY("issue_key"),
}

data class SkeletonDefinition(
  val id: String,
  val slots: List<PhaseSlot>,
  val runStateKind: SkeletonRunStateKind = SkeletonRunStateKind.DURABLE,
  val intake: PhaseIntakeRequirement = PhaseIntakeRequirement.OPTIONAL,
) {
  init {
    val canonicalOrder = slots.zipWithNext().all { (previous, next) -> previous.ordinal < next.ordinal }
    if (slots.isEmpty() || !canonicalOrder) {
      throw InvalidSkeletonDefinitionError(id, slots.map(PhaseSlot::wireValue))
    }
  }

  val stepIds: List<String> get() = slots.flatMap(PhaseSlot::steps)

  companion object {
    val STANDALONE: SkeletonDefinition = SkeletonDefinition("standalone", PhaseSlot.entries)
    val GOAL_CHILD: SkeletonDefinition =
      SkeletonDefinition("goal-child", PhaseSlot.entries.filter { it != PhaseSlot.PULL_REQUEST })
    val REVIEW: SkeletonDefinition =
      SkeletonDefinition("review", listOf(PhaseSlot.CODE_REVIEW), SkeletonRunStateKind.IN_MEMORY)
    val VALIDATION: SkeletonDefinition =
      SkeletonDefinition("validation", listOf(PhaseSlot.QUALITY_GATE), SkeletonRunStateKind.IN_MEMORY)
    val PLAN: SkeletonDefinition =
      SkeletonDefinition(
        "plan",
        listOf(PhaseSlot.PREPLAN, PhaseSlot.PLAN),
        SkeletonRunStateKind.IN_MEMORY,
        PhaseIntakeRequirement.ISSUE_KEY,
      )
    val PR: SkeletonDefinition =
      SkeletonDefinition("pr", listOf(PhaseSlot.COMMIT_PUSH, PhaseSlot.PULL_REQUEST), SkeletonRunStateKind.IN_MEMORY)
    val GOAL_PLANNING: SkeletonDefinition =
      SkeletonDefinition(
        "goal-planning",
        listOf(PhaseSlot.PREPLAN, PhaseSlot.PLAN),
        SkeletonRunStateKind.GOAL_PLANNING,
      )

    val entries: List<SkeletonDefinition>
      get() = listOf(STANDALONE, GOAL_CHILD, REVIEW, VALIDATION, PLAN, PR, GOAL_PLANNING)

    fun forRun(goalContinuation: Boolean): SkeletonDefinition = if (goalContinuation) GOAL_CHILD else STANDALONE

    fun byId(id: String): SkeletonDefinition =
      entries.firstOrNull { it.id == id }
        ?: throw UnknownSkeletonDefinitionError(id, entries.map(SkeletonDefinition::id))
  }
}
