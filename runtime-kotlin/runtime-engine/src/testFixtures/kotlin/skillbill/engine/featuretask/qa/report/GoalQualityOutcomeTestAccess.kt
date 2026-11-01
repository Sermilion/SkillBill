package skillbill.engine.featuretask.qa.report

fun FeatureTaskQaOutcomeRecorder.recordSettledForTest(
  workflowId: String,
  verdict: QaVerdict,
  reason: String?,
  qaFixAttempts: Int,
  tested: QaSettledRun,
) = recordSettled(workflowId, verdict, reason, qaFixAttempts, tested)
