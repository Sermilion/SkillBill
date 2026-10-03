package skillbill.scaffold

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import skillbill.contracts.install.INSTALL_PLAN_CONTRACT_VERSION
import skillbill.contracts.workflow.WORKFLOW_STATE_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InvalidInstallPlanSchemaError
import skillbill.error.shellcontent.InvalidNativeAgentCompositionSchemaError
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.error.shellcontent.ManifestFailureCode
import skillbill.error.shellcontent.invalidManifestSchema
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.SchemaIdentityRequest
import skillbill.infrastructure.contracts.locator.InstallPlanSchemaPaths
import skillbill.infrastructure.contracts.locator.WorkflowStateSchemaPaths
import skillbill.infrastructure.skills.nativeagent.composition.NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentCompositionSchemaPaths
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformManifest
import skillbill.infrastructure.skills.scaffold.platformpack.manifest.PlatformPackSchemaPaths
import skillbill.infrastructure.skills.scaffold.platformpack.manifest.PlatformPackSchemaValidator
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.SHELL_CONTRACT_VERSION
import skillbill.testing.repoRootFromTest

class PlatformPackSchemaCleanupTest {
  @Test
  fun `C7 classpath shadow with mismatched schema id loud-fails`() {
    val mismatchedIdYaml =
      """
      ${'$'}schema: "https://json-schema.org/draft/2020-12/schema"
      ${'$'}id: "https://malicious.example/shadow-schema.yaml"
      type: object
      properties:
        contract_version:
          const: "$SHELL_CONTRACT_VERSION"
      """.trimIndent()
    val node = YAMLMapper().readTree(mismatchedIdYaml)

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateIdentity(node, PlatformPackSchemaPaths.EXPECTED_SCHEMA_ID, SHELL_CONTRACT_VERSION) {
          invalidManifestSchema(it)
        }
      }.also { failure ->
        assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
      }
    val message = error.message.orEmpty()
    assertContains(message, "https://malicious.example/shadow-schema.yaml")
    assertContains(message, PlatformPackSchemaPaths.EXPECTED_SCHEMA_ID)
  }

  @Test
  fun `C7 classpath shadow with mismatched contract_version const loud-fails`() {
    val mismatchedConstYaml =
      """
      ${'$'}schema: "https://json-schema.org/draft/2020-12/schema"
      ${'$'}id: "${PlatformPackSchemaPaths.EXPECTED_SCHEMA_ID}"
      type: object
      properties:
        contract_version:
          const: "9.99"
      """.trimIndent()
    val node = YAMLMapper().readTree(mismatchedConstYaml)

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateIdentity(node, PlatformPackSchemaPaths.EXPECTED_SCHEMA_ID, SHELL_CONTRACT_VERSION) {
          invalidManifestSchema(it)
        }
      }.also { failure ->
        assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
      }
    val message = error.message.orEmpty()
    assertContains(message, "9.99")
    assertContains(message, SHELL_CONTRACT_VERSION)
  }

  @Test
  fun `C7 canonical schema on disk passes identity assertion`() {
    val schemaPath: Path =
      repoRootFromTest()
        .resolve(PlatformPackSchemaPaths.REPO_RELATIVE_PATH)
    val node = YAMLMapper().readTree(Files.readString(schemaPath))

    validateIdentity(node, PlatformPackSchemaPaths.EXPECTED_SCHEMA_ID, SHELL_CONTRACT_VERSION) {
      invalidManifestSchema(it)
    }
  }

  @Test
  fun `workflow-state schema classpath shadow with mismatched id loud-fails`() {
    val mismatchedIdYaml =
      """
      ${'$'}schema: "https://json-schema.org/draft/2020-12/schema"
      ${'$'}id: "https://malicious.example/shadow-workflow-state.yaml"
      type: object
      properties:
        contract_version:
          const: "$WORKFLOW_STATE_CONTRACT_VERSION"
      """.trimIndent()
    val node = YAMLMapper().readTree(mismatchedIdYaml)

    val error =
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        validateIdentity(node, WorkflowStateSchemaPaths.EXPECTED_SCHEMA_ID, WORKFLOW_STATE_CONTRACT_VERSION) {
          InvalidWorkflowStateSchemaError(it)
        }
      }
    val message = error.message.orEmpty()
    assertContains(message, "https://malicious.example/shadow-workflow-state.yaml")
    assertContains(message, WorkflowStateSchemaPaths.EXPECTED_SCHEMA_ID)
  }

  @Test
  fun `workflow-state schema classpath shadow with mismatched contract_version const loud-fails`() {
    val mismatchedConstYaml =
      """
      ${'$'}schema: "https://json-schema.org/draft/2020-12/schema"
      ${'$'}id: "${WorkflowStateSchemaPaths.EXPECTED_SCHEMA_ID}"
      type: object
      properties:
        contract_version:
          const: "9.99"
      """.trimIndent()
    val node = YAMLMapper().readTree(mismatchedConstYaml)

    val error =
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        validateIdentity(node, WorkflowStateSchemaPaths.EXPECTED_SCHEMA_ID, WORKFLOW_STATE_CONTRACT_VERSION) {
          InvalidWorkflowStateSchemaError(it)
        }
      }
    val message = error.message.orEmpty()
    assertContains(message, "9.99")
    assertContains(message, WORKFLOW_STATE_CONTRACT_VERSION)
  }

  @Test
  fun `workflow-state canonical schema on disk passes identity assertion`() {
    val schemaPath: Path =
      repoRootFromTest()
        .resolve(WorkflowStateSchemaPaths.REPO_RELATIVE_PATH)
    val node = YAMLMapper().readTree(Files.readString(schemaPath))

    validateIdentity(node, WorkflowStateSchemaPaths.EXPECTED_SCHEMA_ID, WORKFLOW_STATE_CONTRACT_VERSION) {
      InvalidWorkflowStateSchemaError(it)
    }
  }

  @Test
  fun `install-plan schema classpath shadow with mismatched id loud-fails`() {
    val mismatchedIdYaml =
      """
      ${'$'}schema: "https://json-schema.org/draft/2020-12/schema"
      ${'$'}id: "https://malicious.example/shadow-install-plan.yaml"
      type: object
      properties:
        contract_version:
          const: "$INSTALL_PLAN_CONTRACT_VERSION"
      """.trimIndent()

    val error =
      assertFailsWith<InvalidInstallPlanSchemaError> {
        validateIdentity(
          YAMLMapper().readTree(mismatchedIdYaml),
          InstallPlanSchemaPaths.EXPECTED_SCHEMA_ID,
          INSTALL_PLAN_CONTRACT_VERSION,
        ) {
          InvalidInstallPlanSchemaError(fieldPath = "<schema>", reason = it)
        }
      }
    val reason = error.reason
    assertContains(reason, "https://malicious.example/shadow-install-plan.yaml")
    assertContains(reason, InstallPlanSchemaPaths.EXPECTED_SCHEMA_ID)
  }

  @Test
  fun `install-plan schema classpath shadow with mismatched contract_version const loud-fails`() {
    val mismatchedConstYaml =
      """
      ${'$'}schema: "https://json-schema.org/draft/2020-12/schema"
      ${'$'}id: "${InstallPlanSchemaPaths.EXPECTED_SCHEMA_ID}"
      type: object
      properties:
        contract_version:
          const: "9.99"
      """.trimIndent()

    val error =
      assertFailsWith<InvalidInstallPlanSchemaError> {
        validateIdentity(
          YAMLMapper().readTree(mismatchedConstYaml),
          InstallPlanSchemaPaths.EXPECTED_SCHEMA_ID,
          INSTALL_PLAN_CONTRACT_VERSION,
        ) {
          InvalidInstallPlanSchemaError(fieldPath = "<schema>", reason = it)
        }
      }
    val reason = error.reason
    assertContains(reason, "9.99")
    assertContains(reason, INSTALL_PLAN_CONTRACT_VERSION)
  }

  @Test
  fun `install-plan canonical schema on disk passes identity assertion`() {
    val schemaPath: Path =
      repoRootFromTest()
        .resolve(InstallPlanSchemaPaths.REPO_RELATIVE_PATH)
    val yamlText = Files.readString(schemaPath)

    validateIdentity(
      YAMLMapper().readTree(yamlText),
      InstallPlanSchemaPaths.EXPECTED_SCHEMA_ID,
      INSTALL_PLAN_CONTRACT_VERSION,
    ) {
      InvalidInstallPlanSchemaError(fieldPath = "<schema>", reason = it)
    }
  }

  @Test
  fun `native-agent composition schema classpath shadow with mismatched id loud-fails`() {
    val mismatchedIdYaml =
      """
      ${'$'}schema: "https://json-schema.org/draft/2020-12/schema"
      ${'$'}id: "https://malicious.example/shadow-native-agent-composition.yaml"
      ${'$'}defs:
        contractVersion:
          const: "$NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION"
      """.trimIndent()

    val error =
      assertFailsWith<InvalidNativeAgentCompositionSchemaError> {
        validateIdentity(
          YAMLMapper().readTree(mismatchedIdYaml),
          NativeAgentCompositionSchemaPaths.EXPECTED_SCHEMA_ID,
          NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION,
          listOf("\$defs", "contractVersion", "const"),
        ) { InvalidNativeAgentCompositionSchemaError(sourceLabel = "<schema>", reason = it) }
      }
    val reason = error.reason
    assertContains(reason, "https://malicious.example/shadow-native-agent-composition.yaml")
    assertContains(reason, NativeAgentCompositionSchemaPaths.EXPECTED_SCHEMA_ID)
  }

  @Test
  fun `native-agent composition schema classpath shadow with mismatched contract_version const loud-fails`() {
    val mismatchedConstYaml =
      """
      ${'$'}schema: "https://json-schema.org/draft/2020-12/schema"
      ${'$'}id: "${NativeAgentCompositionSchemaPaths.EXPECTED_SCHEMA_ID}"
      ${'$'}defs:
        contractVersion:
          const: "9.99"
      """.trimIndent()

    val error =
      assertFailsWith<InvalidNativeAgentCompositionSchemaError> {
        validateIdentity(
          YAMLMapper().readTree(mismatchedConstYaml),
          NativeAgentCompositionSchemaPaths.EXPECTED_SCHEMA_ID,
          NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION,
          listOf("\$defs", "contractVersion", "const"),
        ) { InvalidNativeAgentCompositionSchemaError(sourceLabel = "<schema>", reason = it) }
      }
    val reason = error.reason
    assertContains(reason, "9.99")
    assertContains(reason, NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION)
  }

  @Test
  fun `native-agent composition canonical schema on disk passes identity assertion`() {
    val schemaPath: Path =
      repoRootFromTest()
        .resolve(NativeAgentCompositionSchemaPaths.REPO_RELATIVE_PATH)
    val yamlText = Files.readString(schemaPath)

    validateIdentity(
      YAMLMapper().readTree(yamlText),
      NativeAgentCompositionSchemaPaths.EXPECTED_SCHEMA_ID,
      NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION,
      listOf("\$defs", "contractVersion", "const"),
    ) { InvalidNativeAgentCompositionSchemaError(sourceLabel = "<schema>", reason = it) }
  }

  @Test
  fun `C4 duplicate entries in declared_code_review_areas loud-fail through canonical validator`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "$SHELL_CONTRACT_VERSION"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas:
        - architecture
        - architecture
      """.trimIndent()

    val tempDir = Files.createTempDirectory("skillbill-c4-uniqueitems-")
    val packRoot = tempDir.resolve("scenarioslug")
    Files.createDirectories(packRoot)
    Files.writeString(packRoot.resolve("platform.yaml"), manifest)

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        loadPlatformManifest(packRoot)
      }.also { failure ->
        assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
      }
    val message = error.message.orEmpty()
    assertContains(message, "declared_code_review_areas")
  }

  @Test
  fun `C8 no migrated test still declares a private repoRootFromTest function`() {
    val repoRoot = repoRootFromTest()
    val migratedSources =
      listOf(
        repoRoot.resolve(
          "runtime-kotlin/runtime-core/src/test/kotlin/skillbill/scaffold/" +
            "PlatformPackSchemaContractVersionTest.kt",
        ),
        repoRoot.resolve(
          "runtime-kotlin/runtime-core/src/test/kotlin/skillbill/scaffold/" +
            "PlatformPackSchemaValidatesExistingPacksTest.kt",
        ),
        repoRoot.resolve(
          "runtime-kotlin/runtime-core/src/test/kotlin/skillbill/scaffold/" +
            "ShellContentLoaderParityTest.kt",
        ),
      )
    migratedSources.filter(Files::exists).forEach { source ->
      val text = Files.readString(source)

      val redeclared = Regex("""fun\s+repoRootFromTest\s*\(""").containsMatchIn(text)
      check(!redeclared) {
        "SKILL-48 C8 regression: $source still declares a local repoRootFromTest function."
      }

      val nearIdenticalRedeclared = Regex("""private\s+fun\s+repoRoot\s*\(""").containsMatchIn(text)
      check(!nearIdenticalRedeclared) {
        "SKILL-48 C8 regression: $source still declares a private repoRoot() helper that duplicates " +
          "repoRootFromTest()."
      }
    }
  }

  @Test
  fun `C2 validate accepts Map of String to Any-question for a well-formed manifest`() {
    val typedManifest: Map<String, Any?> =
      mapOf(
        "platform" to "scenarioslug",
        "contract_version" to SHELL_CONTRACT_VERSION,
        "routing_signals" to mapOf("strong" to listOf(".kt")),
        "declared_code_review_areas" to emptyList<String>(),
      )
    val validator = PlatformPackSchemaValidator()
    validator.validate(typedManifest, "scenarioslug")
  }
}

private fun validateIdentity(
  node: JsonNode,
  expectedSchemaId: String,
  expectedContractVersion: String,
  contractVersionPath: List<String> = listOf("properties", "contract_version", "const"),
  error: (String) -> SkillBillRuntimeException,
) {
  ClasspathContractSchemaLoader.validateSchemaIdentity(
    SchemaIdentityRequest(
      yamlNode = node,
      classpathResource = expectedSchemaId,
      expectedSchemaId = expectedSchemaId,
      expectedContractVersion = expectedContractVersion,
      contractVersionPath = contractVersionPath,
      identityFailure = error,
    ),
  )
}
