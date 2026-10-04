package skillbill.engine.featuretask.phase.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FeatureTaskRuntimePhaseSafetyPolicyTest {
  @Test
  fun `porcelain paths are normalized and rename uses destination`() {
    val paths =
      FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(
        """
         M src/Main.kt
        ?? .feature-specs/SKILL-124-new/spec.md
        R  old.txt -> new.txt
        """.trimIndent(),
      )

    assertEquals(
      listOf(".feature-specs/SKILL-124-new/spec.md", "new.txt", "src/Main.kt"),
      paths,
    )
  }

  @Test
  fun `runtime-private skill-bill paths are omitted from phase manifests`() {
    val paths =
      FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(
        """
         M src/Main.kt
        ?? .skill-bill/state.db
        ?? .skill-bill/run-evidence/wf/fp/evidence.json
        ?? .skill-bill/config.yaml
        """.trimIndent(),
      )

    assertEquals(
      listOf(".skill-bill/config.yaml", ".skill-bill/run-evidence/wf/fp/evidence.json", "src/Main.kt"),
      paths,
      "run-evidence reaches the manifest so run provenance, not the store prefix, decides ownership",
    )
  }

  @Test
  fun `pure deletes are omitted from changedPaths and collected as deletedPaths`() {
    val status =
      """
       M src/Kept.kt
       D src/Removed.kt
      D  src/IndexRemoved.kt
      ?? src/New.kt
      R  src/OldName.kt -> src/NewName.kt
      """.trimIndent()

    assertEquals(
      listOf("src/Kept.kt", "src/New.kt", "src/NewName.kt"),
      FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(status),
    )
    assertEquals(
      listOf("src/IndexRemoved.kt", "src/OldName.kt", "src/Removed.kt"),
      FeatureTaskRuntimePhaseSafetyPolicy.deletedPaths(status),
    )
  }

  @Test
  fun `package-move porcelain does not treat delete sources as introductions`() {
    val status =
      """
       D application/compile/model/compilation/ActivationReason.kt
      ?? application/compile/model/compilation/run/
      ?? application/compile/model/compilation/platformpack/
      """.trimIndent()

    assertEquals(
      listOf(
        "application/compile/model/compilation/platformpack/",
        "application/compile/model/compilation/run/",
      ),
      FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(status),
    )
    assertEquals(
      listOf("application/compile/model/compilation/ActivationReason.kt"),
      FeatureTaskRuntimePhaseSafetyPolicy.deletedPaths(status),
    )
  }
}
