package skillbill.engine.goalrunner.plan

import me.tatarka.inject.annotations.Inject

@Inject
public class PlanBundleServices(
  internal val reopening: PlanSubtasksReopening,
  internal val reports: StandalonePlanReportBuilder,
)
