package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.PlanRunReport
import kotlin.test.Test
import kotlin.test.assertEquals

class SpecBundleOrderTest {
  @Test
  fun `the manifest and spec md lead, then subtask specs follow in numeric order`() {
    val bundle = "/repo/.skill-bill/specs/SKILL-447"
    val report = PlanRunReport(
      featureName = "SKILL-447",
      parentSpecPath = "$bundle/spec.md",
      subtasks = emptyList(),
      bundleFiles = listOf(
        "$bundle/spec_subtask_10.md",
        "$bundle/traceability.md",
        "$bundle/spec_subtask_2.md",
        "$bundle/spec.md",
        "$bundle/spec_subtask_1.md",
        "$bundle/decomposition-manifest.yaml",
      ),
    )

    assertEquals(
      listOf("decomposition-manifest.yaml", "spec.md", "spec_subtask_1.md", "spec_subtask_2.md", "spec_subtask_10.md", "traceability.md"),
      specBundlePaths(report).map(::fileName),
    )
  }
}
