package skillbill.workflow.taskruntime.model.handoff.task

internal const val FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT: Int = 128

internal const val FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT: Int = 512

data class FeatureTaskRuntimeHandoffProjectionBudget(
  val maxUtf8Bytes: Int,
  val maxCollectionItems: Int,
) {
  init {
    require(maxUtf8Bytes > 0) {
      "FeatureTaskRuntimeHandoffProjectionBudget.maxUtf8Bytes must be positive, was $maxUtf8Bytes."
    }
    require(maxCollectionItems > 0) {
      "FeatureTaskRuntimeHandoffProjectionBudget.maxCollectionItems must be positive, was $maxCollectionItems."
    }
  }

  companion object {
    val PHASE_RECEIPT: FeatureTaskRuntimeHandoffProjectionBudget =
      FeatureTaskRuntimeHandoffProjectionBudget(maxUtf8Bytes = 65_536, maxCollectionItems = 64)

    val PREPLAN_DIGEST_RECEIPT: FeatureTaskRuntimeHandoffProjectionBudget =
      FeatureTaskRuntimeHandoffProjectionBudget(maxUtf8Bytes = 196_608, maxCollectionItems = 64)

    val ADDON_CONTENT: FeatureTaskRuntimeHandoffProjectionBudget =
      FeatureTaskRuntimeHandoffProjectionBudget(maxUtf8Bytes = 98_304, maxCollectionItems = 16)

    val PLANNING_PROJECTION: FeatureTaskRuntimeHandoffProjectionBudget =
      FeatureTaskRuntimeHandoffProjectionBudget(
        maxUtf8Bytes = 196_608,
        maxCollectionItems =
          FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT +
            (IMPLEMENTATION_RECEIPT_ORDINARY_LIST_FIELDS * FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT) +
            IMPLEMENTATION_RECEIPT_SCALAR_FIELDS,
      )

    private const val IMPLEMENTATION_RECEIPT_ORDINARY_LIST_FIELDS: Int = 6

    private const val IMPLEMENTATION_RECEIPT_SCALAR_FIELDS: Int = 2
  }
}
