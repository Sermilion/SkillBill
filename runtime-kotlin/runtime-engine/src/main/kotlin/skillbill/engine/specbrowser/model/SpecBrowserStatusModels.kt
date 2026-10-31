package skillbill.engine.specbrowser.model

public enum class SpecBrowserStatus {
  COMPLETE,
  IN_PROGRESS,
  RUNNING,
  PAUSING,
  PAUSED,
  PENDING,
  BLOCKED,
  CANCELED,
}

public enum class SpecBrowserStatusGroup {
  ACTIVE,
  PENDING,
  DONE,
}
