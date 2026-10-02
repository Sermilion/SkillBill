package skillbill.infrastructure.skills.scaffold

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.AgentAddonFailureCode
import skillbill.error.shellcontent.InvalidScaffoldPayloadError
import skillbill.error.shellcontent.MissingRequiredSectionError
import skillbill.error.shellcontent.RetiredScaffoldKindError
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentInstallRenderRequest
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentOperations
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.infrastructure.skills.nativeagent.testNativeAgentCompositionContext
import skillbill.infrastructure.skills.scaffold.authoring.AuthoringTarget
import skillbill.infrastructure.skills.scaffold.authoring.renderAuthoringTarget
import skillbill.infrastructure.skills.scaffold.authoring.renderWrapper
import skillbill.infrastructure.skills.scaffold.authoring.resolveTarget
import skillbill.infrastructure.skills.scaffold.manifest.appendCodeReviewArea
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformPack
import skillbill.infrastructure.skills.scaffold.rendering.baselineReviewContent
import skillbill.infrastructure.skills.scaffold.rendering.canonicalSeverityCloser
import skillbill.infrastructure.skills.scaffold.rendering.defaultAreaFocus
import skillbill.infrastructure.skills.scaffold.rendering.inferSkillDescription
import skillbill.infrastructure.skills.scaffold.rendering.renderContentBody
import skillbill.infrastructure.skills.scaffold.rendering.renderNativeAgentSourceStub
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.TemplateContext
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.supportingFileTargets
import skillbill.infrastructure.skills.scaffold.runtime.service.planCodeReviewArea
import skillbill.infrastructure.skills.scaffold.runtime.service.renderDeclaredPackContentSheet
import skillbill.infrastructure.skills.scaffold.runtime.service.scaffold
import skillbill.infrastructure.skills.scaffold.runtime.service.support.requiredSupportingFilesForSkill
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import skillbill.model.toPath
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.policy.platformpack.model.PlatformPackManifestRenderRequest
import skillbill.scaffold.policy.platformpack.renderPlatformPackManifest
import skillbill.testsupport.SkillClassFixtures
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScaffoldServiceParityTest {
  @Test
  fun `horizontal subagent specialists emit runtime notes and native stubs`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val result =
        scaffold(
          payload(repo, "horizontal", "name" to "bill-foo-orchestrator") +
            mapOf("subagent_specialists" to listOf("foo-arch", "foo-perf")),
        )
      val skillDir = repo.resolve("skills").resolve("bill-foo-orchestrator")
      val content = Files.readString(skillDir.resolve("content.md"))
      val rendered = renderAuthoringTarget(repo, "bill-foo-orchestrator").stdout

      assertEquals("horizontal", result.kind)
      assertFalse("Subagent Spawn Runtime Notes" in content)
      assertContains(rendered, "### Subagent Spawn Runtime Notes")

      assertContains(rendered, "`foo-arch`")

      listOf("**On Claude", "**On Codex.**", "**On Cursor.**", "**On Junie.**").forEach { marker ->
        assertContains(rendered, marker)
      }
      assertContains(rendered, "\"use the `foo-arch` subagent\" for that role in `bill-foo-orchestrator`")
      assertSourceBundle(skillDir.resolve("native-agents/agents.yaml"), "foo-arch", "foo-perf")
      assertFalse(Files.exists(skillDir.resolve("codex-agents")))
      assertFalse(Files.exists(skillDir.resolve("opencode-agents")))
      assertFalse(Files.exists(skillDir.resolve("junie-agents")))
      assertNoGeneratedWrapperOrSupportingFiles(skillDir, "bill-foo-orchestrator")
      assertTrue(result.notes.any { note -> "Subagent bundle emitted: 2 entries." in note }, result.notes.toString())
    }

  @Test
  fun `horizontal scaffold creates content only without generated wrapper or source pointers`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val result = scaffold(payload(repo, "horizontal", "name" to "bill-pr-description"))
      val skillDir = repo.resolve("skills").resolve("bill-pr-description")

      assertEquals("bill-pr-description", result.skillName)
      assertNoGeneratedWrapperOrSupportingFiles(skillDir, "bill-pr-description")
    }

  @Test
  fun `horizontal scaffold uses supplied description in content and README catalog`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      Files.writeString(
        repo.resolve("README.md"),
        """
        |# Skill Bill
        |
        || Skill | Purpose |
        ||-------|---------|
        || `/bill-code-check` | Stable quality-check entry point |
        || `/bill-pr-description` | Generate PR text |
        |
        """.trimMargin(),
      )

      scaffold(
        payload(
          repo,
          "horizontal",
          "name" to "bill-code-hot-path",
          "description" to "Use when triaging urgent production incidents.",
        ),
      )

      val content = Files.readString(repo.resolve("skills/bill-code-hot-path/content.md"))
      val readme = Files.readString(repo.resolve("README.md"))

      assertContains(content, "description: Use when triaging urgent production incidents.")
      assertContains(content, "Use when triaging urgent production incidents.")
      assertContains(
        readme,
        "| `/bill-code-hot-path` | Use when triaging urgent production incidents. |",
      )
    }

  @Test
  fun `standalone native agent source stub remains custom body markdown`() {
    val source = renderNativeAgentSourceStub("bill-worker", "bill-orchestrator")

    assertContains(source, "name: bill-worker")
    assertContains(source, "description: TODO: one-line description for the bill-worker specialist subagent")
    assertContains(source, "TODO: replace this placeholder with the specialist briefing.")
    assertFalse("compose: governed-content" in source)
  }

  @Test
  fun `horizontal scaffold rejects generated wrapper headings and rolls back`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val skillDir = repo.resolve("skills/bill-wrapper-shaped-horizontal")
      val before = snapshotTree(repo)

      val error =
        assertFailsWith<MissingRequiredSectionError> {
          scaffold(
            payload(
              repo,
              "horizontal",
              "name" to "bill-wrapper-shaped-horizontal",
              "content_body" to "## Descriptor\n\nGenerated wrapper content must not be authored here.",
            ),
          )
        }

      assertContains(error.message.orEmpty(), "generated wrapper boilerplate heading '## Descriptor'")
      assertEquals(before, snapshotTree(repo))
      assertFalse(Files.exists(skillDir), "Rejected scaffold left a partial skill directory at $skillDir")
    }

  @Test
  fun `horizontal scaffold validates planned content when skill name collides with platform pack target`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val skillDir = repo.resolve("skills/bill-kotlin-code-review")
      val before = snapshotTree(repo)

      val error =
        assertFailsWith<MissingRequiredSectionError> {
          scaffold(
            payload(
              repo,
              "horizontal",
              "name" to "bill-kotlin-code-review",
              "content_body" to "## Descriptor\n\nGenerated wrapper content must not be authored here.",
            ),
          )
        }

      assertContains(error.message.orEmpty(), "generated wrapper boilerplate heading '## Descriptor'")
      assertEquals(before, snapshotTree(repo))
      assertFalse(Files.exists(skillDir), "Rejected scaffold left a partial skill directory at $skillDir")
    }
}

