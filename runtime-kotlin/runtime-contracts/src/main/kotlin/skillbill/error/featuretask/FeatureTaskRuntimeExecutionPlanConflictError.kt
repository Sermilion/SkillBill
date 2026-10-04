package skillbill.error.featuretask

import skillbill.error.core.ShellContentContractException

class FeatureTaskRuntimeExecutionPlanConflictError :
  ShellContentContractException(
    "The immutable execution plan cannot be replaced, removed, or adopted after workflow creation.",
  )
