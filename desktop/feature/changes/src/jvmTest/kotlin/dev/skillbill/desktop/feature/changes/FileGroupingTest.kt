package dev.skillbill.desktop.feature.changes

import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals

class FileGroupingTest {
  @Test
  fun `groups paths by source root and parent directory`() {
    val files = listOf(
      ChangedFile("README.md", null, ChangeStatus.Modified, 1, 0),
      ChangedFile("desktop/agent/decisions.md", null, ChangeStatus.Modified, 1, 0),
      ChangedFile("desktop/feature/x/src/main/A.kt", null, ChangeStatus.Modified, 1, 0),
      ChangedFile("runtime-kotlin/runtime-infra/workflow/src/main/B.kt", null, ChangeStatus.Modified, 1, 0),
    )

    assertEquals(listOf("(root)", "desktop/agent", "desktop/feature/x", "runtime-kotlin/runtime-infra/workflow"), groupFiles(files, "") { 0 }.map { it.module })
  }

  @Test
  fun `the title names the issue key once`() {
    val item = workItem("G").copy(issueKey = "SKILL-455", title = "[SKILL-455] Changes window")

    assertEquals("Changes · SKILL-455 · Changes window", changesWindowTitle(item))
  }
}
