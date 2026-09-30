package skillbill.engine.featuretask.lifecycle.continuation

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts

fun isGoalContinuationRun(request: FeatureTaskRuntimeRunFacts): Boolean = request.goalContinuation != null
