package skillbill.engine.featuretask.runloop.qualitygate

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.runloop.settlement.ValidationCommandLaunchPreparer

@Inject
public class RuntimeQualityGateLaunch(
  cycles: RuntimeQualityGateCycles,
  validationCommandLaunch: ValidationCommandLaunchPreparer,
) {
  internal val cycles: RuntimeQualityGateCycles = cycles
  internal val validationCommandLaunch: ValidationCommandLaunchPreparer = validationCommandLaunch
}
