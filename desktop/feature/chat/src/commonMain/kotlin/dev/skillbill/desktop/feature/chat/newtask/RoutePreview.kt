package dev.skillbill.desktop.feature.chat.newtask

/** A cosmetic guess at where typed text would go. It only drives the step line and its caption, and no start path reads it. */
internal enum class NewTaskRoutePreview(val step: String?) {
  Goal(null),
  Plan("plan"),
  Review("review"),
  Validate("validate"),
  Qa("qa"),
  Pr("pr"),
}

private val STANDALONE_ROUTES = listOf(
  Regex("""^plan\b""") to NewTaskRoutePreview.Plan,
  Regex("""^review\b""") to NewTaskRoutePreview.Review,
  Regex("""^(validate|run (the )?checks)\b""") to NewTaskRoutePreview.Validate,
  Regex("""^qa\b""") to NewTaskRoutePreview.Qa,
  Regex("""^open (a )?pr\b""") to NewTaskRoutePreview.Pr,
)

internal fun previewRoute(text: String): NewTaskRoutePreview? {
  val normalized = text.trim().lowercase()
  if (normalized.isEmpty()) return null
  return STANDALONE_ROUTES.firstOrNull { (pattern, _) -> pattern.containsMatchIn(normalized) }?.second ?: NewTaskRoutePreview.Goal
}

internal fun NewTaskRoutePreview.lights(step: String): Boolean = this == NewTaskRoutePreview.Goal || step == this.step

internal fun routeCaption(preview: NewTaskRoutePreview?): String = when (preview) {
  null -> "Every goal runs this path"
  NewTaskRoutePreview.Goal -> "Looks like a goal run, all 7 steps"
  else -> "Looks like a standalone ${preview.step}, just this step"
}
