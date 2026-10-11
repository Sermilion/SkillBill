package dev.skillbill.tray.presentation

import dev.skillbill.tray.application.DashboardState
import dev.skillbill.tray.domain.AppSettings
import dev.skillbill.tray.infrastructure.cli.WorkPayloadDecoder
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class TrayHoverPresentationTest {
  @Test
  fun `hover limits each category to four and escapes repository markup`() {
    val items = (0..4).flatMap { index ->
      listOf(item("GOAL-$index", "feature-goal"), item("PHASE-$index", "feature-task-runtime"))
    }.joinToString(",")
    val board = WorkPayloadDecoder.board("""{"contract_version":"1.1","page_size":100,"next_cursor":null,"items":[$items],"observed_at":"2026-10-11T10:00:00Z","finished_within":"24h","diagnostics":[]}""")
    val text = presentTrayHover(DashboardState(AppSettings(), board = board))
    assertContains(text, "GOAL-3")
    assertContains(text, "PHASE-3")
    assertFalse("GOAL-4" in text)
    assertFalse("PHASE-4" in text)
    assertContains(text, "&lt;repo&gt;")
    assertFalse("<repo>" in text)
    assertContains(text, "#3FB950")
  }

  private fun item(id: String, family: String): String =
    """{"repository_root":"/work/<repo>","snapshot":{"contract_version":"0.3","repository_identity":"repo","workflow_id":"$id","workflow_family":"$family","execution_scope":"workflow","lifecycle_state":"active","freshness":"fresh","issue_key":"$id","summary":"work","current_step":{"id":"implement","label":"Implement"},"updated_at":"2026-10-11T10:00:00Z"}}"""
}
