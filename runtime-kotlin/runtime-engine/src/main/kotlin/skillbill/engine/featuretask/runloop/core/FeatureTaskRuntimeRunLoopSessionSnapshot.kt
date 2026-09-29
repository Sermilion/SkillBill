package skillbill.engine.featuretask.runloop.core

internal fun detachedSessionObservations(
  captured: FeatureTaskRuntimeRunLoopSession,
): FeatureTaskRuntimeRunLoopSessionObservations = DetachedSessionObservations(captured)

private class DetachedSessionObservations(
  private val captured: FeatureTaskRuntimeRunLoopSession,
) : FeatureTaskRuntimeRunLoopSessionObservations by captured
