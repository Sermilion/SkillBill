package skillbill.engine.goalrunner.plan

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.slot.intake.FeatureTaskRuntimePlanSeedRecorder

@Inject
public class PlanBundleServices(
  internal val reopening: PlanSubtasksReopening,
  internal val reports: StandalonePlanReportBuilder,
  internal val seedRecorder: FeatureTaskRuntimePlanSeedRecorder,
)