class PlatformPackScaffoldParityTest {
  @Test
  fun `platform pack rejects custom subagent specialists before mutation`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val before = snapshotTree(repo)

      val error =
        assertFailsWith<InvalidScaffoldPayloadError> {
          scaffold(
            payload(repo, "platform-pack", "platform" to "java") +
              mapOf("subagent_specialists" to listOf("arch", "perf")),
          )
        }

      assertContains(error.message.orEmpty(), "exactly one manifest-derived native agent")
      assertEquals(before, snapshotTree(repo))
    }

  @Test
  fun `platform pack generates all approved specialist areas and registers them`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val result = scaffold(payload(repo, "platform-pack", "platform" to "java"))
      val manifest = Files.readString(repo.resolve("platform-packs/java/platform.yaml"))

      assertEquals("platform-pack", result.kind)
      assertContains(
        Files.readString(repo.resolve("platform-packs/java/code-review/bill-java-code-review/content.md")),
        "internal-for: skill-bill",
      )
      APPROVED_CODE_REVIEW_AREAS.sorted().forEach { area ->
        assertTrue(
          Files.isRegularFile(repo.resolve("platform-packs/java/code-review/bill-java-code-review-$area/content.md")),
          "Missing generated specialist content.md for $area",
        )
        assertContains(manifest, "  - \"$area\"")
        assertContains(manifest, "$area: \"code-review/bill-java-code-review-$area/content.md\"")
        val specialist =
          Files.readString(
            repo.resolve("platform-packs/java/code-review/bill-java-code-review-$area/content.md"),
          )
        assertContains(specialist, "internal-for: skill-bill")
        assertContains(specialist, canonicalSeverityCloser(area))
      }
      assertComposedSourceBundle(
        repo.resolve("platform-packs/java/code-review/bill-java-code-review/native-agents/agents.yaml"),
        APPROVED_CODE_REVIEW_AREAS.associate { area ->
          "bill-java-code-review-$area" to
            "Java ${area.replace('-', ' ')} specialist — " +
            "Java ${defaultAreaFocus(area)} across pom.xml, build.gradle, src/main/java signals."
        },
      )
      assertDistinctProviderAgents(repo)
      val pack = loadPlatformPack(repo.resolve("platform-packs/java"))
      assertEquals(APPROVED_CODE_REVIEW_AREAS.sorted(), pack.declaredCodeReviewAreas.sorted())
      APPROVED_CODE_REVIEW_AREAS.forEach { area ->
        assertEquals(
          repo.resolve("platform-packs/java/code-review/bill-java-code-review-$area/content.md"),
          pack.declaredFiles.areas.getValue(area).toPath(),
        )
        assertNoGeneratedWrapperOrSupportingFiles(
          repo.resolve("platform-packs/java/code-review/bill-java-code-review-$area"),
          "bill-java-code-review-$area",
        )
      }
      val subagentNote = result.notes.single { note -> note.startsWith("Subagent bundle emitted:") }
      assertContains(subagentNote, "content.md files")
      assertFalse("native-agents/agents.yaml" in subagentNote)
      assertFalse("TODO" in subagentNote)
    }

  @Test
  fun `new platform pack and new code review area content declare the skill-bill sidecar parent`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      scaffold(payload(repo, "platform-pack", "platform" to "java"))
      val packContents =
        Files.walk(repo.resolve("platform-packs/java")).use { paths ->
          paths.filter { it.name == "content.md" }.toList()
        }
      val areaPlan =
        planCodeReviewArea(
          payload(repo, "code-review-area", "platform" to "kotlin", "area" to "performance"),
          repo,
        )

      assertEquals(APPROVED_CODE_REVIEW_AREAS.size + 1, packContents.size, packContents.toString())
      packContents.forEach { content ->
        assertContains(Files.readString(content), "internal-for: $PACK_SIDECAR_PARENT_SKILL", message = "$content")
      }
      assertContains(renderDeclaredPackContentSheet(areaPlan), "internal-for: $PACK_SIDECAR_PARENT_SKILL")
    }

  @Test
  fun `platform pack rejects no_subagents before mutation`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val before = snapshotTree(repo)

      val error =
        assertFailsWith<InvalidScaffoldPayloadError> {
          scaffold(
            payload(repo, "platform-pack", "platform" to "java") + mapOf("no_subagents" to true),
          )
        }

      assertContains(error.message.orEmpty(), "exactly one manifest-derived native agent")
      assertEquals(before, snapshotTree(repo))
    }
}

