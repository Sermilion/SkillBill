package dev.skillbill.desktop.core.domain.settings

data class CriterionMarkKey(val workflowId: String, val criterionNumber: Int)

enum class CriterionMark(val wireValue: String) {
  NotInRepo("not_in_repo"),
  SpecQuestion("spec_question"),
}
