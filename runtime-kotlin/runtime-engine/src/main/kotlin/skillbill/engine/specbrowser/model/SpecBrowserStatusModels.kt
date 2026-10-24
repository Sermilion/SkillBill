package skillbill.engine.specbrowser.model

enum class SpecBrowserStatus {
  COMPLETE,
  IN_PROGRESS,
  RUNNING,
  PAUSING,
  PAUSED,
  PENDING,
  BLOCKED,
  CANCELED,
}

enum class SpecBrowserStatusGroup {
  ACTIVE,
  PENDING,
  DONE,
}