class ScaffoldAuthoringParityTest {
  @Test
  fun `code review area scaffold is rejected before mutation`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val before = snapshotTree(repo)
      val error =
        assertFailsWith<RetiredScaffoldKindError> {
          scaffold(
            payload(
              repo,
              "code-review-area",
              "platform" to "kotlin",
              "area" to "performance",
              "name" to "bill-kotlin-code-review-performance",
            ),
          )
        }

      assertContains(error.message.orEmpty(), "retired for new partial scaffold creation")
      assertEquals(before, snapshotTree(repo))
    }

  @Test
  fun `code review area scaffold with content_body is rejected before mutation`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val before = snapshotTree(repo)
      val error =
        assertFailsWith<RetiredScaffoldKindError> {
          scaffold(
            payload(
              repo,
              "code-review-area",
              "platform" to "kotlin",
              "area" to "api-contracts",
              "name" to "bill-kotlin-code-review-api-contracts",
              "content_body" to
                """
            |## Focus
            |
            |Review API boundary regressions.
            |
            |## Review Guidance
            |
            |- Prefer client-visible contract issues.
                """.trimMargin(),
            ),
          )
        }

      assertContains(error.message.orEmpty(), "platform-pack")
      assertEquals(before, snapshotTree(repo))
    }

  @Test
  fun `code review area scaffold with generated wrapper headings is rejected before mutation`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val skillDir = repo.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review-security")
      val before = snapshotTree(repo)

      val error =
        assertFailsWith<RetiredScaffoldKindError> {
          scaffold(
            payload(
              repo,
              "code-review-area",
              "platform" to "kotlin",
              "area" to "security",
              "name" to "bill-kotlin-code-review-security",
              "content_body" to "## Descriptor\n\nGenerated wrapper content must not be authored here.",
            ),
          )
        }

      assertContains(error.message.orEmpty(), "retired for new partial scaffold creation")
      assertEquals(before, snapshotTree(repo))
      assertFalse(Files.exists(skillDir), "Rejected scaffold left a partial skill directory at $skillDir")
    }

  @Test
  fun `subagent payload validation rejects invalid combinations and leaf kinds`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      listOf(
        mapOf("subagent_specialists" to "foo") to "list of strings",
        mapOf("subagent_specialists" to listOf("")) to "non-empty strings",
        mapOf("subagent_specialists" to listOf("foo", "foo")) to "duplicate",
        mapOf("subagent_specialists" to listOf("../foo")) to "invalid name",
        mapOf("no_subagents" to "yes") to "must be a boolean",
        mapOf("subagent_specialists" to listOf("foo"), "no_subagents" to true) to "no_subagents=true",
      ).forEach { (extraPayload, expected) ->
        assertContains(
          assertFailsWith<InvalidScaffoldPayloadError> {
            scaffold(payload(repo, "horizontal", "name" to "bill-mixed-orchestrator") + extraPayload)
          }.message.orEmpty(),
          expected,
        )
      }
      assertFailsWith<RetiredScaffoldKindError> {
        scaffold(
          payload(repo, "code-review-area", "platform" to "kotlin", "area" to "performance") +
            mapOf("name" to "bill-kotlin-code-review-performance", "subagent_specialists" to listOf("x")),
        )
      }
      assertContains(
        assertFailsWith<InvalidScaffoldPayloadError> {
          scaffold(
            payload(repo, "add-on", "platform" to "kotlin", "name" to "review-helper") +
              mapOf("subagent_specialists" to listOf("x")),
          )
        }.message.orEmpty(),
        "subagent_specialists is only valid for orchestrator kinds",
      )
      assertContains(
        assertFailsWith<InvalidScaffoldPayloadError> {
          scaffold(payload(repo, "platform-pack", "platform" to "java") + mapOf("specialist_areas" to listOf("mobile")))
        }.message.orEmpty(),
        "no longer supported",
      )
    }

  @Test
  fun `add-on scaffold registers generated pointer and addon usage in platform manifest`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val result =
        scaffold(
          payload(
            repo,
            "add-on",
            "platform" to "kotlin",
            "name" to "review-helper",
          ),
        )
      val manifestPath = repo.resolve("platform-packs/kotlin/platform.yaml")
      val manifest = Files.readString(manifestPath)
      val addonBody = Files.readString(repo.resolve("platform-packs/kotlin/addons/review-helper.md"))

      assertEquals(
        listOf(repo.resolve("platform-packs/kotlin/addons/review-helper.md")),
        result.createdFiles.map {
          it.toPath()
        },
      )
      assertEquals(listOf(manifestPath), result.manifestEdits.map { it.toPath() })
      assertContains(addonBody, "# review-helper")
      assertContains(addonBody, "TODO: replace this placeholder with the add-on guidance body.")
      assertTrue(
        result.notes.any { note -> "edit the generated add-on body" in note },
        "Expected edit note in ${result.notes}",
      )
      assertContains(manifest, "pointers:")
      assertContains(manifest, "  code-review/bill-kotlin-code-review:")
      assertContains(manifest, "    - name: \"review-helper.md\"")
      assertContains(manifest, "      target: \"platform-packs/kotlin/addons/review-helper.md\"")
      assertContains(manifest, "addon_usage:")
      assertContains(manifest, "    - slug: \"review-helper\"")
      assertContains(manifest, "      entrypoint: \"review-helper.md\"")

      loadPlatformPack(repo.resolve("platform-packs/kotlin"))
      val rendered = renderAuthoringTarget(repo, "bill-kotlin-code-review").stdout
      assertContains(rendered, "## Governed Add-Ons")
      assertContains(rendered, "`review-helper`: entrypoint `review-helper.md`")
    }

  @Test
  fun `add-on scaffold accepts explicit consumer skill dirs`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val specialist = repo.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review-testing")
      Files.createDirectories(specialist)
      Files.writeString(
        specialist.resolve("content.md"),
        renderContentBody(
          TemplateContext("bill-kotlin-code-review-testing", "code-review", "kotlin", "testing", "Kotlin"),
          "Use when reviewing Kotlin test coverage quality.",
        ),
      )
      val manifestPath = repo.resolve("platform-packs/kotlin/platform.yaml")
      appendCodeReviewArea(
        manifestPath,
        "testing",
        "code-review/bill-kotlin-code-review-testing/content.md",
        defaultAreaFocus("testing"),
      )

      scaffold(
        payload(
          repo,
          "add-on",
          "platform" to "kotlin",
          "name" to "testing-helper",
          "consumer_skill_dirs" to listOf("code-review/bill-kotlin-code-review-testing"),
        ),
      )
      val manifest = Files.readString(manifestPath)

      assertContains(manifest, "  code-review/bill-kotlin-code-review-testing:")
      assertContains(manifest, "    - name: \"testing-helper.md\"")
      assertContains(manifest, "    - slug: \"testing-helper\"")
      assertFalse("  code-review/bill-kotlin-code-review:\n    - slug: \"testing-helper\"" in manifest)
    }

  @Test
  fun `add-on scaffold preserves explicit scripted body`() =
    withIsolatedUserHome {
      val repo = seedRepo()

      scaffold(
        payload(
          repo,
          "add-on",
          "platform" to "kotlin",
          "name" to "scripted-helper",
          "body" to "# Scripted Helper\n\nUse this deterministic body.",
        ),
      )

      assertEquals(
        "# Scripted Helper\n\nUse this deterministic body.\n",
        Files.readString(repo.resolve("platform-packs/kotlin/addons/scripted-helper.md")),
      )
    }

  @Test
  fun `add-on scaffold treats explicit blank scripted body as present`() =
    withIsolatedUserHome {
      val repo = seedRepo()

      scaffold(
        payload(
          repo,
          "add-on",
          "platform" to "kotlin",
          "name" to "blank-helper",
          "body" to "",
        ),
      )

      assertEquals(
        "\n",
        Files.readString(repo.resolve("platform-packs/kotlin/addons/blank-helper.md")),
      )
    }

  @Test
  fun `authoring render preserves platform display name and base shell ceremony references`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val packSkill = repo.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review/SKILL.md")
      val packTarget =
        AuthoringTarget(
          skillName = "bill-kotlin-code-review",
          packageName = "kotlin",
          platform = "kotlin",
          displayName = "Kotlin",
          family = "code-review",
          area = "",
          skillFile = packSkill,
          contentFile = packSkill.resolveSibling("content.md"),
        )
      val baseSkill = repo.resolve("skills/skill-bill")
      Files.createDirectories(baseSkill)
      Files.writeString(
        baseSkill.resolve("content.md"),
        "---\nname: skill-bill\ndescription: Base shell content.\n---\n\n" +
          baselineReviewContent("Base shell content."),
      )
      val baseTarget =
        AuthoringTarget(
          skillName = "skill-bill",
          packageName = "base",
          platform = "",
          displayName = "skill bill",
          family = "skill-bill",
          area = "",
          skillFile = baseSkill.resolve("SKILL.md"),
          contentFile = baseSkill.resolve("content.md"),
        )

      assertContains(renderWrapper(packTarget), "Platform pack: `kotlin` (Kotlin)")
      val renderedBase = renderWrapper(baseTarget)

      assertContains(renderedBase, "[shell-ceremony.md](shell-ceremony.md)")
      assertFalse("[review-orchestrator.md](review-orchestrator.md)" in renderedBase)
    }

  @Test
  fun `authoring render and loader share multi word platform display name fallback`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val result =
        scaffold(
          payload(repo, "platform-pack", "platform" to "foo-bar") +
            mapOf("routing_signals" to mapOf("strong" to listOf(".foobar"))),
        )
      val target = resolveTarget(repo, "bill-foo-bar-code-review")
      val rendered = renderWrapper(target)

      assertEquals("platform-pack", result.kind)
      assertContains(rendered, "Platform pack: `foo-bar` (Foo Bar)")
      loadPlatformPack(repo.resolve("platform-packs/foo-bar"))
    }

  @Test
  fun `feature verify override scaffold is rejected before mutation`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val before = snapshotTree(repo)
      val error =
        assertFailsWith<RetiredScaffoldKindError> {
          scaffold(
            payload(
              repo,
              "platform-override-piloted",
              "platform" to "kmp",
              "family" to "feature-verify",
              "name" to "bill-kmp-feature-verify",
            ),
          )
        }

      assertContains(error.message.orEmpty(), "retired for new partial scaffold creation")
      assertEquals(before, snapshotTree(repo))
    }

  @Test
  fun `no subagents opt out skips stub emission`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      scaffold(payload(repo, "horizontal", "name" to "bill-bar-orchestrator") + mapOf("no_subagents" to true))
      val skillDir = repo.resolve("skills").resolve("bill-bar-orchestrator")

      assertFalse(Files.exists(skillDir.resolve("codex-agents")))
      assertFalse(Files.exists(skillDir.resolve("opencode-agents")))
      assertFalse(Files.exists(skillDir.resolve("junie-agents")))
      assertFalse(Files.exists(skillDir.resolve("native-agents")))
      assertFalse("## Subagent Spawn Runtime Notes" in Files.readString(skillDir.resolve("content.md")))
    }

  @Test
  fun `quality check override scaffold is rejected before mutation`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val before = snapshotTree(repo)
      val error =
        assertFailsWith<RetiredScaffoldKindError> {
          scaffold(
            payload(
              repo,
              "platform-override-piloted",
              "platform" to "kotlin",
              "family" to "quality-check",
              "name" to "bill-kotlin-code-check",
            ),
          )
        }

      assertContains(error.message.orEmpty(), "platform-pack")
      assertEquals(before, snapshotTree(repo))
    }

  @Test
  fun `quality check override with content_body is rejected before mutation`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val before = snapshotTree(repo)
      val error =
        assertFailsWith<RetiredScaffoldKindError> {
          scaffold(
            payload(
              repo,
              "platform-override-piloted",
              "platform" to "kotlin",
              "family" to "quality-check",
              "name" to "bill-kotlin-code-check",
              "content_body" to
                """
            |## Focus
            |
            |Run Kotlin checks with the governed quality-check ceremony.
            |
            |## Failure Handling
            |
            |- Report root causes before repair steps.
                """.trimMargin(),
            ),
          )
        }

      assertContains(error.message.orEmpty(), "retired for new partial scaffold creation")
      assertEquals(before, snapshotTree(repo))
    }

  @Test
  fun `quality check override rejects generated wrapper headings and rolls back manifest byte identically`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val manifestPath = repo.resolve("platform-packs/kotlin/platform.yaml")
      val skillDir = repo.resolve("platform-packs/kotlin/quality-check/bill-kotlin-code-check")
      val before = snapshotTree(repo)
      val beforeManifest = Files.readAllBytes(manifestPath)

      val error =
        assertFailsWith<RetiredScaffoldKindError> {
          scaffold(
            payload(
              repo,
              "platform-override-piloted",
              "platform" to "kotlin",
              "family" to "quality-check",
              "name" to "bill-kotlin-code-check",
              "content_body" to "## Descriptor\n\nGenerated wrapper content must not be authored here.",
            ),
          )
        }

      assertContains(error.message.orEmpty(), "retired for new partial scaffold creation")
      assertEquals(before, snapshotTree(repo))
      assertTrue(
        beforeManifest.contentEquals(Files.readAllBytes(manifestPath)),
        "Rejected scaffold must restore platform.yaml byte-for-byte",
      )
      assertFalse(Files.exists(skillDir), "Rejected scaffold left a partial skill directory at $skillDir")
    }

  @Test
  fun `scaffold rollback restores manifest files symlinks and directories byte identically`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val unrelated = repo.resolve("notes/unrelated.txt")
      Files.createDirectories(unrelated.parent)
      Files.writeString(unrelated, "keep me")
      Files.delete(repo.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review/content.md"))
      val before = snapshotTree(repo)

      assertFailsWith<RetiredScaffoldKindError> {
        scaffold(
          payload(
            repo,
            "code-review-area",
            "platform" to "kotlin",
            "area" to "performance",
            "name" to "bill-kotlin-code-review-performance",
          ),
        )
      }

      assertEquals(before, snapshotTree(repo))
      assertEquals("keep me", Files.readString(unrelated))
      assertFalse(Files.exists(repo.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review-performance")))
    }

  @Test
  fun `agent addon dry run plans two files and execute creates only governed sources`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      seedBaseSkill(repo, "skill-bill")
      val request =
        payload(
          repo,
          "agent-addon",
          "slug" to "review-helper",
          "description" to "Review helper",
          "agent_ids" to listOf("codex"),
          "consumers" to listOf("skill-bill"),
          "content_body" to "Use the review helper.\n",
        )

      val preview = scaffold(request, dryRun = true)
      assertEquals(2, preview.createdFiles.size)
      assertFalse(Files.exists(repo.resolve("agent-addons/review-helper")))

      val result = scaffold(request)
      val addonRoot = repo.resolve("agent-addons/review-helper")
      assertEquals(2, result.createdFiles.size)
      assertTrue(Files.isRegularFile(addonRoot.resolve("agent-addon.yaml")))
      assertEquals("Use the review helper.\n", Files.readString(addonRoot.resolve("content.md")))
      assertEquals(
        setOf("agent-addon.yaml", "content.md"),
        Files.list(addonRoot).use { files -> files.map { it.fileName.toString() }.toList().toSet() },
      )
    }

  @Test
  fun `agent addon rejects path traversal slug before filesystem mutation`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      seedBaseSkill(repo, "skill-bill")
      val before = snapshotTree(repo)

      val error =
        assertFailsWith<SkillBillRuntimeException> {
          scaffold(
            payload(
              repo,
              "agent-addon",
              "slug" to "../escaped",
              "description" to "Review helper",
              "agent_ids" to listOf("codex"),
              "consumers" to listOf("skill-bill"),
            ),
          )
        }

      assertEquals(AgentAddonFailureCode.INVALID_SCHEMA, error.code)
      assertEquals(before, snapshotTree(repo))
      assertFalse(Files.exists(repo.parent.resolve("escaped")))
    }
}

