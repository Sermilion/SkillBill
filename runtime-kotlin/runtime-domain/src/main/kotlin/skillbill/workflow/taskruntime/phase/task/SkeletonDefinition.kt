package skillbill.workflow.taskruntime.phase.task

import skillbill.error.featuretask.InvalidSkeletonDefinitionError
import skillbill.error.featuretask.UnknownSkeletonDefinitionError
import skillbill.workflow.taskruntime.model.core.PhaseSlot
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration

enum class SkeletonRunStateKind(val wireValue: String) {
  DURABLE("durable"),
  IN_MEMORY("in_memory"),
}

enum class PhaseIntakeRequirement(val wireValue: String) {
  OPTIONAL("optional"),
  ISSUE_KEY("issue_key"),
  SPEC("spec"),
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

  fun declaration(): FeatureTaskRuntimeTransitionDeclaration = derive(stepIds, entryStepIds = emptySet())

  fun traversal(
    selectedStepIds: Set<String>,
    entryStepIds: Set<String>,
  ): FeatureTaskRuntimeTransitionDeclaration = derive(stepIds.filter { it in selectedStepIds }, entryStepIds)

  private fun derive(
    steps: List<String>,
    entryStepIds: Set<String>,
  ): FeatureTaskRuntimeTransitionDeclaration {
    val canonical = FeatureTaskRuntimePhaseWorkflowDefinition.transitions
    val present = steps.toSet()
    val loopOnly = canonical.loopOnlyPhaseIds.filter { it in present && it !in entryStepIds }.toSet()
    return FeatureTaskRuntimeTransitionDeclaration(
      forwardPhaseIds = steps,
      entryGates = canonical.entryGates.filter { it.phaseId in present && it.requiredPhaseId in present },
      backwardEdges =
        canonical.backwardEdges.filter { it.fromPhaseId in present && it.destinationPhaseId in present },
      loopOnlyPhaseIds = loopOnly,
      loopOnlySuccessors =
        canonical.loopOnlySuccessors.filter { (source, successor) -> source in loopOnly && successor in loopOnly },
    )
  }

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
    val IMPLEMENT: SkeletonDefinition =
      SkeletonDefinition(
        "implement",
        listOf(PhaseSlot.IMPLEMENTATION),
        SkeletonRunStateKind.IN_MEMORY,
        PhaseIntakeRequirement.SPEC,
      )
    val PR: SkeletonDefinition =
      SkeletonDefinition("pr", listOf(PhaseSlot.PULL_REQUEST), SkeletonRunStateKind.IN_MEMORY)

    val entries: List<SkeletonDefinition>
      get() = listOf(STANDALONE, GOAL_CHILD, REVIEW, VALIDATION, PLAN, IMPLEMENT, PR)

    fun forRun(goalContinuation: Boolean): SkeletonDefinition = if (goalContinuation) GOAL_CHILD else STANDALONE

    fun byId(id: String): SkeletonDefinition =
      entries.firstOrNull { it.id == id }
        ?: throw UnknownSkeletonDefinitionError(id, entries.map(SkeletonDefinition::id))
  }
}
