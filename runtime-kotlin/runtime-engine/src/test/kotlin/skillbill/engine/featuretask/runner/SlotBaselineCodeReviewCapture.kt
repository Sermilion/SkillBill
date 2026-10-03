package skillbill.engine.featuretask.runner

import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.engine.featuretask.model.review.ReviewInvocation
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

internal object SlotBaselineCodeReviewCapture {
  fun encodedFiles(): Map<String, String> {
    val inline = captureMode(CodeReviewExecutionMode.INLINE, "inln")
    val delegated = captureMode(CodeReviewExecutionMode.DELEGATED, "dlgt")
    return mapOf(
      SlotBaselinePaths.INLINE_OUTPUT to inline.output,
      SlotBaselinePaths.DELEGATED_OUTPUT to delegated.output,
      SlotBaselinePaths.REVIEW_RUNS_INLINE to inline.reviewRuns,
      SlotBaselinePaths.REVIEW_RUNS_DELEGATED to delegated.reviewRuns,
      SlotBaselinePaths.REVIEW_TELEMETRY_INLINE to inline.telemetry,
      SlotBaselinePaths.REVIEW_TELEMETRY_DELEGATED to delegated.telemetry,
    ).entries.associate { (fileName, value) ->
      "${SlotBaselinePaths.CODE_REVIEW}/$fileName" to SlotBaselineJson.encode(value)
    }
  }

  private fun captureMode(
    mode: CodeReviewExecutionMode,
    runSuffix: String,
  ): ReviewModeCapture =
    SlotBaselinePhaseRunHarness.use { harness ->
      val request =
        harness.request(SkeletonDefinition.REVIEW.id, mode).copy(
          reviewInvocation =
            ReviewInvocation(
              target = ReviewTarget.Scoped(ParallelReviewScope.BRANCH, "HEAD^", "HEAD"),
              reviewRunId = "rvw-20260602-120000-$runSuffix",
              reviewSessionId = "rvs-slot-baseline-$runSuffix",
            ),
        )
      val result = harness.reviewEntry(mode).run(request)
      val databasePath = harness.database.resolveDbPath()
      ReviewModeCapture(
        output = result.printedFields(),
        reviewRuns = SlotBaselineSqlite.tablesWithPrefix(databasePath, "review_"),
        telemetry = harness.outboxRows(),
      )
    }

  private data class ReviewModeCapture(
    val output: Map<String, Any?>,
    val reviewRuns: Map<String, List<Map<String, Any?>>>,
    val telemetry: List<Map<String, Any?>>,
  )
}
