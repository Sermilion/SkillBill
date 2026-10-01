package skillbill.architecture

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstallPolicyOwnershipArchitectureTest {
  private val runtimeRoot: Path = ArchitectureScanSupport.runtimeRoot
  private val infraSkillsModule = RuntimeModuleCatalog.runtimeKotlinModuleDirectory("runtime-infra:skills")
  private val infraContractsModule = RuntimeModuleCatalog.runtimeKotlinModuleDirectory("runtime-infra:contracts")
  private val approvedPolicyCallers =
    setOf(
      "$infraSkillsModule/src/main/kotlin/skillbill/infrastructure/skills/install/plan/InstallPlanBuilder.kt",
    )

  private val approvedValidationSeams =
    mapOf(
      "$infraSkillsModule/src/main/kotlin/skillbill/infrastructure/skills/install/plan/InstallPlanBuilder.kt" to
        "wireValidator.validate(buildInstallPlanWireMap(plan))",
      "runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/install/apply/InstallCliPayloads.kt" to
        "installService.validateInstallPlanWire",
    )

  private val approvedValidatorAdapters =
    setOf(
      "$infraContractsModule/src/main/kotlin/skillbill/infrastructure/contracts/install/InstallPlanSchemaValidator.kt",
    )

  @Test
  fun `install policy package must not import filesystem or install implementation mechanics`() {
    val policyRoot =
      runtimeRoot.resolve(
        "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/install/policy",
      )
    val policyFiles = kotlinFilesUnderWithArchitectureAsserts(policyRoot)
    assertTrue(policyFiles.isNotEmpty(), "Install policy package must exist in runtime-domain.")

    val forbiddenImportPattern = installPolicyForbiddenImportPattern()
    val violations =
      policyFiles.flatMap { sourceFile ->
        sourceFile.readText().lineSequence()
          .mapIndexedNotNull { index, line ->
            val trimmed = line.trim()
            if (forbiddenImportPattern.matches(trimmed)) {
              "${runtimeRoot.relativize(sourceFile)}:${index + 1} imports ${trimmed.removePrefix("import ")}"
            } else {
              null
            }
          }
      }

    assertEquals(
      emptyList(),
      violations,
      "Install plan policy must stay pure: filesystem/process mechanics and install implementation " +
        "imports belong in runtime-infra:skills.",
    )
  }

  @Test
  fun `install policy forbidden import regex catches known bad and passes known good`() {
    val forbiddenImportPattern = installPolicyForbiddenImportPattern()
    val mustBeDetectedAsForbidden =
      listOf(
        "import java.io.File",
        "import java.nio.file.Files",
        "import java.lang.ProcessBuilder",
        "import skillbill.infrastructure.skills.install.FileSystemInstallPlanningFacts",
        "import skillbill.infrastructure.skills.install.InstallOperations",
        "import skillbill.infrastructure.skills.install.InstallPlanBuilder",
        "import skillbill.infrastructure.skills.install.computeInstallContentHash",
      )
    val mustNotBeDetectedAsForbidden =
      listOf(
        "import java.nio.file.Path",
        "import skillbill.install.model.InstallPlan",
        "import skillbill.install.policy.InstallPlanPolicy",
      )

    val falseNegatives = mustBeDetectedAsForbidden.filterNot(forbiddenImportPattern::matches)
    val falsePositives = mustNotBeDetectedAsForbidden.filter(forbiddenImportPattern::matches)

    assertEquals(emptyList(), falseNegatives, "Install-policy forbidden-import regex missed known-bad imports.")
    assertEquals(emptyList(), falsePositives, "Install-policy forbidden-import regex flagged known-good imports.")
  }

  @Test
  fun `domain install policy takes no validator and does not reference the schema validator`() {
    val policyText =
      runtimeRoot
        .resolve(
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/install/policy/InstallPlanPolicy.kt",
        )
        .readText()
    assertTrue(
      !policyText.contains("InstallPlanSchemaValidator"),
      "InstallPlanPolicy must not reference the concrete InstallPlanSchemaValidator.",
    )
  }

  @Test
  fun `adapter install seams do not own planner or validator policy`() {
    approvedValidationSeams.forEach { (relativePath, expectedToken) ->
      val text = runtimeRoot.resolve(relativePath).readText()
      assertTrue(
        text.contains(expectedToken),
        "Approved install-plan validation seam $relativePath must call '$expectedToken' to route validation " +
          "through the injected InstallPlanWireValidator port.",
      )
    }

    val validatorOwnerFiles =
      setOf(
        "$infraContractsModule/src/main/kotlin/skillbill/infrastructure/contracts/install/" +
          "InstallPlanSchemaValidator.kt",
      )
    val adapterFiles = adapterKotlinFiles()
    val violations =
      adapterFiles
        .filterNot { sourceFile -> runtimeRoot.relativize(sourceFile).toSlashPath() in validatorOwnerFiles }
        .flatMap { sourceFile ->
          adapterPolicyOwnershipViolations(runtimeRoot.relativize(sourceFile).toSlashPath(), sourceFile.readText())
        }

    assertEquals(
      emptyList(),
      violations,
      "Adapter modules may invoke install-plan contract validation only at approved seams and must not own " +
        "install planner, validator, or domain-policy decisions.",
    )
  }

  @Test
  fun `adapter ownership scanner catches direct fqn alias and wildcard policy samples`() {
    val knownBadSamples =
      mapOf(
        "runtime-cli/src/main/kotlin/skillbill/cli/BadPolicyAlias.kt" to
          "import skillbill.install.policy.InstallPlanPolicy as Policy\nval draft = Policy.buildPlanDraft(input)",
        "runtime-cli/src/main/kotlin/skillbill/cli/BadPolicyWildcard.kt" to
          "import skillbill.install.policy.*\nval draft = InstallPlanPolicy.buildPlanDraft(input)",
        "runtime-cli/src/main/kotlin/skillbill/cli/BadPolicyFqn.kt" to
          "val draft = skillbill.install.policy.InstallPlanPolicy.buildPlanDraft(input)",
        "runtime-cli/src/main/kotlin/skillbill/cli/BadValidatorAlias.kt" to
          "import skillbill.contracts.install.InstallPlanSchemaValidator as Validator\n" +
          "Validator.validate(payload)",
        "runtime-cli/src/main/kotlin/skillbill/cli/BadValidatorWildcard.kt" to
          "import skillbill.contracts.install.*\nInstallPlanSchemaValidator.validate(payload)",
        "runtime-cli/src/main/kotlin/skillbill/cli/BadValidatorFqn.kt" to
          "skillbill.contracts.install.InstallPlanSchemaValidator.validate(payload)",
        "runtime-mcp/src/main/kotlin/skillbill/mcp/BadValidationCall.kt" to
          "buildInstallPlanWireMap(plan)",
        "runtime-mcp/src/main/kotlin/skillbill/mcp/BadValidationAlias.kt" to
          "import skillbill.install.model.buildInstallPlanWireMap as buildPlan\nbuildPlan(plan)",
      )

    val falseNegatives =
      knownBadSamples.mapNotNull { (relativePath, sourceText) ->
        relativePath.takeIf { adapterPolicyOwnershipViolations(relativePath, sourceText).isEmpty() }
      }

    assertEquals(emptyList(), falseNegatives, "Adapter ownership scanner missed known-bad samples.")
    assertEquals(
      emptyList(),
      adapterPolicyOwnershipViolations(
        "$infraSkillsModule/src/main/kotlin/skillbill/infrastructure/skills/install/plan/InstallPlanBuilder.kt",
        """
        |import skillbill.install.policy.InstallPlanPolicy
        |import skillbill.install.model.buildInstallPlanWireMap
        |val draft = InstallPlanPolicy.buildPlanDraft(input)
        |wireValidator.validate(buildInstallPlanWireMap(plan))
        """.trimMargin(),
      ),
      "Approved builder seam must remain allowed to invoke policy and shared validation.",
    )
  }

  private fun installPolicyForbiddenImportPattern(): Regex =
    Regex(
      """^import\s+(""" +
        """java\.io\.File|java\.nio\.file\.Files|java\.lang\.ProcessBuilder|""" +
        """skillbill\.infrastructure(?:\..*)?|""" +
        """skillbill\.install\.(?!model\.|policy\.)[A-Za-z0-9_.*]+""" +
        """)$""",
    )

  private fun adapterPolicyOwnershipViolations(
    relativePath: String,
    sourceText: String,
  ): List<String> =
    sourceText.lineSequence().mapIndexedNotNull { index, line ->
      adapterPolicyOwnershipViolation(relativePath, index + 1, line.trim())
    }.toList()

  private fun adapterPolicyOwnershipViolation(
    relativePath: String,
    lineNumber: Int,
    trimmed: String,
  ): String? {
    val code = trimmed.takeUnless { it.startsWith("//") || it.startsWith("*") } ?: return null
    val disallowedPolicyReference =
      installPolicyReferencePattern().containsMatchIn(code) &&
        relativePath !in approvedPolicyCallers
    val disallowedSchemaValidatorReference =
      installSchemaValidatorReferencePattern().containsMatchIn(code) &&
        relativePath !in approvedValidatorAdapters
    val disallowedValidationUse =
      installWireSnapshotValidationReferencePattern().containsMatchIn(code) &&
        relativePath !in approvedValidationSeams
    val disallowedPolicyDeclaration = installPolicyDeclarationPattern().containsMatchIn(code)
    return when {
      disallowedPolicyReference -> "$relativePath:$lineNumber references InstallPlanPolicy outside the builder seam"
      disallowedSchemaValidatorReference -> "$relativePath:$lineNumber references InstallPlanSchemaValidator directly"
      disallowedValidationUse -> "$relativePath:$lineNumber invokes install-plan validation outside approved seams"
      disallowedPolicyDeclaration -> "$relativePath:$lineNumber declares install planner/validator policy"
      else -> null
    }
  }

  private fun installPolicyReferencePattern(): Regex =
    Regex(
      """^import\s+skillbill\.install\.policy\.\*(?:\s+as\s+\w+)?$|""" +
        """\bskillbill\.install\.policy\.InstallPlanPolicy\b""",
    )

  private fun installSchemaValidatorReferencePattern(): Regex =
    Regex(
      """^import\s+skillbill\.contracts\.install\.\*(?:\s+as\s+\w+)?$|""" +
        """\bskillbill\.contracts\.install\.InstallPlanSchemaValidator\b""",
    )

  private fun installWireSnapshotValidationReferencePattern(): Regex =
    Regex(
      """^import\s+skillbill\.install\.model\.buildInstallPlanWireMap(?:\s+as\s+\w+)?$|""" +
        """(?:^|[^\w.])(?:skillbill\.install\.model\.)?buildInstallPlanWireMap\s*(?:\(|$)""",
    )

  private fun installPolicyDeclarationPattern(): Regex =
    Regex(
      """^(class|object|interface)\s+.*""" +
        """(InstallPlanPolicy|InstallPlanner|InstallPlanValidator|InstallPlanSchemaValidator)\b""",
    )

  private fun adapterKotlinFiles(): List<Path> =
    listOf(
      moduleMainKotlinRoot("runtime-cli"),
      moduleMainKotlinRoot("runtime-mcp"),
      moduleMainKotlinRoot("runtime-infra:skills"),
      moduleMainKotlinRoot("runtime-infra:http"),
      moduleMainKotlinRoot("runtime-infra:sqlite"),
    ).flatMap(::kotlinFilesUnderWithArchitectureAsserts)

  private fun Path.toSlashPath(): String = toString().replace('\\', '/')
}
