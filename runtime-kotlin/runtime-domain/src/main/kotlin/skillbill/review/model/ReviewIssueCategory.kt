package skillbill.review.model

enum class ReviewIssueCategory(val wireValue: String) {
  BEHAVIOR_CORRECTNESS("behavior_correctness"),
  DATA_PERSISTENCE("data_persistence"),
  CONCURRENCY_LIFECYCLE("concurrency_lifecycle"),
  UX_ACCESSIBILITY("ux_accessibility"),
  TESTING_QUALITY_GATE("testing_quality_gate"),
  CODE_QUALITY("code_quality"),
  SECURITY_PRIVACY("security_privacy"),
  DOCS_CONTRACT("docs_contract"),
  OTHER("other"),
}
