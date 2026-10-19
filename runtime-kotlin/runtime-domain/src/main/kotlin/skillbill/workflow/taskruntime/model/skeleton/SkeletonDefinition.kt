package skillbill.workflow.taskruntime.model.skeleton

import skillbill.error.featuretask.InvalidPhaseStrategyCompositionError
import skillbill.error.featuretask.InvalidSkeletonDefinitionError
import skillbill.error.featuretask.UnknownSkeletonDefinitionError
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePhaseIds

enum class SkeletonRunStateKind(val wireValue: String) {
  DURABLE("durable"),
  IN_MEMORY("in_memory"),
}

enum class PhaseRunHarness {
  NONE,
  DEVICE_QA,
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
  val preparedExecutionOf: String? = null,
  val runHarness: PhaseRunHarness = PhaseRunHarness.NONE,
  val loopOnlyStepIds: Set<String> = emptySet(),
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
    require(loopOnlyStepIds.all(stepIds::contains))
  }

  companion object {
    val LEGACY_FEATURE_RUN_SLOTS_REVISION_2: List<PhaseSlot> =
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

    val FEATURE_RUN_SLOTS: List<PhaseSlot> =
      LEGACY_FEATURE_RUN_SLOTS_REVISION_2.toMutableList().apply {
        add(indexOf(PhaseSlot.CODE_REVIEW), PhaseSlot.QA)
      }

    val STANDALONE_REVISION_2: SkeletonDefinition =
      SkeletonDefinition("standalone", LEGACY_FEATURE_RUN_SLOTS_REVISION_2, semanticRevision = 2)
    val GOAL_CHILD_REVISION_2: SkeletonDefinition =
      SkeletonDefinition(
        "goal-child",
        LEGACY_FEATURE_RUN_SLOTS_REVISION_2.filter { it != PhaseSlot.PULL_REQUEST && it != PhaseSlot.MONITOR },
        semanticRevision = 2,
      )
    val STANDALONE: SkeletonDefinition = SkeletonDefinition("standalone", FEATURE_RUN_SLOTS, semanticRevision = 3)
    val GOAL_CHILD: SkeletonDefinition =
      SkeletonDefinition(
        "goal-child",
        FEATURE_RUN_SLOTS.filter { it != PhaseSlot.PULL_REQUEST && it != PhaseSlot.MONITOR },
        semanticRevision = 3,
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
    val QA: SkeletonDefinition =
      SkeletonDefinition(
        "qa",
        listOf(PhaseSlot.QA),
        SkeletonRunStateKind.IN_MEMORY,
        standaloneInvocable = true,
        stepIds = listOf(FeatureTaskRuntimePhaseIds.QA),
        runHarness = PhaseRunHarness.DEVICE_QA,
      )
    val GOAL_QA: SkeletonDefinition =
      SkeletonDefinition(
        "goal-qa",
        listOf(PhaseSlot.QA, PhaseSlot.QUALITY_GATE),
        runStateKind = SkeletonRunStateKind.IN_MEMORY,
        stepIds =
          listOf(
            FeatureTaskRuntimePhaseIds.QA,
            FeatureTaskRuntimePhaseIds.QA_FIX,
            FeatureTaskRuntimePhaseIds.VALIDATE,
          ),
        loopOnlyStepIds = setOf(FeatureTaskRuntimePhaseIds.VALIDATE),
      )
    val PLAN: SkeletonDefinition =
      SkeletonDefinition(
        "plan",
        listOf(PhaseSlot.INTAKE, PhaseSlot.PREPLAN, PhaseSlot.PLAN),
        SkeletonRunStateKind.DURABLE,
        PhaseIntakeRequirement.ISSUE_KEY,
        semanticRevision = 2,
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
    val STANDALONE_MONITOR: SkeletonDefinition =
      SkeletonDefinition(
        "standalone-monitor",
        listOf(PhaseSlot.COMMIT_PUSH, PhaseSlot.MONITOR),
        SkeletonRunStateKind.IN_MEMORY,
        standaloneInvocable = false,
        preparedExecutionOf = MONITOR.id,
      )
    val entries: List<SkeletonDefinition>
      get() =
        listOf(
          STANDALONE_REVISION_2,
          GOAL_CHILD_REVISION_2,
          STANDALONE,
          GOAL_CHILD,
          REVIEW,
          VALIDATION,
          QA,
          GOAL_QA,
          PLAN,
          PR,
          MONITOR,
          STANDALONE_MONITOR,
        )

    fun admittedForRun(goalContinuation: Boolean): Set<SkeletonDefinition> =
      if (goalContinuation) setOf(GOAL_CHILD) else setOf(PLAN)

    fun preparedExecutionFor(publicId: String): SkeletonDefinition? =
      entries.singleOrNull { it.preparedExecutionOf == publicId }

    fun runHarnessOf(publicId: String): PhaseRunHarness =
      entries.firstOrNull { it.id == publicId }?.runHarness ?: PhaseRunHarness.NONE

    fun byId(id: String): SkeletonDefinition =
      entries.filter { it.id == id }.maxByOrNull(SkeletonDefinition::semanticRevision)
        ?: throw UnknownSkeletonDefinitionError(id, entries.map(SkeletonDefinition::id))
  }
}
