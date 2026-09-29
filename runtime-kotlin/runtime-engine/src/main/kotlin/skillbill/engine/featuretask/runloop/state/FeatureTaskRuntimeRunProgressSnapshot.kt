package skillbill.engine.featuretask.runloop.state

internal fun detachedProgressObservations(
  captured: FeatureTaskRuntimeRunState,
): FeatureTaskRuntimeRunLoopProgressObservations = DetachedProgressObservations(captured)

private class DetachedProgressObservations(
  private val captured: FeatureTaskRuntimeRunState,
) : FeatureTaskRuntimeRunLoopProgressObservations by captured
