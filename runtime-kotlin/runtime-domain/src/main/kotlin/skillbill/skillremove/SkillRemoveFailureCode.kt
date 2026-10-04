package skillbill.skillremove

import skillbill.error.core.RuntimeFailureCode

enum class SkillRemoveFailureCode : RuntimeFailureCode {
  ROLLBACK_INCOMPLETE,
}