class PlatformPackNativeAgentScaffoldTest {
  @Test
  fun `platform pack rejects an explicit body based canonical agent`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val before = snapshotTree(repo)

      val error =
        assertFailsWith<InvalidScaffoldPayloadError> {
          scaffold(
            payload(repo, "platform-pack", "platform" to "java") +
              mapOf("subagent_specialists" to listOf("bill-java-code-review")),
          )
        }

      assertContains(error.message.orEmpty(), "exactly one manifest-derived native agent")
      assertEquals(before, snapshotTree(repo))
    }
}

private fun payload(
  repo: Path,
  kind: String,
  vararg pairs: Pair<String, Any?>,
): Map<String, Any?> =
  mapOf("scaffold_payload_version" to "1.0", "kind" to kind, "repo_root" to repo.toString()) + pairs

internal fun reviewStructurePayload(
  repo: Path,
  kind: String,
  vararg pairs: Pair<String, Any?>,
): Map<String, Any?> = payload(repo, kind, *pairs)

private fun seedRepo(): Path {
  val repo = Files.createTempDirectory("skillbill-scaffold-repo")
  SkillClassFixtures.seedShippedSkillClasses(repo)
  supportingFileTargets(repo).values.forEach { target ->
    Files.createDirectories(target.parent)
    Files.writeString(target, "# ${target.fileName}\n")
  }
  seedBaseSkill(repo, "bill-code-check")
  seedBaseSkill(repo, PACK_SIDECAR_PARENT_SKILL)
  seedKotlinPack(repo)
  seedKmpPack(repo)
  return repo
}

