package skillbill.engine.featuretask.runloop.core

internal fun detachedSessionObservations(
  captured: FeatureTaskRuntimeRunLoopSession,
): FeatureTaskRuntimeRunSessionObservations = DetachedSessionObservations(captured)

private class DetachedSessionObservations(
  private val captured: FeatureTaskRuntimeRunLoopSession,
) : FeatureTaskRuntimeRunSessionObservations by captured
