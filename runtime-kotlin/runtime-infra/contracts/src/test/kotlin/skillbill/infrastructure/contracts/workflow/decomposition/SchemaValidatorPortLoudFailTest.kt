package skillbill.infrastructure.contracts.workflow.decomposition

import skillbill.contracts.JsonCodec
import skillbill.contracts.install.INSTALL_PLAN_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidDecompositionManifestSchemaError
import skillbill.error.shellcontent.InvalidInstallPlanSchemaError
import skillbill.infrastructure.contracts.install.InstallPlanSchemaValidator
import skillbill.install.model.InstallPlanWireMap
import skillbill.ports.install.InstallPlanWireValidator
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
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
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class SchemaValidatorPortLoudFailTest {
  private val installValidator: InstallPlanWireValidator = InstallPlanSchemaValidator()
  private val decompositionValidator: DecompositionManifestValidator = DecompositionManifestSchemaValidator()
  private val fileStore = YamlEncodingDecompositionManifestStore()

  @Test
  fun `malformed install-plan wire map loud-fails through the injected port`() {
    val wireMap = validInstallPlanWireMap()
    val mcpRegistration = requireNotNull(JsonCodec.anyToStringAnyMap(wireMap["mcp_registration"])).toMutableMap()
    wireMap["mcp_registration"] = mcpRegistration
    mcpRegistration["runtime_mcp_bin"] = ""

    val error =
      assertFailsWith<InvalidInstallPlanSchemaError> {
        installValidator.validate(InstallPlanWireMap.from(wireMap))
      }
    assertContains(error.message.orEmpty(), "mcp_registration.runtime_mcp_bin")
  }

  @Test
  fun `well-formed install-plan wire map passes through the injected port`() {
    installValidator.validate(InstallPlanWireMap.from(validInstallPlanWireMap()))
  }

  @Test
  fun `malformed decomposition YAML loud-fails through the injected port`() {
    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        decompositionValidator.validateYamlText("contract_version: [", "malformed.yaml")
      }
    assertContains(error.reason, "YAML is malformed")
  }

  @Test
  fun `non-object decomposition root loud-fails through the injected port`() {
    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        decompositionValidator.validateYamlText("- contract_version: 0.2", "array.yaml")
      }
    assertContains(error.reason, "<root> must be an object")
  }

  @Test
  fun `decomposition schema violation loud-fails through the injected emission port`() {
    val wireMap = validSameBranchManifest().toMutableWireMap()
    wireMap.remove("contract_version")

    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        decompositionValidator.validate(DecompositionManifestWireMap.from(wireMap), "missing-contract")
      }
    assertContains(error.reason, "contract_version")
  }

  @Test
  fun `duplicate decomposition subtask ids loud-fail through the injected port`() {
    val manifest =
      validSameBranchManifest().copy(
        subtasks =
          listOf(
            subtask(1, "spec_subtask_1_foundation.md"),
            subtask(1, "spec_subtask_2_runtime.md", dependencies = listOf(DecompositionDependency(subtaskId = 1))),
          ),
      )
    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        encodeDecompositionManifestYaml(manifest, decompositionValidator, fileStore)
      }
    assertContains(error.reason, "Duplicate subtask id '1'")
  }

  @Test
  fun `dangling decomposition dependency loud-fails through the injected port`() {
    val manifest =
      validSameBranchManifest().copy(
        subtasks =
          listOf(
            subtask(1, "spec_subtask_1_foundation.md", dependencies = listOf(DecompositionDependency(subtaskId = 2))),
            subtask(2, "spec_subtask_2_runtime.md"),
          ),
      )
    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        encodeDecompositionManifestYaml(manifest, decompositionValidator, fileStore)
      }
    assertContains(error.reason, "earlier declared subtask")
  }

  @Test
  fun `same-branch manifest without feature branch loud-fails through the injected port`() {
    val manifest = validSameBranchManifest().copy(featureBranch = null)
    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        encodeDecompositionManifestYaml(manifest, decompositionValidator, fileStore)
      }
    assertContains(error.reason, "feature_branch")
  }

  @Test
  fun `stacked-branch manifest mismatch loud-fails through the injected port`() {
    val manifest =
      validSameBranchManifest().copy(
        executionModel = DecompositionExecutionModel.STACKED_BRANCHES,
        featureBranch = null,
        stackBranches =
          listOf(
            DecompositionStackBranch(subtaskId = 2, branch = "feature/SKILL-52-02", baseBranch = "main"),
            DecompositionStackBranch(subtaskId = 1, branch = "feature/SKILL-52-01", baseBranch = "main"),
          ),
      )
    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        encodeDecompositionManifestYaml(manifest, decompositionValidator, fileStore)
      }
    assertContains(error.reason, "one branch per subtask in subtask order")
  }

  private fun subtask(
    id: Int,
    specFile: String,
    dependencies: List<DecompositionDependency> = emptyList(),
  ): DecompositionSubtask =
    DecompositionSubtask(
      id = id,
      name = "Subtask $id",
      specPath = ".feature-specs/SKILL-52-decomposition/$specFile",
      dependencies = dependencies,
    )

  private fun validSameBranchManifest(): DecompositionManifest =
    DecompositionManifest(
      issueKey = "SKILL-52",
      featureName = "decomposition",
      parentSpecPath = ".feature-specs/SKILL-52-decomposition/spec.md",
      baseBranch = "main",
      featureBranch = "feature/SKILL-52-decomposition",
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "start"),
      subtasks =
        listOf(
          subtask(1, "spec_subtask_1_foundation.md"),
          subtask(2, "spec_subtask_2_runtime.md", dependencies = listOf(DecompositionDependency(subtaskId = 1))),
        ),
    )

  private fun DecompositionManifest.toMutableWireMap(): MutableMap<String, Any?> =
    LinkedHashMap(DecompositionManifestSchemaValidator().encodeManifestWireMap(this))

  private fun validInstallPlanWireMap(): MutableMap<String, Any?> =
    linkedMapOf(
      "status" to "planned",
      "contract_version" to INSTALL_PLAN_CONTRACT_VERSION,
      "agents" to
        listOf(
          linkedMapOf("agent" to "codex", "path" to "/home/user/.codex/skills", "source" to "manual"),
        ),
      "platform_packs" to emptyList<Map<String, Any?>>(),
      "selected_platforms" to emptyList<String>(),
      "skills" to
        listOf(
          linkedMapOf(
            "name" to "bill-code-review",
            "kind" to "base",
            "platform" to null,
            "source_dir" to "/repo/skills/bill-code-review",
          ),
        ),
      "staging_root" to "/home/user/.skill-bill/installed-skills",
      "staging" to
        listOf(
          linkedMapOf(
            "skill_name" to "bill-code-review",
            "source_dir" to "/repo/skills/bill-code-review",
            "staging_dir" to "/home/user/.skill-bill/installed-skills/bill-code-review-abcdef",
            "content_hash" to "abcdef",
          ),
        ),
      "telemetry_level" to "anonymous",
      "mcp_registration" to
        linkedMapOf<String, Any?>(
          "register" to true,
          "runtime_mcp_bin" to "/home/user/.skill-bill/runtime/runtime-mcp/bin/runtime-mcp",
          "agents" to listOf("codex"),
        ),
      "runtime_distribution" to
        linkedMapOf(
          "runtime_install_root" to "/home/user/.skill-bill/runtime",
          "runtime_cli_build_dir" to null,
          "runtime_mcp_build_dir" to null,
          "runtime_cli_install_dir" to null,
          "runtime_mcp_install_dir" to null,
          "runtime_launcher_bin_dir" to null,
        ),
      "windows_symlink_preflight" to
        linkedMapOf(
          "state" to "not_windows",
          "decision" to "not_required",
          "message" to "",
        ),
      "replace_existing_skill_bill_links" to false,
    )
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
