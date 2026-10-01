package skillbill.infrastructure.contracts.workflow.decomposition

import skillbill.contracts.JsonCodec
import skillbill.error.shellcontent.InvalidDecompositionManifestSchemaError
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.decomposition.decodeManifest
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionDependency
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionManifestValidationResult
import skillbill.workflow.decomposition.model.DecompositionManifestWireMap
import skillbill.workflow.decomposition.model.DecompositionStackBranch
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.model.requireAccepted
import skillbill.workflow.decomposition.runtime.invalidManifest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DecompositionManifestValidationTest {
  private val realDecompositionManifestValidator: DecompositionManifestValidator =
    DecompositionManifestSchemaValidator()
  private val fileStore = YamlEncodingDecompositionManifestStore()

  @Test
  fun `valid same branch manifest passes with default execution model`() {
    val manifest = validSameBranchManifest()

    val yaml = encodeDecompositionManifestYaml(manifest, realDecompositionManifestValidator, fileStore)

    val wireMap = realDecompositionManifestValidator.encodeManifestWireMap(manifest)
    assertContains(yaml, "execution_model")
    assertEquals("same_branch_commit_per_subtask", wireMap["execution_model"])
    assertEquals("feature/SKILL-51-decomposition", wireMap["feature_branch"])
    assertEquals(emptyList<Any?>(), wireMap["stack_branches"])
  }

  @Test
  fun `application emission delegates YAML serialization to the file store encode seam`() {
    val manifest = validSameBranchManifest()
    val spyFileStore = RecordingEncodeFileStore(delegate = fileStore)

    val yaml = encodeDecompositionManifestYaml(manifest, realDecompositionManifestValidator, spyFileStore)

    assertEquals(1, spyFileStore.encodeCalls, "encodeManifestYaml must be the single YAML serialize owner.")
    assertContains(yaml, "execution_model")
  }

  @Test
  fun `relocated encode seam still loud-fails on invalid manifest input`() {
    val invalidManifest = validSameBranchManifest().copy(featureBranch = null)
    val spyFileStore = RecordingEncodeFileStore(delegate = fileStore)

    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        encodeDecompositionManifestYaml(invalidManifest, realDecompositionManifestValidator, spyFileStore)
      }

    assertContains(error.reason, "feature_branch")
    assertTrue(
      spyFileStore.encodeCalls == 0,
      "Invalid input must loud-fail at the validated map seam before reaching the YAML serializer.",
    )
  }

  private class RecordingEncodeFileStore(
    private val delegate: DecompositionManifestStore,
  ) : DecompositionManifestStore by delegate {
    var encodeCalls: Int = 0
      private set

    override fun encodeManifestYaml(wireMap: DecompositionManifestWireMap): String {
      encodeCalls += 1
      return delegate.encodeManifestYaml(wireMap)
    }
  }

  @Test
  fun `valid stacked branch opt-in passes validation`() {
    val manifest =
      validSameBranchManifest().copy(
        executionModel = DecompositionExecutionModel.STACKED_BRANCHES,
        featureBranch = null,
        stackBranches =
          listOf(
            DecompositionStackBranch(subtaskId = 1, branch = "feature/SKILL-51-01-foundation", baseBranch = "main"),
            DecompositionStackBranch(
              subtaskId = 2,
              branch = "feature/SKILL-51-02-runtime",
              baseBranch = "feature/SKILL-51-01-foundation",
            ),
          ),
      )

    encodeDecompositionManifestYaml(manifest, realDecompositionManifestValidator, fileStore)

    assertEquals(
      "stacked_branches",
      realDecompositionManifestValidator.encodeManifestWireMap(manifest)["execution_model"],
    )
  }

  @Test
  fun `malformed schema payload rejects missing required fields`() {
    val wireMap = realDecompositionManifestValidator.encodeManifestWireMap(validSameBranchManifest()).toMutableMap()
    wireMap.remove("contract_version")

    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        realDecompositionManifestValidator.decodeManifest(
          DecompositionManifestWireMap.from(wireMap),
          "missing-contract",
        )
      }
    assertContains(error.reason, "contract_version")
  }

  @Test
  fun `decomposition manifest rejects telemetry-like result payload fields`() {
    val wireMap = validWireMap()
    wireMap.mutableSubtasks()[0]["review_result"] = mapOf("finding_count" to 0)

    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        realDecompositionManifestValidator.decodeManifest(
          DecompositionManifestWireMap.from(wireMap),
          "result-payload-noise",
        )
      }
    assertContains(error.reason, "review_result")
  }

  @Test
  fun `subtask ids above Kotlin Int max fail at application decode seam`() {
    val wireMap = validWireMap()
    wireMap.mutableSubtasks()[0]["id"] = Int.MAX_VALUE.toLong() + 1L

    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        realDecompositionManifestValidator.decodeManifest(
          DecompositionManifestWireMap.from(wireMap),
          "oversized-subtask-id",
        )
      }
    assertContains(error.reason, "subtasks[0].id")
    assertContains(error.reason, Int.MAX_VALUE.toString())
  }

  @Test
  fun `duplicate subtask ids fail at application decode seam`() {
    val wireMap = validWireMap()
    wireMap.mutableSubtasks()[1]["id"] = 1

    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        realDecompositionManifestValidator.decodeManifest(
          DecompositionManifestWireMap.from(wireMap),
          "duplicate-subtask-id",
        )
      }
    assertContains(error.reason, "subtasks[1].id")
    assertContains(error.reason, "Duplicate subtask id '1'")
  }

  @Test
  fun `subtask array order is execution order independent of numeric ids`() {
    val wireMap = validWireMap()
    val subtasks = wireMap.mutableSubtasks()
    subtasks[0]["id"] = 2
    subtasks[1]["id"] = 1
    subtasks[1]["dependencies"] = emptyList<Map<String, Any?>>()

    val decoded =
      realDecompositionManifestValidator.decodeManifest(
        DecompositionManifestWireMap.from(wireMap),
        "array-order-subtasks",
      )

    assertEquals(listOf(2, 1), decoded.subtasks.map { it.id })
  }

  @Test
  fun `current subtask intent must reference declared subtask at application decode seam`() {
    val wireMap = validWireMap()
    wireMap.mutableCurrentSubtaskIntent()["subtask_id"] = 99

    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        realDecompositionManifestValidator.decodeManifest(
          DecompositionManifestWireMap.from(wireMap),
          "missing-current-subtask",
        )
      }
    assertContains(error.reason, "current_subtask_intent.subtask_id")
    assertContains(error.reason, "Current subtask intent must reference a declared subtask")
  }

  @Test
  fun `current subtask intent none action must use zero at application decode seam`() {
    val wireMap = validWireMap()
    val intent = wireMap.mutableCurrentSubtaskIntent()
    intent["subtask_id"] = 1
    intent["action"] = "none"

    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        realDecompositionManifestValidator.decodeManifest(
          DecompositionManifestWireMap.from(wireMap),
          "invalid-none-current-subtask",
        )
      }
    assertContains(error.reason, "current_subtask_intent.subtask_id")
    assertContains(error.reason, "Intent action none must use subtask_id 0")
  }

  @Test
  fun `current subtask intent fractional id fails at application decode seam`() {
    val wireMap = validWireMap()
    wireMap.mutableCurrentSubtaskIntent()["subtask_id"] = 1.5

    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        realDecompositionManifestValidator.decodeManifest(
          DecompositionManifestWireMap.from(wireMap),
          "fractional-current-subtask",
        )
      }
    assertContains(error.reason, "current_subtask_intent.subtask_id")
  }

  @Test
  fun `same branch manifest rejects missing feature branch at application emission seam`() {
    val manifest = validSameBranchManifest().copy(featureBranch = null)

    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        encodeDecompositionManifestYaml(manifest, realDecompositionManifestValidator, fileStore)
      }
    assertContains(error.reason, "feature_branch")
  }

  @Test
  fun `stacked manifest rejects out of order branch declarations at application emission seam`() {
    val manifest =
      validSameBranchManifest().copy(
        executionModel = DecompositionExecutionModel.STACKED_BRANCHES,
        featureBranch = null,
        stackBranches =
          listOf(
            DecompositionStackBranch(subtaskId = 2, branch = "feature/SKILL-51-02-runtime", baseBranch = "main"),
            DecompositionStackBranch(subtaskId = 1, branch = "feature/SKILL-51-01-foundation", baseBranch = "main"),
          ),
      )

    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        encodeDecompositionManifestYaml(manifest, realDecompositionManifestValidator, fileStore)
      }
    assertContains(error.reason, "one branch per subtask in subtask order")
  }

  @Test
  fun `dependency must reference prior subtask at application emission seam`() {
    val manifest =
      validSameBranchManifest().copy(
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 1,
              name = "Foundation",
              specPath = ".feature-specs/SKILL-51-decomposition/spec_subtask_1_foundation.md",
              dependencies = listOf(DecompositionDependency(subtaskId = 2)),
            ),
            DecompositionSubtask(
              id = 2,
              name = "Runtime",
              specPath = ".feature-specs/SKILL-51-decomposition/spec_subtask_2_runtime.md",
              dependencies = emptyList(),
            ),
          ),
      )

    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        encodeDecompositionManifestYaml(manifest, realDecompositionManifestValidator, fileStore)
      }
    assertContains(error.reason, "earlier declared subtask")
  }

  @Test
  fun `duplicate subtask spec paths fail at application emission seam`() {
    val manifest =
      validSameBranchManifest().copy(
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 1,
              name = "Foundation",
              specPath = ".feature-specs/SKILL-51-decomposition/spec_subtask_1_foundation.md",
              dependencies = emptyList(),
            ),
            DecompositionSubtask(
              id = 2,
              name = "Runtime",
              specPath = ".feature-specs/SKILL-51-decomposition/spec_subtask_1_foundation.md",
              dependencies = listOf(DecompositionDependency(subtaskId = 1)),
            ),
          ),
      )

    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        encodeDecompositionManifestYaml(manifest, realDecompositionManifestValidator, fileStore)
      }
    assertContains(error.reason, "spec_path")
    assertContains(error.reason, "Duplicate")
  }

  private fun validSameBranchManifest(): DecompositionManifest =
    DecompositionManifest(
      issueKey = "SKILL-51",
      featureName = "decomposition",
      parentSpecPath = ".feature-specs/SKILL-51-decomposition/spec.md",
      baseBranch = "main",
      featureBranch = "feature/SKILL-51-decomposition",
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "start"),
      subtasks =
        listOf(
          DecompositionSubtask(
            id = 1,
            name = "Foundation",
            specPath = ".feature-specs/SKILL-51-decomposition/spec_subtask_1_foundation.md",
            dependencies = emptyList(),
          ),
          DecompositionSubtask(
            id = 2,
            name = "Runtime",
            specPath = ".feature-specs/SKILL-51-decomposition/spec_subtask_2_runtime.md",
            dependencies = listOf(DecompositionDependency(subtaskId = 1)),
          ),
        ),
    )

  private fun validWireMap(): MutableMap<String, Any?> =
    LinkedHashMap(
      realDecompositionManifestValidator.encodeManifestWireMap(validSameBranchManifest()),
    )

  private fun MutableMap<String, Any?>.mutableSubtasks(): MutableList<MutableMap<String, Any?>> {
    val mutableSubtasks =
      requireNotNull(JsonCodec.anyToStringAnyMapList(this["subtasks"]))
        .mapTo(mutableListOf<MutableMap<String, Any?>>()) { LinkedHashMap(it) }
    this["subtasks"] = mutableSubtasks
    return mutableSubtasks
  }

  private fun MutableMap<String, Any?>.mutableCurrentSubtaskIntent(): MutableMap<String, Any?> {
    val mutableIntent = LinkedHashMap(requireNotNull(JsonCodec.anyToStringAnyMap(this["current_subtask_intent"])))
    this["current_subtask_intent"] = mutableIntent
    return mutableIntent
  }
}

private fun encodeDecompositionManifestYaml(
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
  fileStore: DecompositionManifestStore,
  sourceLabel: String = "<in-memory>",
): String {
  val wireMap = validator.encodeManifestWireMap(manifest, sourceLabel)
  val yamlText = fileStore.encodeManifestYaml(wireMap)
  return when (val result = validator.validateYamlTextResult(yamlText, sourceLabel)) {
    is DecompositionManifestValidationResult.AcceptedUnchanged -> result.yamlText
    is DecompositionManifestValidationResult.AcceptedAfterRepair -> result.yamlText
    is DecompositionManifestValidationResult.Rejected -> {
      result.requireAccepted(sourceLabel)
      error("Unreachable rejected decomposition manifest result.")
    }
  }
}
