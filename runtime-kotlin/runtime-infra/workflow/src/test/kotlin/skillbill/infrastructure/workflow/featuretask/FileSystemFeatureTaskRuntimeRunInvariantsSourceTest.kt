package skillbill.infrastructure.workflow.featuretask

import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseHandoffSchemaError
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileSystemFeatureTaskRuntimeRunInvariantsSourceTest {
  @Test
  fun `reads explicit governed feature size from spec text`() {
    val spec =
      writeSpec(
        """
        # Runtime spec

        feature_size: LARGE

        ## Acceptance Criteria
        1. Criterion one.
        """.trimIndent(),
      )

    val invariants = FileSystemFeatureTaskRuntimeRunInvariantsSource().read(spec)

    assertEquals(FeatureTaskRuntimeFeatureSize.LARGE, invariants.featureSize)
  }

  @Test
  fun `defaults omitted feature size to medium`() {
    val spec =
      writeSpec(
        """
        # Runtime spec

        ## Acceptance Criteria
        1. Criterion one.
        """.trimIndent(),
      )

    val invariants = FileSystemFeatureTaskRuntimeRunInvariantsSource().read(spec)

    assertEquals(FeatureTaskRuntimeFeatureSize.MEDIUM, invariants.featureSize)
  }

  @Test
  fun `rejects malformed explicit feature size instead of defaulting`() {
    val spec =
      writeSpec(
        """
        # Runtime spec

        feature_size: HUGE

        ## Acceptance Criteria
        1. Criterion one.
        """.trimIndent(),
      )

    assertFailsWith<InvalidFeatureTaskRuntimePhaseHandoffSchemaError> {
      FileSystemFeatureTaskRuntimeRunInvariantsSource().read(spec)
    }
  }

  @Test
  fun `reads explicit governed feature size with an inline comment`() {
    val spec =
      writeSpec(
        """
        # Runtime spec

        Feature size: SMALL # intentionally scoped

        ## Acceptance Criteria
        1. Criterion one.
        """.trimIndent(),
      )

    val invariants = FileSystemFeatureTaskRuntimeRunInvariantsSource().read(spec)

    assertEquals(FeatureTaskRuntimeFeatureSize.SMALL, invariants.featureSize)
  }

  @Test
  fun `reads numbered acceptance criteria under the canonical heading`() {
    val spec =
      writeSpec(
        """
        # Runtime spec

        ## Acceptance Criteria
        1. First criterion.
        2. Second criterion.
        """.trimIndent(),
      )

    val invariants = FileSystemFeatureTaskRuntimeRunInvariantsSource().read(spec)

    assertEquals(listOf("First criterion.", "Second criterion."), invariants.acceptanceCriteria)
  }

  @Test
  fun `accepts a heading suffix after acceptance criteria`() {
    val spec =
      writeSpec(
        """
        # Runtime spec

        ## Acceptance criteria (this subtask)
        1. First criterion.
        """.trimIndent(),
      )

    val invariants = FileSystemFeatureTaskRuntimeRunInvariantsSource().read(spec)

    assertEquals(listOf("First criterion."), invariants.acceptanceCriteria)
  }

  @Test
  fun `accepts bullet acceptance criteria`() {
    val spec =
      writeSpec(
        """
        # Runtime spec

        ## Acceptance criteria (this subtask)
        - AC1: bullet criterion one.
        - AC2: bullet criterion two.
        """.trimIndent(),
      )

    val invariants = FileSystemFeatureTaskRuntimeRunInvariantsSource().read(spec)

    assertEquals(listOf("AC1: bullet criterion one.", "AC2: bullet criterion two."), invariants.acceptanceCriteria)
  }

  @Test
  fun `accepts checkbox acceptance criteria and strips the marker`() {
    val spec =
      writeSpec(
        """
        # Runtime spec

        ## Acceptance Criteria
        - [ ] unchecked criterion.
        - [x] checked criterion.
        """.trimIndent(),
      )

    val invariants = FileSystemFeatureTaskRuntimeRunInvariantsSource().read(spec)

    assertEquals(listOf("unchecked criterion.", "checked criterion."), invariants.acceptanceCriteria)
  }

  @Test
  fun `keeps nested acceptance bullets inside their parent criterion`() {
    val spec =
      writeSpec(
        """
        # Runtime spec

        ## Acceptance Criteria
        1. Tests cover, at minimum:
           - running duplicate protection;
           - repository isolation.
        2. Maintainer validation passes.
        """.trimIndent(),
      )

    val invariants = FileSystemFeatureTaskRuntimeRunInvariantsSource().read(spec)

    assertEquals(
      listOf(
        "Tests cover, at minimum: Subcriterion: running duplicate protection; " +
          "Subcriterion: repository isolation.",
        "Maintainer validation passes.",
      ),
      invariants.acceptanceCriteria,
    )
  }

  @Test
  fun `does not harvest criteria from a non-acceptance heading`() {
    val spec =
      writeSpec(
        """
        # Runtime spec

        ## Scope
        1. Not an acceptance criterion.
        """.trimIndent(),
      )

    assertFailsWith<IllegalArgumentException> {
      FileSystemFeatureTaskRuntimeRunInvariantsSource().read(spec)
    }
  }

  @Test
  fun `an Agent line under the Status block does not perturb the acceptance-criteria reader`() {
    val spec =
      writeSpec(
        """
        # Runtime spec

        ## Status

        - Status: Complete
        - Agent: claude

        ## Acceptance Criteria
        1. First criterion.
        2. Second criterion.
        """.trimIndent(),
      )

    val invariants = FileSystemFeatureTaskRuntimeRunInvariantsSource().read(spec)

    assertEquals(listOf("First criterion.", "Second criterion."), invariants.acceptanceCriteria)
  }

  @Test
  fun `launch admission reads the ready selected spec and refuses every unusable artifact`() {
    val ready = SelectedSpecFixture()
    assertEquals(listOf("Selected criterion."), ready.read().acceptanceCriteria)
    assertEquals(ready.spec.toString(), ready.read().specReference)

    val refusals: Map<String, () -> SelectedSpecFixture> =
      mapOf(
        "missing" to { SelectedSpecFixture().also { Files.delete(it.spec) } },
        "escaped" to { SelectedSpecFixture(linkSpecOutside = true) },
        "symlink-escaped directory" to { SelectedSpecFixture(linkBundleOutside = true) },
        "wrong-subtask" to { SelectedSpecFixture(manifestedSpecName = "spec_subtask_2_other.md") },
        "partial" to { SelectedSpecFixture().also { Files.createFile(it.spec.resolveSibling(PENDING_MARKER)) } },
        "acceptance-free" to { SelectedSpecFixture(specText = "# Subtask\n\n## Scope\nNo criteria.\n") },
      )
    refusals.forEach { (case, build) ->
      val fixture = build()
      assertFailsWith<IllegalArgumentException>("case '$case' must refuse launch") { fixture.read() }
    }
  }

  private class SelectedSpecFixture(
    specText: String = "# Subtask\n\n## Acceptance Criteria\n1. Selected criterion.\n",
    manifestedSpecName: String = SPEC_NAME,
    linkSpecOutside: Boolean = false,
    linkBundleOutside: Boolean = false,
  ) {
    private val root: Path = Files.createTempDirectory("feature-task-runtime-selected-spec")
    private val outside: Path = Files.createDirectories(root.resolve("outside"))
    private val bundle: Path =
      Files.createDirectories(root.resolve(".feature-specs")).let { specs ->
        if (linkBundleOutside) {
          Files.writeString(outside.resolve(SPEC_NAME), specText)
          Files.writeString(outside.resolve(MANIFEST_NAME), manifest(manifestedSpecName))
          Files.createSymbolicLink(specs.resolve("SKILL-1-bundle"), outside)
        } else {
          Files.createDirectories(specs.resolve("SKILL-1-bundle"))
        }
      }
    val spec: Path = bundle.resolve(SPEC_NAME)

    init {
      if (linkSpecOutside) {
        Files.writeString(outside.resolve("elsewhere.md"), specText)
        Files.createSymbolicLink(spec, outside.resolve("elsewhere.md"))
      } else if (!linkBundleOutside) {
        Files.writeString(spec, specText)
      }
      if (!linkBundleOutside) {
        Files.writeString(bundle.resolve(MANIFEST_NAME), manifest(manifestedSpecName))
      }
    }

    fun read() = FileSystemFeatureTaskRuntimeRunInvariantsSource().read(spec)

    private fun manifest(specName: String) =
      """
      contract_version: "0.5"
      issue_key: "SKILL-1"
      feature_name: "bundle"
      parent_spec_path: ".feature-specs/SKILL-1-bundle/spec.md"
      status: "in_progress"
      execution_model: "same_branch_commit_per_subtask"
      base_branch: "main"
      feature_branch: "feat/SKILL-1-bundle"
      stack_branches: []
      current_subtask_intent:
        subtask_id: 1
        action: "resume"
      subtasks:
      - id: 1
        name: "selected"
        spec_path: ".feature-specs/SKILL-1-bundle/$specName"
        status: "pending"
        dependencies: []
      """.trimIndent()
  }

  private fun writeSpec(text: String) =
    Files.createTempDirectory("feature-task-runtime-invariants").resolve("spec.md").also { path ->
      Files.writeString(path, text)
    }

  private companion object {
    const val SPEC_NAME = "spec_subtask_1_selected.md"
    const val MANIFEST_NAME = "decomposition-manifest.yaml"
    const val PENDING_MARKER = ".decomposition-manifest-bundle-abc.commit"
  }
}
