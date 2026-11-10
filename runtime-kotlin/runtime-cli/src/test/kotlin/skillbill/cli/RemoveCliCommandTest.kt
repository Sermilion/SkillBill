package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonCodec
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentSource
import skillbill.infrastructure.skills.nativeagent.composition.renderNativeAgentSource
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoveCliCommandTest {
  @Test
  fun `remove without target returns examples instead of generic missing argument error`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-remove-missing-target")
    val result =
      CliRuntime.run(
        listOf("remove", "--format", "json"),
        CliRuntimeContext(userHome = tempDir, environment = isolatedCliEnvironment(tempDir)),
      )
    val payload = decodeJsonObject(result.stdout)
    val error = payload["error"].toString()

    assertEquals(1, result.exitCode)
    assertEquals("error", payload["status"].toString().trim('"'))
    assertTrue("skill-bill remove skill:bill-my-skill --dry-run" in error)
    assertTrue("skill-bill remove platform:my-platform --dry-run" in error)
    assertTrue("skill-bill remove addon:platform-packs/kmp/addons/my-addon.md --dry-run" in error)
  }

  @Test
  fun `remove --dry-run returns a preview payload without mutating the repo`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-remove-dry-run")
    val skillDir = tempDir.resolve("skills/bill-foo")
    Files.createDirectories(skillDir)
    val contentFile = skillDir.resolve("content.md")
    val manifestFile = skillDir.resolve("manifest.yaml")
    val contentBytes = "stub".toByteArray()
    val manifestBytes = "name: bill-foo\nkind: horizontal\n".toByteArray()
    Files.write(contentFile, contentBytes)
    Files.write(manifestFile, manifestBytes)

    val context = CliRuntimeContext(userHome = tempDir, environment = isolatedCliEnvironment(tempDir))
    val result =
      CliRuntime.run(
        listOf(
          "remove",
          "skill:bill-foo",
          "--repo-root",
          tempDir.toString(),
          "--allow-shipped",
          "--dry-run",
          "--format",
          "json",
        ),
        context,
      )
    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeJsonObject(result.stdout)
    assertEquals("preview", payload["status"].toString().trim('"'))

    assertTrue(Files.exists(skillDir))
    assertTrue(Files.exists(contentFile))
    assertEquals("stub", Files.readString(contentFile))
    assertTrue(Files.exists(manifestFile))
    assertTrue(Files.readAllBytes(manifestFile).contentEquals(manifestBytes))
  }

  @Test
  fun `remove dry-run uses top-level home override for agent symlink preview`() {
    val repoRoot = Files.createTempDirectory("skillbill-cli-remove-home-repo")
    val contextHome = Files.createTempDirectory("skillbill-cli-remove-context-home")
    val selectedHome = Files.createTempDirectory("skillbill-cli-remove-selected-home")
    val skillDir = repoRoot.resolve("skills/bill-foo")
    Files.createDirectories(skillDir)
    Files.writeString(skillDir.resolve("content.md"), "# bill-foo\n")
    val context = CliRuntimeContext(userHome = contextHome, environment = isolatedCliEnvironment(selectedHome))
    val sources = Files.createDirectories(skillDir.resolve("native-agents"))
    Files.writeString(
      sources.resolve("bill-foo-worker.md"),
      renderNativeAgentSource(NativeAgentSource("bill-foo-worker", "Test worker.", "# Worker")),
    )
    val platformPacks = Files.createDirectories(repoRoot.resolve("platform-packs"))
    val installation =
      CliRuntime.run(
        listOf(
          "--home",
          selectedHome.toString(),
          "install",
          "link-claude-agents",
          "--platform-packs",
          platformPacks.toString(),
          "--skills",
          repoRoot.resolve("skills").toString(),
        ),
        context,
      )
    assertEquals(0, installation.exitCode, installation.stdout + installation.stderr)

    val result =
      CliRuntime.run(
        listOf(
          "--home",
          selectedHome.toString(),
          "remove",
          "skill:bill-foo",
          "--repo-root",
          repoRoot.toString(),
          "--allow-shipped",
          "--dry-run",
          "--format",
          "json",
        ),
        context,
      )

    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeJsonObject(result.stdout)
    val symlinkPaths =
      (payload["agent_symlink_unlinks"] as? List<*>)
        .orEmpty()
        .mapNotNull { entry -> (entry as? Map<*, *>)?.get("path")?.toString() }

    assertEquals(listOf(selectedHome.resolve(".claude/agents/bill-foo-worker.md").toString()), symlinkPaths)
    assertTrue(
      symlinkPaths.all { path -> selectedHome.toString() in path },
      "all symlink preview paths should use the selected --home: $symlinkPaths",
    )
    assertTrue(
      symlinkPaths.none { path -> contextHome.toString() in path },
      "symlink preview paths must not use the process/context home: $symlinkPaths",
    )
  }

  @Test
  fun `remove refuses dot-bill-shared with status error`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-remove-shared")
    val context = CliRuntimeContext(userHome = tempDir, environment = isolatedCliEnvironment(tempDir))
    val result =
      CliRuntime.run(
        listOf(
          "remove",
          "skill:.bill-shared",
          "--repo-root",
          tempDir.toString(),
          "--dry-run",
          "--format",
          "json",
        ),
        context,
      )
    assertEquals(1, result.exitCode)
    val payload = decodeJsonObject(result.stdout)
    assertEquals("error", payload["status"].toString().trim('"'))
  }

  @Test
  fun `remove refuses bill-prefixed horizontal product skill without --allow-shipped`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-remove-bill")
    val context = CliRuntimeContext(userHome = tempDir, environment = isolatedCliEnvironment(tempDir))
    val result =
      CliRuntime.run(
        listOf(
          "remove",
          "skill:bill-code-review",
          "--repo-root",
          tempDir.toString(),
          "--dry-run",
          "--format",
          "json",
        ),
        context,
      )
    assertEquals(1, result.exitCode)
    val payload = decodeJsonObject(result.stdout)
    assertEquals("error", payload["status"].toString().trim('"'))
    val error = payload["error"].toString()
    assertTrue("--allow-shipped" in error)
    assertTrue("allowShipped" !in error)
    assertTrue("Why this is protected:" in error)
    assertTrue("bill-* skills are shipped product surfaces" in error)
    assertTrue("skill-bill remove skill:bill-code-review --dry-run --allow-shipped" in error)
    assertTrue("skill-bill remove skill:bill-code-review --allow-shipped" in error)
  }

  @Test
  fun `remove treats kotlin as a normal horizontal skill name after pre-shell pruning`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-remove-kotlin")
    val context = CliRuntimeContext(userHome = tempDir, environment = isolatedCliEnvironment(tempDir))
    val result =
      CliRuntime.run(
        listOf("remove", "skill:kotlin", "--repo-root", tempDir.toString(), "--dry-run", "--format", "json"),
        context,
      )
    assertEquals(0, result.exitCode, result.stdout)
  }

  @Test
  fun `remove platform kotlin succeeds without --allow-shipped`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-remove-platform-kotlin")
    Files.createDirectories(tempDir.resolve("platform-packs/kotlin"))
    val context = CliRuntimeContext(userHome = tempDir, environment = isolatedCliEnvironment(tempDir))
    val result =
      CliRuntime.run(
        listOf(
          "remove",
          "platform:kotlin",
          "--repo-root",
          tempDir.toString(),
          "--dry-run",
          "--format",
          "json",
        ),
        context,
      )
    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeJsonObject(result.stdout)
    assertEquals("preview", payload["status"].toString().trim('"'))
  }

  @Test
  fun `remove platform deletes the platform pack folder`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-remove-platform-delete")
    val platformPackRoot = tempDir.resolve("platform-packs/example")
    Files.createDirectories(platformPackRoot.resolve("code-review/bill-example-code-review"))
    Files.writeString(platformPackRoot.resolve("code-review/bill-example-code-review/content.md"), "# example\n")
    val context = CliRuntimeContext(userHome = tempDir, environment = isolatedCliEnvironment(tempDir))

    val result =
      CliRuntime.run(
        listOf(
          "remove",
          "platform:example",
          "--repo-root",
          tempDir.toString(),
          "--format",
          "json",
        ),
        context,
      )

    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeJsonObject(result.stdout)
    assertEquals("ok", payload["status"].toString().trim('"'))
    assertTrue(!Files.exists(platformPackRoot, LinkOption.NOFOLLOW_LINKS), "platform pack folder should be deleted")
  }

  @Test
  fun `remove kotlin succeeds when --allow-shipped is passed in dry-run`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-remove-kotlin-allow")
    val context = CliRuntimeContext(userHome = tempDir, environment = isolatedCliEnvironment(tempDir))
    val result =
      CliRuntime.run(
        listOf(
          "remove",
          "skill:kotlin",
          "--repo-root",
          tempDir.toString(),
          "--dry-run",
          "--allow-shipped",
          "--format",
          "json",
        ),
        context,
      )
    assertEquals(0, result.exitCode, result.stdout)
  }

  private fun decodeJsonObject(raw: String): Map<String, Any?> {
    val parsed = JsonCodec.parseObjectOrNull(raw) ?: error("invalid JSON: $raw")
    return requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(parsed))) {
      "not an object: $raw"
    }
  }
}
