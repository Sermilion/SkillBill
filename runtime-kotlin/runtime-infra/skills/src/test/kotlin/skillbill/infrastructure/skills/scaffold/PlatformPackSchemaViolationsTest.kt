package skillbill.infrastructure.skills.scaffold

import skillbill.error.shellcontent.ContractVersionMismatchError
import skillbill.error.shellcontent.InvalidManifestSchemaError
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformManifest
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformPack
import skillbill.infrastructure.skills.scaffold.platformpack.loader.validateGovernedSkill
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import skillbill.model.toPath
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PlatformPackSchemaViolationsTest {
  @Test
  fun `missing machine readable routing path fails before preparation`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: [architecture]
      declared_files:
        baseline: code-review/content.md
        areas:
          architecture: code-review/architecture/content.md
      lane_conditions:
        architecture:
          required: true
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    assertContains(error.message.orEmpty(), "path")
  }

  @Test
  fun `missing declared area lane condition fails before preparation`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
        path: ["*.kt"]
      lane_conditions: {}
      declared_code_review_areas: [architecture]
      declared_files:
        baseline: code-review/content.md
        areas:
          architecture: code-review/architecture/content.md
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    assertContains(error.message.orEmpty(), "lane_conditions")
    assertContains(error.message.orEmpty(), "architecture")
  }

  @Test
  fun `missing platform field`() {
    val manifest =
      """
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }

    val message = error.message.orEmpty()
    assertContains(message, "'platform'")
    assertContains(message, "required")
  }

  @Test
  fun `missing routing_signals strong`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals: {}
      declared_code_review_areas: []
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    assertContains(error.message.orEmpty(), "routing_signals")
    assertContains(error.message.orEmpty(), "strong")
  }

  @Test
  fun `coherence rule slug parity platform field disagrees with directory name`() {
    val manifest =
      """
      platform: wrong
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("kotlin", manifest)
      }
    val message = error.message.orEmpty()
    assertContains(message, "platform")
    assertContains(message, "wrong")
    assertContains(message, "kotlin")
  }

  @Test
  fun `coherence rule areas without baseline raises named error`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas:
        - architecture
      declared_files:
        areas:
          architecture: code-review/architecture/content.md
      area_metadata:
        architecture:
          focus: "architecture"
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    assertContains(error.message.orEmpty(), "declared_files")
    assertContains(error.message.orEmpty(), "baseline")
  }

  @Test
  fun `coherence rule area_metadata key not in declared_code_review_areas`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas:
        - architecture
      declared_files:
        baseline: code-review/content.md
        areas:
          architecture: code-review/architecture/content.md
      area_metadata:
        architecture:
          focus: "architecture"
        security:
          focus: "security"
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    assertContains(error.message.orEmpty(), "area_metadata")
    assertContains(error.message.orEmpty(), "security")
  }

  @Test
  fun `declared_code_review_areas with unapproved enum value`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas:
        - laravel
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    assertContains(error.message.orEmpty(), "declared_code_review_areas")
    assertContains(error.message.orEmpty(), "laravel")
  }

  @Test
  fun `contract_version mismatch surfaces ContractVersionMismatchError`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "9.99"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      """.trimIndent()
    val error =
      assertFailsWith<ContractVersionMismatchError> {
        loadPackThroughContractGate("scenarioslug", manifest)
      }
    val message = error.message.orEmpty()

    assertContains(message, "contract_version")
    assertContains(message, "9.99")
  }

  @Test
  fun `contract_version mismatch surfaces ContractVersionMismatchError from loadPlatformManifest`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "9.99"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      """.trimIndent()
    val error =
      assertFailsWith<ContractVersionMismatchError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    val message = error.message.orEmpty()
    assertContains(message, "contract_version")
    assertContains(message, "9.99")
  }

  @Test
  fun `manifest still declaring contract_version 1_1 fails loudly`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.1"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      """.trimIndent()
    val error =
      assertFailsWith<ContractVersionMismatchError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    val message = error.message.orEmpty()
    assertContains(message, "contract_version")
    assertContains(message, "1.1")
  }

  @Test
  fun `pointer name without md suffix fails schema rule`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      pointers:
        code-review/something:
          - name: "review.txt"
            target: "orchestration/shell-content-contract/shell-ceremony.md"
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    val message = error.message.orEmpty()
    assertContains(message, "review.txt")
    assertContains(message, ".md")
  }

  @Test
  fun `pointer name containing parent-dir sequence fails schema rule`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      pointers:
        code-review/something:
          - name: "..md"
            target: "orchestration/shell-content-contract/shell-ceremony.md"
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    val message = error.message.orEmpty()
    assertContains(message, "name")
    assertContains(message, "..")
  }

  @Test
  fun `pointer target containing parent-dir segments fails runtime safety rule`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      pointers:
        code-review/something:
          - name: "shell-ceremony.md"
            target: "../../etc/passwd"
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    val message = error.message.orEmpty()
    assertContains(message, "target")
    assertContains(message, "..")
  }

  @Test
  fun `coherence rule areas keys not bijective with declared_code_review_areas`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas:
        - architecture
      declared_files:
        baseline: code-review/content.md
        areas:
          architecture: code-review/architecture/content.md
          performance: code-review/performance/content.md
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    assertContains(error.message.orEmpty(), "declared_files.areas")
    assertContains(error.message.orEmpty(), "performance")
  }

  @Test
  fun `coherence rule declared area missing from declared_files areas`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas:
        - architecture
        - security
      declared_files:
        baseline: code-review/content.md
        areas:
          architecture: code-review/architecture/content.md
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    val message = error.message.orEmpty()
    assertContains(message, "declared_files.areas")
    assertContains(message, "security")
  }

  @Test
  fun `coherence rule pointers unique name per dir rejects duplicate name`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      pointers:
        code-review/something:
          - name: "shell-ceremony.md"
            target: "orchestration/shell-content-contract/shell-ceremony.md"
          - name: "shell-ceremony.md"
            target: "orchestration/shell-content-contract/shell-ceremony.md"
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    val message = error.message.orEmpty()
    assertContains(message, "shell-ceremony.md")

    assertContains(message, "duplicate")
  }

  @Test
  fun `coherence rule addon_usage keys must match declared skill directories`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      declared_files:
        baseline: code-review/bill-scenarioslug-code-review/content.md
        areas: {}
      pointers:
        code-review/not-a-skill:
          - name: android-compose-review.md
            target: platform-packs/scenarioslug/addons/android-compose-review.md
      addon_usage:
        code-review/not-a-skill:
          - slug: android-compose
            entrypoint: android-compose-review.md
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    val message = error.message.orEmpty()
    assertContains(message, "addon_usage")
    assertContains(message, "code-review/not-a-skill")
    assertContains(message, "declared skill directory")
  }

  @Test
  fun `retired quality-check addon_usage without a declared checker does not block pack load`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      declared_files:
        baseline: code-review/bill-scenarioslug-code-review/content.md
        areas: {}
      addon_usage:
        quality-check/bill-scenarioslug-code-check:
          - slug: android-r8
            entrypoint: android-r8-review.md
      """.trimIndent()

    loadPackFromInMemory("scenarioslug", manifest)
  }

  @Test
  fun `coherence rule addon_usage must reference declared pointer under same skill dir`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      declared_files:
        baseline: code-review/something/content.md
        areas: {}
      addon_usage:
        code-review/something:
          - slug: android-compose
            entrypoint: android-compose-review.md
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    val message = error.message.orEmpty()
    assertContains(message, "addon_usage")
    assertContains(message, "android-compose-review.md")
    assertContains(message, "pointers[code-review/something]")
  }

  @Test
  fun `coherence rule addon_usage must reference pack-owned addon pointer targets`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      declared_files:
        baseline: code-review/something/content.md
        areas: {}
      pointers:
        code-review/something:
          - name: shell-ceremony.md
            target: orchestration/shell-content-contract/shell-ceremony.md
      addon_usage:
        code-review/something:
          - slug: shell-help
            entrypoint: shell-ceremony.md
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    val message = error.message.orEmpty()
    assertContains(message, "addon_usage")
    assertContains(message, "shell-ceremony.md")
    assertContains(message, "platform-packs/scenarioslug/addons/")
  }

  @Test
  fun `coherence rule addon_usage rejects duplicate slug per skill dir`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      declared_files:
        baseline: code-review/something/content.md
        areas: {}
      pointers:
        code-review/something:
          - name: android-compose-review.md
            target: platform-packs/scenarioslug/addons/android-compose-review.md
          - name: android-compose-edge-to-edge.md
            target: platform-packs/scenarioslug/addons/android-compose-edge-to-edge.md
      addon_usage:
        code-review/something:
          - slug: android-compose
            entrypoint: android-compose-review.md
          - slug: android-compose
            entrypoint: android-compose-edge-to-edge.md
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    val message = error.message.orEmpty()
    assertContains(message, "android-compose")
    assertContains(message, "duplicate")
  }

  @Test
  fun `feature_addon_usage wrong type fails schema rule`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      feature_addon_usage: "not a mapping"
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    assertContains(error.message.orEmpty(), "feature_addon_usage")
  }

  @Test
  fun `feature_addon_usage unknown nested key fails schema rule`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      pointers:
        feature-task:
          - name: android-compose-implementation.md
            target: platform-packs/scenarioslug/addons/android-compose-implementation.md
      feature_addon_usage:
        feature-task:
          - slug: android-compose-implementation
            entrypoint: android-compose-implementation.md
            unexpected: true
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    val message = error.message.orEmpty()
    assertContains(message, "feature_addon_usage")
    assertContains(message, "unexpected")
  }

  @Test
  fun `feature_addon_usage pointer targeting nonexistent addon file fails loudly`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      pointers:
        feature-task:
          - name: android-compose-implementation.md
            target: platform-packs/scenarioslug/addons/android-compose-implementation.md
      feature_addon_usage:
        feature-task:
          - slug: android-compose-implementation
            entrypoint: android-compose-implementation.md
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    val message = error.message.orEmpty()
    assertContains(message, "feature_addon_usage")
    assertContains(message, "android-compose-implementation.md")
    assertContains(message, "does not exist")
  }

  @Test
  fun `feature_addon_usage is typed and excluded from custom fields`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      pointers:
        feature-task:
          - name: android-compose-implementation.md
            target: platform-packs/scenarioslug/addons/android-compose-implementation.md
      feature_addon_usage:
        feature-task:
          - slug: android-compose-implementation
            entrypoint: android-compose-implementation.md
      fork_field: "custom"
      """.trimIndent()
    val packRoot = newTempPackRoot("scenarioslug", manifest)
    val addon = packRoot.resolve("addons/android-compose-implementation.md")
    Files.createDirectories(addon.parent)
    Files.writeString(addon, "# Android Compose implementation\n")

    val pack = loadPlatformManifest(packRoot)

    assertContains(pack.featureAddonUsage.single().consumer, "feature-task")
    assertContains(pack.featureAddonUsage.single().addons.single().entrypoint, "android-compose-implementation.md")
    assertFalse("feature_addon_usage" in pack.customFields)
    assertContains(pack.customFields.keys, "fork_field")
  }

  @Test
  fun `SKILL-48 nested anchored block typo fails loudly`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas:
        - architecture
      declared_files:
        baselin: code-review/content.md
        areas:
          architecture: code-review/architecture/content.md
      area_metadata:
        architecture:
          focus: "architecture"
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    val message = error.message.orEmpty()

    assertContains(message, "declared_files")
    assertContains(message, "baselin")
  }

  @Test
  fun `SKILL-48 Subtask 3 typo on anchored top-level field fails loudly with field path`() {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      declared_filez:
        baseline: code-review/content.md
      """.trimIndent()
    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        loadPackFromInMemory("scenarioslug", manifest)
      }
    val message = error.message.orEmpty()
    assertContains(message, "declared_filez")
    assertContains(message, "declared_files")
  }

  @Test
  fun `quality-check override declaring the skill-bill parent passes governed skill validation`() {
    val (pack, qualityCheck) = qualityCheckOverridePack(internalFor = PACK_SIDECAR_PARENT_SKILL)

    validateGovernedSkill(pack, "declared_quality_check_file", qualityCheck, "quality-check")
  }

  @Test
  fun `quality-check override declaring a retired parent fails governed skill validation`() {
    val (pack, qualityCheck) = qualityCheckOverridePack(internalFor = "bill-code-check")

    val error =
      assertFailsWith<InvalidManifestSchemaError> {
        validateGovernedSkill(pack, "declared_quality_check_file", qualityCheck, "quality-check")
      }
    assertContains(error.message.orEmpty(), "internal-for: $PACK_SIDECAR_PARENT_SKILL")
  }

  private fun qualityCheckOverridePack(internalFor: String): Pair<PlatformManifest, Path> {
    val manifest =
      """
      platform: scenarioslug
      contract_version: "1.8"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      declared_quality_check_file: quality-check/bill-scenarioslug-code-check/content.md
      """.trimIndent()
    val packRoot = newTempPackRoot("scenarioslug", manifest)
    val qualityCheck = packRoot.resolve("quality-check/bill-scenarioslug-code-check/content.md")
    Files.createDirectories(qualityCheck.parent)
    Files.writeString(
      qualityCheck,
      """
      ---
      name: bill-scenarioslug-code-check
      description: Scenario quality-check override.
      internal-for: $internalFor
      ---

      # Scenario Quality Check

      Run the scenario stack checks and report root causes first.
      """.trimIndent() + "\n",
    )
    val pack = loadPlatformManifest(packRoot)
    return pack to requireNotNull(pack.declaredQualityCheckFile).toPath()
  }

  private fun loadPackFromInMemory(
    slug: String,
    manifest: String,
  ) {
    val packRoot = newTempPackRoot(slug, manifest)

    loadPlatformManifest(packRoot)
  }

  private fun loadPackThroughContractGate(
    slug: String,
    manifest: String,
  ) {
    val packRoot = newTempPackRoot(slug, manifest)

    loadPlatformPack(packRoot)
  }

  private fun newTempPackRoot(
    slug: String,
    manifest: String,
  ): Path {
    val tempDir = Files.createTempDirectory("skillbill-platform-pack-schema-test-")
    val packRoot = tempDir.resolve("platform-packs").resolve(slug)
    Files.createDirectories(packRoot)
    Files.writeString(packRoot.resolve("platform.yaml"), manifest)
    return packRoot
  }
}
