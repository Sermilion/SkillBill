package skillbill.error.projectchecks

import skillbill.error.core.RuntimeFailureCode

enum class ProjectCheckConfigFailureCode : RuntimeFailureCode {
  PROJECT_CHECK_CONFIG_INVALID,
  PROJECT_CHECK_CONFIG_UNSUPPORTED_VERSION,
  PROJECT_CHECK_DISCOVERY_INVALID,
  PROJECT_CHECK_DISCOVERY_UNSUPPORTED_VERSION,
}
