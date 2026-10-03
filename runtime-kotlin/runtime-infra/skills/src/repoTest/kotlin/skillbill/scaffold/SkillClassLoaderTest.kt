package skillbill.scaffold

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.error.shellcontent.ManifestFailureCode
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformManifest
import skillbill.infrastructure.skills.scaffold.platformpack.loader.skillclass.SKILL_CLASSES_DIR
import skillbill.infrastructure.skills.scaffold.platformpack.loader.skillclass.discoverSkillClasses
import skillbill.infrastructure.skills.scaffold.platformpack.loader.skillclass.resolveSkillClass
import skillbill.infrastructure.skills.scaffold.rendering.renderCeremonySection
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.SHELL_CONTRACT_VERSION
import skillbill.infrastructure.skills.scaffold.runtime.service.support.requiredSupportingFilesForSkill
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.model.SkillClassManifest
import skillbill.scaffold.model.SkillClassMatcher

private val LEGACY_POINTER_GOLDEN: Map<String, Set<String>> =
  mapOf(
    "skill-bill" to
      setOf(
        "peak-hours-warner.md",
        "shell-ceremony.md",
        "telemetry-contract.md",
      ),
  )

private val LEGACY_PATTERN_GOLDEN: List<Pair<Regex, Set<String>>> =
  listOf(
    Regex("^bill-[a-z0-9-]+-code-review-[a-z0-9-]+$") to
      setOf(
        "specialist-contract.md",
      ),
    Regex("^bill-[a-z0-9-]+-code-review$") to
      setOf(
        "review-scope.md",
        "stack-routing.md",
        "review-orchestrator.md",
        "specialist-contract.md",
        "review-delegation.md",
        "telemetry-contract.md",
        "shell-ceremony.md",
      ),
  )

private fun goldenPointerSetFor(skillName: String): Set<String>? =
  LEGACY_POINTER_GOLDEN[skillName]
    ?: LEGACY_PATTERN_GOLDEN.firstOrNull { (pattern, _) -> pattern.matches(skillName) }?.second

private const val FEATURE_ENTRY_CEREMONY_GOLDEN = """
## Ceremony

Before launch, follow [peak-hours-warner.md](peak-hours-warner.md).

Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).

When telemetry applies, follow [telemetry-contract.md](telemetry-contract.md).
"""

class SkillClassLoaderTest {
  @Test
  fun `valid class manifest round-trips through the loader`() {
    val repoRoot = Files.createTempDirectory("skill-class-loader-valid")
    val classesDir = repoRoot.resolve(SKILL_CLASSES_DIR)
    Files.createDirectories(classesDir)
    Files.writeString(
      classesDir.resolve("widget-shell.yaml"),
      """
      class: widget-shell
      contract_version: "1.8"
      matchers:
        - exact: bill-widget
      pointers:
        - shell-ceremony
        - telemetry-contract
      sections:
        - heading: Setup
          body: |
            Inspect the widget repo before routing.
            Resolve scope first.
      ceremony_lines:
        - "Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md)."
      """.trimIndent() + "\n",
    )

    val classes = discoverSkillClasses(repoRoot)

    assertEquals(1, classes.size)
    val manifest = classes.single()
    assertEquals("widget-shell", manifest.classId)
    assertEquals("1.8", manifest.contractVersion)
    assertEquals(listOf("shell-ceremony", "telemetry-contract"), manifest.pointers)
    assertEquals(1, manifest.sections.size)
    assertEquals("Setup", manifest.sections.single().heading)
    assertTrue(manifest.sections.single().body.startsWith("Inspect the widget repo"))
    assertEquals(1, manifest.ceremonyLines.size)
  }

  @Test
  fun `exact and pattern matchers resolve the right class`() {
    val classes =
      listOf(
        manifest(
          "code-review-shell",
          matchers = listOf("exact" to "bill-code-review"),
        ),
        manifest(
          "code-review-orchestrator",
          matchers = listOf("pattern" to "^bill-[a-z0-9-]+-code-review$"),
          excludeExact = listOf("bill-code-review"),
        ),
        manifest(
          "code-review-specialist",
          matchers = listOf("pattern" to "^bill-[a-z0-9-]+-code-review-[a-z0-9-]+$"),
        ),
      )

    assertEquals("code-review-shell", resolveSkillClass("bill-code-review", classes)?.classId)
    assertEquals("code-review-orchestrator", resolveSkillClass("bill-kotlin-code-review", classes)?.classId)
    assertEquals(
      "code-review-specialist",
      resolveSkillClass("bill-kotlin-code-review-security", classes)?.classId,
    )
    assertNull(resolveSkillClass("bill-unmatched", classes))
  }