internal fun seedReviewStructureRepo(): Path = seedRepo()

private fun seedBaseSkill(
  repo: Path,
  skillName: String,
) {
  val skillDir = repo.resolve("skills").resolve(skillName)
  Files.createDirectories(skillDir)
  Files.writeString(
    skillDir.resolve("content.md"),
    """
    |---
    |name: $skillName
    |description: Test base skill.
    |---
    |
    |Test body.
    """.trimMargin(),
  )
}

private fun seedKotlinPack(repo: Path) {
  val packRoot = repo.resolve("platform-packs/kotlin")
  val baseline = packRoot.resolve("code-review/bill-kotlin-code-review")
  Files.createDirectories(baseline)
  val context = TemplateContext("bill-kotlin-code-review", "code-review", "kotlin", "", "Kotlin")
  Files.writeString(
    packRoot.resolve("platform.yaml"),
    renderPlatformPackManifest(
      PlatformPackManifestRenderRequest(
        platform = "kotlin",
        displayName = "Kotlin",
        strongSignals = listOf(".kt"),
        baselineContentPath = "code-review/bill-kotlin-code-review/content.md",
      ),
    ),
  )
  Files.writeString(
    baseline.resolve("content.md"),
    renderContentBody(context, inferSkillDescription(context)),
  )
}

