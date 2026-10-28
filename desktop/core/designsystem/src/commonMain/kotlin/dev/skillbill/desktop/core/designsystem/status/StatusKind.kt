package dev.skillbill.desktop.core.designsystem.status

enum class StatusKind(val label: String) {
  Active("Active"),
  Planning("Planning"),
  Stalled("Stalled"),
  Paused("Paused"),
  Blocked("Blocked"),
  Failed("Failed"),
  Completed("Completed"),
  CompletedNoChange("Completed, no change"),
  Pending("Pending"),
  Skipped("Skipped"),
  Canceled("Canceled"),
}
