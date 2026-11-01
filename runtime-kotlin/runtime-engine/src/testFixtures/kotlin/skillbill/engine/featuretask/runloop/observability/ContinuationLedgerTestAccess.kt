package skillbill.engine.featuretask.runloop.observability

fun FeatureTaskRuntimeContinuationKind.ledgerDetailForTest(): String =
  FeatureTaskRuntimeContinuationKind.LEDGER_DETAIL_PREFIX + wireValue
