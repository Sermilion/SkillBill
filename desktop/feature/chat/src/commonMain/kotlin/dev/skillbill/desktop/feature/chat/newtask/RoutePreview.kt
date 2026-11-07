package dev.skillbill.desktop.feature.chat.newtask

/** A cosmetic guess at where typed text would go. It only drives the step line and its caption, and no start path reads it. */
internal enum class NewTaskRoutePreview(val step: String?) {
  Goal(null),
  Plan("plan"),
  Review("review"),
  Validate("validate"),
  Qa("qa"),
  Pr("pr"),
  Release("release"),
}

// Conversational openers only, so "Add a review screen" stays a goal.
private const val LEAD_IN = """^(?:(?:let'?s|please|can you|could you|do|run|start)\s+)*(?:(?:a|an|the)\s+)?"""

private val STANDALONE_ROUTES = listOf(
  Regex("""${LEAD_IN}plan\b""") to NewTaskRoutePreview.Plan,
  Regex("""${LEAD_IN}(code\s+)?review\b""") to NewTaskRoutePreview.Review,
  Regex("""${LEAD_IN}(validate|validation|checks)\b""") to NewTaskRoutePreview.Validate,
  Regex("""${LEAD_IN}qa\b""") to NewTaskRoutePreview.Qa,
  Regex("""${LEAD_IN}open (a )?pr\b""") to NewTaskRoutePreview.Pr,
  // Anchored at the end, so "Add a release button" and "publish release notes" stay goals.
  Regex("""${LEAD_IN}(?:(?:publish|cut|ship)\s+)?(?:(?:a|an|the|new)\s+)*(?:(?:patch|minor|major)\s+)?release(?:\s+(?:now|please|it|this|v?\d\S*))?[.!?]*$""") to NewTaskRoutePreview.Release,
  Regex("""${LEAD_IN}operation:release\b""") to NewTaskRoutePreview.Release,
)

internal fun previewRoute(text: String): NewTaskRoutePreview? {
  val normalized = text.trim().lowercase()
  if (normalized.isEmpty()) return null
  return STANDALONE_ROUTES.firstOrNull { (pattern, _) -> pattern.containsMatchIn(normalized) }?.second ?: NewTaskRoutePreview.Goal
}

internal fun NewTaskRoutePreview.lights(step: String): Boolean = this == NewTaskRoutePreview.Goal || step == this.step

internal fun routeCaption(preview: NewTaskRoutePreview?): String? = when (preview) {
  null -> null
  NewTaskRoutePreview.Goal -> "Looks like a goal run, all 7 steps"
  NewTaskRoutePreview.Release -> "Looks like a release, which no button starts"
  else -> "Looks like a standalone ${preview.step}, just this step"
}
