package skillbill.workflow.taskruntime.model.skeleton

import skillbill.error.featuretask.InvalidPhaseStrategyCompositionError
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
  val semanticRevision: Int = 1,
  val stepIds: List<String> = slots.flatMap(PhaseSlot::steps),
  val standaloneInvocable: Boolean = false,
) {
  init {
    val canonicalOrder = slots.zipWithNext().all { (previous, next) -> previous.ordinal < next.ordinal }
    if (slots.isEmpty() || !canonicalOrder) {
      throw InvalidSkeletonDefinitionError(id, slots.map(PhaseSlot::wireValue))
    }
    require(semanticRevision > 0)
    val available = slots.flatMap(PhaseSlot::steps)
    if (stepIds.isEmpty() || stepIds != available.filter { it in stepIds }) {
      throw InvalidPhaseStrategyCompositionError("definition $id has duplicate, reordered, or unowned steps")
    }
  }

  fun requiresSpecBundle(goalContinuation: Boolean): Boolean = slots.last() == PhaseSlot.PLAN && !goalContinuation

  companion object {
    val FEATURE_RUN_SLOTS: List<PhaseSlot> =
      listOf(
        PhaseSlot.PREPLAN,
        PhaseSlot.PLAN,
        PhaseSlot.IMPLEMENTATION,
        PhaseSlot.AUDIT,
        PhaseSlot.CODE_REVIEW,
        PhaseSlot.QUALITY_GATE,
        PhaseSlot.WRITE_HISTORY,
        PhaseSlot.COMMIT_PUSH,
        PhaseSlot.PULL_REQUEST,
        PhaseSlot.MONITOR,
      )

    val STANDALONE: SkeletonDefinition = SkeletonDefinition("standalone", FEATURE_RUN_SLOTS, semanticRevision = 2)
    val GOAL_CHILD: SkeletonDefinition =
      SkeletonDefinition(
        "goal-child",
        FEATURE_RUN_SLOTS.filter { it != PhaseSlot.PULL_REQUEST && it != PhaseSlot.MONITOR },
        semanticRevision = 2,
      )
    val REVIEW: SkeletonDefinition =
      SkeletonDefinition(
        "review",
        listOf(PhaseSlot.STANDALONE_REVIEW),
        SkeletonRunStateKind.IN_MEMORY,
        standaloneInvocable = true,
      )
    val VALIDATION: SkeletonDefinition =
      SkeletonDefinition(
        "validation",
        listOf(PhaseSlot.QUALITY_GATE),
        SkeletonRunStateKind.IN_MEMORY,
        standaloneInvocable = true,
      )
    val PLAN: SkeletonDefinition =
      SkeletonDefinition(
        "plan",
        listOf(PhaseSlot.PREPLAN, PhaseSlot.PLAN),
        SkeletonRunStateKind.DURABLE,
        PhaseIntakeRequirement.ISSUE_KEY,
        standaloneInvocable = true,
      )
    val PR: SkeletonDefinition =
      SkeletonDefinition(
        "pr",
        listOf(PhaseSlot.COMMIT_PUSH, PhaseSlot.PULL_REQUEST, PhaseSlot.MONITOR),
        SkeletonRunStateKind.IN_MEMORY,
        standaloneInvocable = true,
      )
    val MONITOR: SkeletonDefinition =
      SkeletonDefinition(
        "monitor",
        listOf(PhaseSlot.COMMIT_PUSH, PhaseSlot.MONITOR),
        SkeletonRunStateKind.IN_MEMORY,
        standaloneInvocable = true,
      )
    val GOAL_PLANNING: SkeletonDefinition =
      SkeletonDefinition(
        "goal-planning",
        listOf(PhaseSlot.PREPLAN, PhaseSlot.PLAN),
        SkeletonRunStateKind.GOAL_PLANNING,
      )

    val entries: List<SkeletonDefinition>
      get() = listOf(STANDALONE, GOAL_CHILD, REVIEW, VALIDATION, PLAN, PR, MONITOR, GOAL_PLANNING)

    fun forRun(goalContinuation: Boolean): SkeletonDefinition = if (goalContinuation) GOAL_CHILD else STANDALONE

    fun admittedForRun(goalContinuation: Boolean): Set<SkeletonDefinition> =
      if (goalContinuation) setOf(GOAL_CHILD) else setOf(STANDALONE, PLAN)

    fun byId(id: String): SkeletonDefinition =
      entries.firstOrNull { it.id == id }
        ?: throw UnknownSkeletonDefinitionError(id, entries.map(SkeletonDefinition::id))
  }
}