private fun seedKmpPack(repo: Path) {
  val packRoot = repo.resolve("platform-packs/kmp")
  val baseline = packRoot.resolve("code-review/bill-kmp-code-review")
  Files.createDirectories(baseline)
  val context = TemplateContext("bill-kmp-code-review", "code-review", "kmp", "", "KMP")
  Files.writeString(
    packRoot.resolve("platform.yaml"),
    renderPlatformPackManifest(
      PlatformPackManifestRenderRequest(
        platform = "kmp",
        displayName = "KMP",
        strongSignals = listOf(".kt"),
        baselineContentPath = "code-review/bill-kmp-code-review/content.md",
      ),
    ),
  )
  Files.writeString(
    baseline.resolve("content.md"),
    renderContentBody(context, inferSkillDescription(context)),
  )
}

private fun assertSourceBundle(
  path: Path,
  vararg names: String,
) {
  val text = Files.readString(path)
  names.forEach { name ->
    assertContains(text, "name: $name")
    assertContains(text, "TODO: one-line description for the $name specialist subagent")
  }
  assertContains(text, "agents:")
  assertEquals(names.size, Regex("""(?m)^    compose: governed-content$""").findAll(text).count())
  assertFalse("body: |-" in text)
  assertFalse("TODO: replace this placeholder with the specialist briefing." in text)
  assertFalse("mode: subagent" in text)
}