  @Test
  fun `ambiguous matches loud-fail`() {
    val classes =
      listOf(
        manifest("a", matchers = listOf("pattern" to "^bill-thing$")),
        manifest("b", matchers = listOf("exact" to "bill-thing")),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        resolveSkillClass("bill-thing", classes)
      }.also { failure ->
        assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
      }
    assertTrue(error.message.orEmpty().contains("matches more than one class"), error.message.orEmpty())
  }

  @Test
  fun `class id must match filename`() {
    val repoRoot = Files.createTempDirectory("skill-class-loader-name-mismatch")
    val classesDir = repoRoot.resolve(SKILL_CLASSES_DIR)
    Files.createDirectories(classesDir)
    Files.writeString(
      classesDir.resolve("widget-shell.yaml"),
      """
      class: gadget-shell
      contract_version: "1.8"
      matchers:
        - exact: bill-widget
      """.trimIndent() + "\n",
    )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        discoverSkillClasses(repoRoot)
      }.also { failure ->
        assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
      }
    assertTrue(error.message.orEmpty().contains("expected 'widget-shell' to match the filename"))
  }

  @Test
  fun `contract version mismatch loud-fails`() {
    val repoRoot = Files.createTempDirectory("skill-class-loader-contract")
    val classesDir = repoRoot.resolve(SKILL_CLASSES_DIR)
    Files.createDirectories(classesDir)
    Files.writeString(
      classesDir.resolve("widget-shell.yaml"),
      """
      class: widget-shell
      contract_version: "2.0"
      matchers:
        - exact: bill-widget
      """.trimIndent() + "\n",
    )
    assertFailsWith<SkillBillRuntimeException> { discoverSkillClasses(repoRoot) }
      .also { assertEquals(InstallFailureCode.CONTRACT_VERSION_MISMATCH, it.code) }
  }

  @Test
  fun `missing matchers loud-fail`() {
    val repoRoot = Files.createTempDirectory("skill-class-loader-no-matchers")
    val classesDir = repoRoot.resolve(SKILL_CLASSES_DIR)
    Files.createDirectories(classesDir)
    Files.writeString(
      classesDir.resolve("widget-shell.yaml"),
      """
      class: widget-shell
      contract_version: "1.8"
      """.trimIndent() + "\n",
    )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        discoverSkillClasses(repoRoot)
      }.also { failure ->
        assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
      }
    assertTrue(error.message.orEmpty().contains("'matchers' is missing"))
  }

  @Test
  fun `missing classes directory loud-fails`() {
    val repoRoot = Files.createTempDirectory("skill-class-loader-empty-repo")
    assertFailsWith<SkillBillRuntimeException> {
      discoverSkillClasses(repoRoot)
    }.also { failure ->
      assertEquals(ManifestFailureCode.MISSING_MANIFEST, failure.code)
    }
  }

  @Test
  fun `every shipped governed skill resolves to exactly one class and matches legacy pointer set`() {
    val repoRoot = currentRepoRootForClassLoader()
    val classes = discoverSkillClasses(repoRoot)
    val governedSkills = shippedGovernedSkillNames(repoRoot)
    assertTrue(governedSkills.isNotEmpty(), "expected to find governed skills under $repoRoot")

    val mismatches = mutableListOf<String>()
    governedSkills.forEach { skillName ->
      val resolved =
        try {
          resolveSkillClass(skillName, classes)
        } catch (error: Throwable) {
          mismatches += "$skillName: resolveSkillClass threw ${error.message}"
          return@forEach
        }
      val expected = goldenPointerSetFor(skillName)
      if (resolved == null) {
        if (expected != null) {
          mismatches += "$skillName: no class matched but golden table expected ${expected.sorted()}"
        }
        return@forEach
      }
      val yamlDerived = resolved.pointers.map { "$it.md" }.toSet()
      if (expected != null && expected != yamlDerived) {
        mismatches += "$skillName: golden=${expected.sorted()} yaml=${yamlDerived.sorted()}"
      } else if (expected == null) {
        mismatches += "$skillName: class matched (${resolved.classId}) but no golden entry — add one to LEGACY_*_GOLDEN"
      }
    }
    assertTrue(mismatches.isEmpty(), "Class manifest pointer set mismatches:\n${mismatches.joinToString("\n")}")
  }

  @Test
  fun `kmp feature-task routed support pointers preserve pre-change ordering`() {
    val repoRoot = currentRepoRootForClassLoader()
    val kmpManifest = loadPlatformManifest(repoRoot.resolve("platform-packs/kmp"))

    val pointers = requiredSupportingFilesForSkill("skill-bill", repoRoot, listOf(kmpManifest))

    assertEquals(
      listOf(
        "peak-hours-warner.md",
        "shell-ceremony.md",
        "telemetry-contract.md",
        "android-compose-implementation.md",
        "android-navigation-implementation.md",
        "android-interop-implementation.md",
        "android-design-system-implementation.md",
        "android-r8-implementation.md",
        "android-compose-edge-to-edge.md",
        "android-compose-adaptive-layouts.md",
      ),
      pointers,
    )
  }

  @Test
  fun `every shipped class manifest loads cleanly`() {
    val repoRoot = currentRepoRootForClassLoader()
    val classes = discoverSkillClasses(repoRoot)
    assertTrue(classes.isNotEmpty(), "expected at least one shipped class manifest in $repoRoot/$SKILL_CLASSES_DIR")
    classes.forEach { manifest ->
      assertNotNull(manifest.classFile)
      assertEquals(SHELL_CONTRACT_VERSION, manifest.contractVersion)
      assertTrue(manifest.matchers.isNotEmpty(), "${manifest.classId} must declare at least one matcher")
    }
  }

  @Test
  fun `feature entry class owns merged ceremony`() {
    val repoRoot = currentRepoRootForClassLoader()
    val classes = discoverSkillClasses(repoRoot)
    val manifest = resolveSkillClass("skill-bill", classes)

    assertEquals("feature-launch-warning", manifest?.classId)
    assertTrue(Files.isRegularFile(repoRoot.resolve("$SKILL_CLASSES_DIR/feature-launch-warning.yaml")))
    assertTrue(!Files.exists(repoRoot.resolve("$SKILL_CLASSES_DIR/feature-task.yaml")))
    assertEquals(FEATURE_ENTRY_CEREMONY_GOLDEN.trimStart(), renderCeremonySection(manifest))
  }

  @Test
  fun `leftover pack checker names do not match a quality-check leaf class`() {
    val classes = discoverSkillClasses(currentRepoRootForClassLoader())
    assertNull(resolveSkillClass("bill-kotlin-code-check", classes))
  }

  private fun manifest(
    classId: String,
    matchers: List<Pair<String, String>>,
    excludeExact: List<String> = emptyList(),
  ): SkillClassManifest {
    return SkillClassManifest(
      classId = classId,
      classFile = Path.of("/tmp/$classId.yaml").toFileLocation(),
      contractVersion = SHELL_CONTRACT_VERSION,
      matchers =
        matchers.map { (kind, value) ->
          when (kind) {
            "exact" -> SkillClassMatcher(exact = value, excludeExact = excludeExact)
            "pattern" -> SkillClassMatcher(pattern = Regex(value), excludeExact = excludeExact)
            else -> error("unknown matcher kind $kind")
          }
        },
      pointers = emptyList(),
      sections = emptyList(),
      ceremonyLines = emptyList(),
    )
  }

  private fun shippedGovernedSkillNames(repoRoot: Path): List<String> {
    val names = mutableListOf<String>()
    collectSkillsUnder(repoRoot.resolve("skills"), names)
    val packs = childDirsOf(repoRoot.resolve("platform-packs"))
    packs.forEach { pack ->
      collectSkillsUnder(pack.resolve("code-review"), names)
    }
    return names.sorted()
  }

  private fun childDirsOf(dir: Path): List<Path> {
    if (!Files.isDirectory(dir)) return emptyList()
    return Files.list(dir).use { stream ->
      stream.filter { Files.isDirectory(it) }.toList()
    }
  }

  private fun collectSkillsUnder(
    dir: Path,
    names: MutableList<String>,
  ) {
    if (!Files.isDirectory(dir)) return
    Files.list(dir).use { stream ->
      stream
        .filter { path -> Files.isDirectory(path) && Files.isRegularFile(path.resolve("content.md")) }
        .forEach { path -> names += path.fileName.toString() }
    }
  }

  private fun currentRepoRootForClassLoader(): Path {
    var current: Path? = Path.of("").toAbsolutePath().normalize()
    while (current != null) {
      if (Files.isRegularFile(current.resolve("runtime-kotlin/settings.gradle.kts")) &&
        Files.isDirectory(current.resolve("skills")) &&
        Files.isDirectory(current.resolve("platform-packs"))
      ) {
        return current
      }
      current = current.parent
    }
    error("Could not locate skill-bill repo root for SkillClassLoaderTest")
  }
}
