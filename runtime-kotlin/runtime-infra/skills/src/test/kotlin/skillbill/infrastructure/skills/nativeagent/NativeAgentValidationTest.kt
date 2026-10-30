package skillbill.infrastructure.skills.nativeagent

import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentSource
import skillbill.infrastructure.skills.nativeagent.composition.parseNativeAgentSource
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentCompositionTargetSource
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentInstallRenderRequest
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.infrastructure.skills.nativeagent.rendering.discoverRepoNativeAgentSourceEntries
import skillbill.infrastructure.skills.nativeagent.rendering.discoverRepoNativeAgentSources
import skillbill.infrastructure.skills.nativeagent.validation.validateRepoNativeAgents
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeAgentValidationTest {
  @Test
  fun `provider-agnostic body passes validator`() {
    val repo = newRepoWithSource(body = "# Worker\n\nDo the work.")
    val report = validateRepoNativeAgents(repo, testNativeAgentCompositionContext(repo))
    assertTrue(
      report.passed,
      "Expected report to pass, got issues:\n${report.issues.joinToString("\n")}",
    )
  }

  @Test
  fun `body with claude handlebars conditional is rejected`() {
    assertProviderConditionalRejected("# Worker\n\n{{#claude}}claude only{{/claude}}")
  }

  @Test
  fun `body with codex handlebars conditional is rejected`() {
    assertProviderConditionalRejected("# Worker\n\n{{#codex}}codex only{{/codex}}")
  }

  @Test
  fun `body with cursor handlebars conditional is rejected`() {
    assertProviderConditionalRejected("# Worker\n\n{{#cursor}}cursor only{{/cursor}}")
  }

  @Test
  fun `body with junie handlebars conditional is rejected`() {
    assertProviderConditionalRejected("# Worker\n\n{{#junie}}junie only{{/junie}}")
  }

  @Test
  fun `body with case-insensitive 'if provider ==' is rejected`() {
    assertProviderConditionalRejected("# Worker\n\nIF Provider == claude { ... }")
  }

  @Test
  fun `body with case-insensitive 'if (provider' is rejected`() {
    assertProviderConditionalRejected("# Worker\n\nif (Provider) { ... }")
  }

  private fun assertProviderConditionalRejected(body: String) {
    val repo = newRepoWithSource(body = body)
    val report = validateRepoNativeAgents(repo, testNativeAgentCompositionContext(repo))
    assertFalse(report.passed, "Expected validator to reject body: $body")
    val expectedTail = "native agent bodies must be provider-agnostic; conditionals belong in the renderer"
    assertTrue(
      report.issues.any { it.endsWith(expectedTail) },
      "Expected an issue ending with '$expectedTail'; got:\n${report.issues.joinToString("\n")}",
    )
  }

  private fun newRepoWithSource(body: String): Path {
    val repo = Files.createTempDirectory("skillbill-validation-test")
    val skillDir = repo.resolve("skills/bill-validation-fixture/native-agents")
    Files.createDirectories(skillDir)
    val sourceFile = skillDir.resolve("bill-validation-fixture.md")
    val text =
      "---\n" +
        "name: bill-validation-fixture\n" +
        "description: Validation fixture.\n" +
        "---\n\n" +
        body + "\n"
    Files.writeString(sourceFile, text)
    return repo
  }

  @Test
  fun `passing fixture issues list is empty`() {
    val repo = newRepoWithSource(body = "# Worker\n\nDo the work.")
    val report = validateRepoNativeAgents(repo, testNativeAgentCompositionContext(repo))
    assertEquals(emptyList(), report.issues)
  }

  @Test
  fun `governed content composition resolves through platform manifest declared files`() {
    val repo = newRepoWithComposedPlatformAgent(writeAreaContent = true)
    val sourcePath = discoverRepoNativeAgentSources(repo).single()
    val source = parseNativeAgentSource(sourcePath)

    val target = testResolveNativeAgentCompositionTarget(repo, source)
    val expectedTarget =
      repo.resolve(
        "platform-packs/fixture/code-review/bill-fixture-code-review-architecture/content.md",
      )

    assertEquals(expectedTarget, target?.contentPath)
    assertEquals(NativeAgentCompositionTargetSource.PlatformManifest, target?.source)
    assertEquals(emptyList(), validateRepoNativeAgents(repo, testNativeAgentCompositionContext(repo)).issues)
  }

  @Test
  fun `missing governed content composition target is reported as validation issue`() {
    val repo = newRepoWithComposedPlatformAgent(writeAreaContent = false)

    val report = validateRepoNativeAgents(repo, testNativeAgentCompositionContext(repo))

    assertFalse(report.passed)
    val issue = report.issues.single()
    assertContains(issue, "bill-fixture-code-review-architecture")
    assertContains(issue, "content.md")
  }

  @Test
  fun `platform pack composition does not fall back to undeclared sibling content`() {
    val repo =
      newRepoWithComposedPlatformAgent(
        writeAreaContent = true,
        declaredSourceName = "bill-fixture-unregistered",
      )

    val report = validateRepoNativeAgents(repo, testNativeAgentCompositionContext(repo))

    assertFalse(report.passed)
    val issue = report.issues.single()
    assertContains(issue, "could not resolve a corresponding content.md")
    assertContains(issue, "bill-fixture-unregistered")
  }

  @Test
  fun `malformed composition directive is reported as validation issue`() {
    val repo = newRepoWithComposedPlatformAgent(writeAreaContent = true, composeDirective = "local-file")

    val report = validateRepoNativeAgents(repo, testNativeAgentCompositionContext(repo))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any { issue -> "unsupported native agent compose directive 'local-file'" in issue },
      "Expected malformed compose directive issue, got:\n${report.issues.joinToString("\n")}",
    )
  }

  @Test
  fun `missing platform manifest for composed platform native agent is reported as validation issue`() {
    val repo = newRepoWithComposedPlatformAgent(writeAreaContent = true)
    Files.delete(repo.resolve("platform-packs/fixture/platform.yaml"))

    val report = validateRepoNativeAgents(repo, testNativeAgentCompositionContext(repo))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any { issue -> "expected manifest" in issue && "platform.yaml" in issue },
      "Expected missing manifest issue, got:\n${report.issues.joinToString("\n")}",
    )
  }

  @Test
  fun `version mismatched platform manifest for composed native agent is reported as validation issue`() {
    val repo = newRepoWithComposedPlatformAgent(writeAreaContent = true)
    val manifest = repo.resolve("platform-packs/fixture/platform.yaml")
    Files.writeString(
      manifest,
      Files.readString(manifest).replace("contract_version: \"1.8\"", "contract_version: \"9.99\""),
    )

    val report = validateRepoNativeAgents(repo, testNativeAgentCompositionContext(repo))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any { issue ->
        "declares contract_version '9.99'" in issue && "shell expects '1.8'" in issue
      },
      "Expected contract version mismatch issue, got:\n${report.issues.joinToString("\n")}",
    )
  }

  @Test
  fun `native agent source discovery still returns composed source in place`() {
    val repo = newRepoWithComposedPlatformAgent(writeAreaContent = true)

    val sources = discoverRepoNativeAgentSources(repo)

    assertEquals(
      listOf(
        repo.resolve(
          "platform-packs/fixture/code-review/bill-fixture-code-review/native-agents/" +
            "bill-fixture-code-review-architecture.md",
        ),
      ),
      sources,
    )
  }

  @Test
  fun `provider render output contains composed governed content without mutating source file`() {
    val repo = newRepoWithComposedPlatformAgent(writeAreaContent = true, localFraming = "Use delegated mode.")
    val sourcePath = discoverRepoNativeAgentSources(repo).single()
    val source = parseNativeAgentSource(sourcePath)

    val composed = testComposeNativeAgentSource(repo, source)
    val rendered = NativeAgentProvider.Claude.render(composed)

    assertContains(rendered, "Use delegated mode.")
    assertContains(rendered, "Use this governed content.")
    assertContains(Files.readString(sourcePath), "compose: governed-content")
    assertTrue(Files.exists(sourcePath), "source file must remain in place after composition")
  }

  @Test
  fun `sibling governed content composition inlines sibling markdown sidecars`() {
    val repo = Files.createTempDirectory("skillbill-sibling-composition-test")
    val skillDir = repo.resolve("skills/bill-sibling")
    writeContent(
      skillDir.resolve("content.md"),
      "bill-sibling",
      body = "# Sibling\n\nRead [rubric.md](rubric.md) before reviewing.",
    )
    Files.writeString(skillDir.resolve("rubric.md"), "# Rubric\n\nSibling-only rubric.\n")
    val nativeAgentDir = skillDir.resolve("native-agents")
    Files.createDirectories(nativeAgentDir)
    Files.writeString(
      nativeAgentDir.resolve("bill-sibling.md"),
      "---\n" +
        "name: bill-sibling\n" +
        "description: Sibling worker.\n" +
        "compose: governed-content\n" +
        "---\n",
    )

    val source = parseNativeAgentSource(nativeAgentDir.resolve("bill-sibling.md"))
    val rendered = NativeAgentProvider.Claude.render(testComposeNativeAgentSource(repo, source))

    assertContains(rendered, "Read rubric.md before reviewing.")
    assertContains(rendered, "## Inlined Reference: rubric.md")
    assertContains(rendered, "Sibling-only rubric.")
    assertFalse("(rubric.md)" in rendered)
  }

  @Test
  fun `install render output is self contained with declared KMP sidecars inlined recursively`() {
    val repo = newRepoWithKmpPointerSidecars()

    val result =
      NativeAgentOperations.renderInstallArtifacts(
        NativeAgentInstallRenderRequest(
          platformPacksRoot = repo.resolve("platform-packs"),
          skillsRoot = null,
          selectedPlatforms = listOf("kmp"),
          provider = NativeAgentProvider.Claude,
          home = repo.resolve("home"),
          compositionContext = testNativeAgentCompositionContext(repo),
        ),
      )

    val rendered = Files.readString(result.generatedFiles.single())
    assertContains(rendered, "Scan sidecar.md first.")
    assertContains(rendered, "Sidecar details.")
    assertContains(rendered, "Nested details.")
    assertFalse("(sidecar.md)" in rendered)
    assertFalse("(nested.md)" in rendered)
  }

  @Test
  fun `install render rejects malformed composition directives before writing provider output`() {
    val repo = newRepoWithComposedPlatformAgent(writeAreaContent = true, composeDirective = "local-file")
    val home = repo.resolve("home")

    val error =
      assertFailsWith<IllegalArgumentException> {
        NativeAgentOperations.renderInstallArtifacts(
          NativeAgentInstallRenderRequest(
            platformPacksRoot = repo.resolve("platform-packs"),
            skillsRoot = null,
            selectedPlatforms = listOf("fixture"),
            provider = NativeAgentProvider.Claude,
            home = home,
            compositionContext = testNativeAgentCompositionContext(repo),
          ),
        )
      }

    assertContains(error.message.orEmpty(), "unsupported native agent compose directive 'local-file'")
    assertFalse(
      Files.exists(home.resolve(".skill-bill/native-agents")),
      "invalid install must not write provider output",
    )
  }

  @Test
  fun `unresolved local markdown links in composed content are rejected`() {
    val repo = newRepoWithKmpPointerSidecars(declareSidecarPointer = false)

    val report = validateRepoNativeAgents(repo, testNativeAgentCompositionContext(repo))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any { issue -> "local markdown link 'sidecar.md' is not declared" in issue },
      "Expected unresolved local markdown link issue, got:\n${report.issues.joinToString("\n")}",
    )
  }

  @Test
  fun `checked in generated provider dirs are rejected`() {
    val repo = newRepoWithSource(body = "# Worker\n\nDo the work.")
    val generatedDir = Files.createDirectories(repo.resolve("skills/bill-validation-fixture/claude-agents"))
    Files.writeString(generatedDir.resolve("bill-validation-fixture.md"), "generated\n")

    val report = validateRepoNativeAgents(repo, testNativeAgentCompositionContext(repo))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any { issue -> "generated native agent artifacts must not be checked in" in issue },
      "Expected checked-in generated artifact issue, got:\n${report.issues.joinToString("\n")}",
    )
  }

  @Test
  fun `checked in generated provider dirs are rejected when sources are bundled`() {
    val repo = Files.createTempDirectory("skillbill-validation-bundle-generated")
    val nativeAgentDir = Files.createDirectories(repo.resolve("skills/bill-validation-fixture/native-agents"))
    Files.writeString(
      nativeAgentDir.resolve("agents.yaml"),
      """
      agents:
        - name: bill-validation-fixture
          description: Validation fixture.
          body: |-
            # Worker

            Do the work.
      """.trimIndent() + "\n",
    )
    val generatedDir = Files.createDirectories(repo.resolve("skills/bill-validation-fixture/codex-agents"))
    Files.writeString(generatedDir.resolve("bill-validation-fixture.toml"), "generated\n")

    val report = validateRepoNativeAgents(repo, testNativeAgentCompositionContext(repo))

    assertFalse(report.passed)
    assertTrue(
      report.issues.any { issue -> "generated native agent artifacts must not be checked in" in issue },
      "Expected checked-in generated artifact issue, got:\n${report.issues.joinToString("\n")}",
    )
  }

  @Test
  fun `composition is manifest driven for arbitrary platform slugs`() {
    val repo =
      newRepoWithComposedPlatformAgent(
        platformSlug = "swift",
        writeAreaContent = true,
        declaredSourceName = "bill-swift-code-review-architecture",
      )
    val sourcePath = discoverRepoNativeAgentSources(repo).single()
    val source = parseNativeAgentSource(sourcePath)

    val rendered = NativeAgentProvider.Claude.render(testComposeNativeAgentSource(repo, source))

    assertContains(rendered, "Use this governed content.")
    assertEquals(emptyList(), validateRepoNativeAgents(repo, testNativeAgentCompositionContext(repo)).issues)
  }

  @Test
  fun `bundled platform composition resolves through manifest declared files`() {
    val repo = newRepoWithComposedPlatformAgent(writeAreaContent = true)
    val nativeAgentDir = repo.resolve("platform-packs/fixture/code-review/bill-fixture-code-review/native-agents")
    Files.delete(nativeAgentDir.resolve("bill-fixture-code-review-architecture.md"))
    Files.writeString(
      nativeAgentDir.resolve("agents.yaml"),
      """
      agents:
        - name: bill-fixture-code-review
          description: Baseline worker.
          compose: governed-content
        - name: bill-fixture-code-review-architecture
          description: Architecture worker.
          compose: governed-content
      """.trimIndent() + "\n",
    )

    val source =
      discoverRepoNativeAgentSourceEntries(repo)
        .single { source -> source.name == "bill-fixture-code-review-architecture" }
    val target = testResolveNativeAgentCompositionTarget(repo, source)

    assertEquals(
      repo.resolve("platform-packs/fixture/code-review/bill-fixture-code-review-architecture/content.md"),
      target?.contentPath,
    )
    assertEquals(NativeAgentCompositionTargetSource.PlatformManifest, target?.source)
    assertEquals(emptyList(), validateRepoNativeAgents(repo, testNativeAgentCompositionContext(repo)).issues)
  }

  @Test
  fun `bundled platform composition rejects undeclared agent structurally`() {
    val repo =
      newRepoWithComposedPlatformAgent(
        writeAreaContent = true,
        declaredSourceName = "bill-fixture-unregistered",
      )
    val nativeAgentDir = repo.resolve("platform-packs/fixture/code-review/bill-fixture-code-review/native-agents")
    Files.delete(nativeAgentDir.resolve("bill-fixture-unregistered.md"))
    Files.writeString(
      nativeAgentDir.resolve("agents.yaml"),
      """
      agents:
        - name: bill-fixture-code-review
          description: Baseline worker.
          compose: governed-content
        - name: bill-fixture-unregistered
          description: Undeclared worker.
          compose: governed-content
      """.trimIndent() + "\n",
    )

    val report = validateRepoNativeAgents(repo, testNativeAgentCompositionContext(repo))

    assertFalse(report.passed)
    val issue = report.issues.single { issue -> "entry 'bill-fixture-unregistered'" in issue }
    assertContains(issue, "agents.yaml entry 'bill-fixture-unregistered'")
    assertContains(issue, "unknown=[bill-fixture-unregistered]")
  }

  @Test
  fun `bundled horizontal composition resolves only matching sibling content frontmatter`() {
    val repo = Files.createTempDirectory("skillbill-horizontal-bundle-composition")
    val skillDir = repo.resolve("skills/bill-horizontal")
    writeContent(skillDir.resolve("content.md"), "bill-horizontal", body = "# Horizontal\n\nUse sibling content.")
    val nativeAgentDir = Files.createDirectories(skillDir.resolve("native-agents"))
    Files.writeString(
      nativeAgentDir.resolve("agents.yaml"),
      """
      agents:
        - name: bill-horizontal
          description: Horizontal worker.
          compose: governed-content
      """.trimIndent() + "\n",
    )

    val source = discoverRepoNativeAgentSourceEntries(repo).single()
    val rendered = NativeAgentProvider.Claude.render(testComposeNativeAgentSource(repo, source))

    assertContains(rendered, "Use sibling content.")
    assertEquals(emptyList(), validateRepoNativeAgents(repo, testNativeAgentCompositionContext(repo)).issues)

    Files.writeString(
      skillDir.resolve("content.md"),
      Files.readString(skillDir.resolve("content.md")).replace("name: bill-horizontal", "name: bill-other"),
    )
    val report = validateRepoNativeAgents(repo, testNativeAgentCompositionContext(repo))
    assertFalse(report.passed)
    assertTrue(
      report.issues.any { issue -> "could not resolve a corresponding content.md" in issue },
      "Expected sibling frontmatter mismatch to fail, got:\n${report.issues.joinToString("\n")}",
    )
  }

  @Test
  fun `duplicate names loud fail across markdown and bundled sources`() {
    val repo = newRepoWithSource(body = "# Worker\n\nDo the work.")
    val nativeAgentDir = repo.resolve("skills/bill-validation-fixture/native-agents")
    Files.writeString(
      nativeAgentDir.resolve("agents.yaml"),
      """
      agents:
        - name: bill-validation-fixture
          description: Duplicate worker.
          body: Duplicate body.
      """.trimIndent() + "\n",
    )

    val report = validateRepoNativeAgents(repo, testNativeAgentCompositionContext(repo))

    assertFalse(report.passed)
    val issue = report.issues.single { "duplicates" in it }
    assertContains(issue, "agents.yaml entry 'bill-validation-fixture'")
    assertContains(issue, "bill-validation-fixture.md")
  }

  @Test
  fun `bundled custom body renders same provider output as markdown source`() {
    val body = "# Worker\n\nDo the work."
    val bundled =
      NativeAgentSource(
        name = "bill-custom",
        description = "Custom worker.",
        body = body,
        path = Path.of("native-agents/agents.yaml"),
        bundleEntryName = "bill-custom",
      )
    val markdown =
      NativeAgentSource(
        name = "bill-custom",
        description = "Custom worker.",
        body = body,
        path = Path.of("native-agents/bill-custom.md"),
      )

    NativeAgentProvider.entries.forEach { provider ->
      assertEquals(provider.render(markdown), provider.render(bundled), provider.name)
    }
  }

  @Test
  fun `bundled install output writes a self contained artifact for a declared review agent`() {
    val repo = newRepoWithComposedPlatformAgent(writeAreaContent = true)
    val nativeAgentDir = repo.resolve("platform-packs/fixture/code-review/bill-fixture-code-review/native-agents")
    Files.delete(nativeAgentDir.resolve("bill-fixture-code-review-architecture.md"))
    Files.writeString(
      nativeAgentDir.resolve("agents.yaml"),
      """
      agents:
        - name: bill-fixture-code-review
          description: Baseline worker.
          compose: governed-content
        - name: bill-fixture-code-review-architecture
          description: Architecture worker.
          compose: governed-content
      """.trimIndent() + "\n",
    )

    val result =
      NativeAgentOperations.renderInstallArtifacts(
        NativeAgentInstallRenderRequest(
          platformPacksRoot = repo.resolve("platform-packs"),
          skillsRoot = null,
          selectedPlatforms = listOf("fixture"),
          provider = NativeAgentProvider.Claude,
          home = repo.resolve("home"),
          compositionContext = testNativeAgentCompositionContext(repo),
        ),
      )

    assertEquals(
      listOf("bill-fixture-code-review-architecture.md", "bill-fixture-code-review.md"),
      result.generatedFiles.map { it.fileName.toString() }.sorted(),
    )
    val composed = Files.readString(result.generatedFiles.first { it.fileName.toString().contains("architecture") })
    assertContains(composed, "Use this governed content.")
    assertFalse("(sidecar.md)" in composed)
  }

  private fun newRepoWithComposedPlatformAgent(
    platformSlug: String = "fixture",
    writeAreaContent: Boolean,
    composeDirective: String = "governed-content",
    declaredSourceName: String = "bill-fixture-code-review-architecture",
    localFraming: String = "",
  ): Path {
    val repo = Files.createTempDirectory("skillbill-composed-agent-test")
    val packRoot = repo.resolve("platform-packs/$platformSlug")
    val declaredAreaName = "bill-$platformSlug-code-review-architecture"
    Files.createDirectories(packRoot)
    Files.writeString(
      packRoot.resolve("platform.yaml"),
      """
      platform: $platformSlug
      contract_version: "1.8"
      routing_signals:
        strong:
          - ".fixture"
        tie_breakers: []
      declared_code_review_areas:
        - architecture
      declared_files:
        baseline: code-review/bill-$platformSlug-code-review/content.md
        areas:
          architecture: code-review/bill-$platformSlug-code-review-architecture/content.md
      area_metadata:
        architecture:
          focus: "architecture review"
      """.trimIndent() + "\n",
    )
    writeContent(
      packRoot.resolve("code-review/bill-$platformSlug-code-review/content.md"),
      "bill-$platformSlug-code-review",
    )
    if (writeAreaContent) {
      writeContent(
        packRoot.resolve("code-review/bill-$platformSlug-code-review-architecture/content.md"),
        declaredAreaName,
      )
    }
    val nativeAgentDir = packRoot.resolve("code-review/bill-$platformSlug-code-review/native-agents")
    Files.createDirectories(nativeAgentDir)
    val sourceName = declaredSourceName
    Files.writeString(
      nativeAgentDir.resolve("$sourceName.md"),
      "---\n" +
        "name: $sourceName\n" +
        "description: Architecture worker.\n" +
        "compose: $composeDirective\n" +
        "---\n\n" +
        localFraming.trimEnd() + "\n",
    )
    if (sourceName != declaredAreaName) {
      writeContent(
        packRoot.resolve("code-review/$sourceName/content.md"),
        sourceName,
      )
    }
    return repo
  }

  private fun newRepoWithKmpPointerSidecars(declareSidecarPointer: Boolean = true): Path {
    val repo = Files.createTempDirectory("skillbill-kmp-sidecar-test")
    val packRoot = repo.resolve("platform-packs/kmp")
    Files.createDirectories(packRoot)
    val pointersBlock =
      if (declareSidecarPointer) {
        """
        pointers:
          code-review/bill-kmp-code-review-ui:
            - name: sidecar.md
              target: platform-packs/kmp/addons/sidecar.md
            - name: nested.md
              target: platform-packs/kmp/addons/nested.md
        """.trimIndent()
      } else {
        """
        pointers:
          code-review/bill-kmp-code-review-ui: []
        """.trimIndent()
      }
    Files.writeString(packRoot.resolve("platform.yaml"), kmpPointerSidecarManifest(pointersBlock))
    writeContent(packRoot.resolve("code-review/bill-kmp-code-review/content.md"), "bill-kmp-code-review")
    writeContent(
      packRoot.resolve("code-review/bill-kmp-code-review-ui/content.md"),
      "bill-kmp-code-review-ui",
      body = "# UI\n\nScan [sidecar.md](sidecar.md) first.",
    )
    val addons = Files.createDirectories(packRoot.resolve("addons"))
    Files.writeString(addons.resolve("sidecar.md"), "# Sidecar\n\nSidecar details. Read [nested.md](nested.md).\n")
    Files.writeString(addons.resolve("nested.md"), "# Nested\n\nNested details.\n")
    val nativeAgentDir =
      Files.createDirectories(
        packRoot.resolve("code-review/bill-kmp-code-review/native-agents"),
      )
    Files.writeString(
      nativeAgentDir.resolve("bill-kmp-code-review-ui.md"),
      "---\n" +
        "name: bill-kmp-code-review-ui\n" +
        "description: KMP UI worker.\n" +
        "compose: governed-content\n" +
        "---\n",
    )
    return repo
  }

  private fun kmpPointerSidecarManifest(pointersBlock: String): String =
    listOf(
      "platform: kmp",
      "contract_version: \"1.8\"",
      "routing_signals:",
      "  strong:",
      "    - \"commonMain\"",
      "  tie_breakers: []",
      "declared_code_review_areas:",
      "  - ui",
      "declared_files:",
      "  baseline: code-review/bill-kmp-code-review/content.md",
      "  areas:",
      "    ui: code-review/bill-kmp-code-review-ui/content.md",
      "area_metadata:",
      "  ui:",
      "    focus: \"ui review\"",
      pointersBlock,
    ).joinToString("\n") + "\n"

  private fun writeContent(
    path: Path,
    name: String,
    body: String = "# Fixture\n\nUse this governed content.",
  ) {
    Files.createDirectories(path.parent)
    Files.writeString(
      path,
      "---\n" +
        "name: $name\n" +
        "description: Fixture content.\n" +
        "---\n\n" +
        body.trimEnd() + "\n",
    )
  }
}