private fun assertDistinctProviderAgents(repo: Path) {
  val generatedAgents =
    NativeAgentOperations.renderInstallArtifacts(
      NativeAgentInstallRenderRequest(
        platformPacksRoot = repo.resolve("platform-packs"),
        skillsRoot = repo.resolve("skills"),
        selectedPlatforms = listOf("java"),
        provider = NativeAgentProvider.Codex,
        home = Path.of(System.getProperty("user.home")),
        compositionContext = testNativeAgentCompositionContext(repo),
      ),
    )
  val generatedArchitecture =
    Files.readString(
      generatedAgents.cacheRoot.resolve("codex-agents/bill-java-code-review-architecture.toml"),
    )
  val generatedSecurity =
    Files.readString(
      generatedAgents.cacheRoot.resolve("codex-agents/bill-java-code-review-security.toml"),
    )
  assertContains(generatedArchitecture, "architecture, boundaries, and dependency direction")
  assertContains(generatedSecurity, "secrets handling, auth, and sensitive-data exposure")
  assertFalse(generatedArchitecture == generatedSecurity, "provider-native agents must render distinct content")
}

private fun assertComposedSourceBundle(
  path: Path,
  descriptions: Map<String, String>,
) {
  val text = Files.readString(path)
  assertContains(text, "agents:")
  descriptions.forEach { (name, description) ->
    assertContains(text, "name: $name")
    assertContains(text, "description: \"$description\"")
  }
  assertEquals(descriptions.size, Regex("""(?m)^    compose: governed-content$""").findAll(text).count())
  assertFalse("TODO:" in text)
  assertFalse("body: |-" in text)
  assertFalse("mode: subagent" in text)
}

