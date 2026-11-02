package dev.skillbill.desktop.feature.chat.newtask

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutePreviewTest {
  @Test
  fun `only a leading keyword picks a standalone route`() {
    val table = mapOf(
      "Plan the export" to NewTaskRoutePreview.Plan,
      "Review my current branch" to NewTaskRoutePreview.Review,
      "validate" to NewTaskRoutePreview.Validate,
      "Run the checks" to NewTaskRoutePreview.Validate,
      "run checks" to NewTaskRoutePreview.Validate,
      "QA my current branch" to NewTaskRoutePreview.Qa,
      "Open a PR" to NewTaskRoutePreview.Pr,
      "open pr" to NewTaskRoutePreview.Pr,
      "Add a review screen" to NewTaskRoutePreview.Goal,
      "planning poker timer" to NewTaskRoutePreview.Goal,
      "  " to null,
    )
    table.forEach { (text, expected) -> assertEquals(expected, previewRoute(text), text) }
  }

  @Test
  fun `a goal lights every step and a standalone route lights only its own`() {
    assertTrue(NEW_TASK_STEPS.all(NewTaskRoutePreview.Goal::lights))
    assertTrue(NewTaskRoutePreview.Review.lights("review"))
    assertFalse(NewTaskRoutePreview.Review.lights("plan"))
  }

  @Test
  fun `the caption words each route as a guess`() {
    val table = mapOf(
      null to "Every goal runs this path",
      NewTaskRoutePreview.Goal to "Looks like a goal run, all 7 steps",
      NewTaskRoutePreview.Plan to "Looks like a standalone plan, just this step",
      NewTaskRoutePreview.Review to "Looks like a standalone review, just this step",
      NewTaskRoutePreview.Validate to "Looks like a standalone validate, just this step",
      NewTaskRoutePreview.Qa to "Looks like a standalone qa, just this step",
      NewTaskRoutePreview.Pr to "Looks like a standalone pr, just this step",
    )
    table.forEach { (preview, caption) -> assertEquals(caption, routeCaption(preview)) }
  }
}