private fun assertNoGeneratedWrapperOrSupportingFiles(
  skillDir: Path,
  skillName: String,
) {
  assertNoGeneratedWrapper(skillDir)
  val repoRoot = locateTestRepoRoot(skillDir)
  requiredSupportingFilesForSkill(skillName, repoRoot).forEach { fileName ->
    assertFalse(
      Files.exists(skillDir.resolve(fileName)),
      "scaffold must not create source pointer/supporting file '$fileName' at $skillDir",
    )
  }
}

private fun locateTestRepoRoot(skillDir: Path): Path {
  var current: Path? = skillDir.toAbsolutePath().normalize().parent
  while (current != null) {
    if (Files.isDirectory(current.resolve("skills")) || Files.isDirectory(current.resolve("platform-packs"))) {
      val rootCandidate = current
      val classesDir = rootCandidate.resolve("orchestration/skill-classes")
      if (!Files.isDirectory(classesDir)) {
        SkillClassFixtures.seedShippedSkillClasses(rootCandidate)
      }
      return rootCandidate
    }
    current = current.parent
  }
  return skillDir
}

private fun assertNoGeneratedWrapper(skillDir: Path) {
  assertFalse(Files.exists(skillDir.resolve("SKILL.md")), "scaffold must not create source SKILL.md at $skillDir")
}

private fun withIsolatedUserHome(block: () -> Unit) {
  val originalHome = System.getProperty("user.home")
  val tempHome = Files.createTempDirectory("skillbill-no-agents-home")
  try {
    System.setProperty("user.home", tempHome.toString())
    block()
  } finally {
    System.setProperty("user.home", originalHome)
  }
}

internal fun withReviewStructureUserHome(block: () -> Unit) = withIsolatedUserHome(block)

private fun snapshotTree(root: Path): Map<String, String> {
  if (!Files.exists(root)) {
    return emptyMap()
  }
  return Files.walk(root).use { stream ->
    stream
      .filter { path -> path != root }
      .sorted()
      .toList()
      .associate { path ->
        val key = root.relativize(path).toString()
        val value =
          when {
            Files.isSymbolicLink(path) -> "symlink:${Files.readSymbolicLink(path)}"
            Files.isDirectory(path) -> "dir"
            else -> "file:${Files.readString(path)}"
          }
        key to value
      }
  }
}
