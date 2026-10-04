package skillbill.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import skillbill.cli.core.CliRuntime
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.model.CliExecutionResult
import skillbill.cli.model.CliRuntimeContext
import skillbill.cli.scaffold.payload.readCliTextFile
import skillbill.contracts.JsonCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliScaffoldRuntimeTest {
  @Test
  fun `attached payload stdin form is consumed after parsing`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-payload-stdin")
    val result =
      CliRuntime.run(
        listOf("new-skill", "--payload=-", "--dry-run", "--format", "json"),
        CliRuntimeContext(
          userHome = tempDir,
          stdinText = """{"scaffold_payload_version":"1.0","kind":"horizontal","name":"bill-payload-stdin"}""",
        ),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "bill-payload-stdin")
  }

  @Test
  fun `cli scaffold uses explicit repo root and invocation root as the shared defaults`() {
    val invocationRoot = Files.createTempDirectory("skillbill-cli-scaffold-invocation-root")
    val explicitRoot = Files.createTempDirectory("skillbill-cli-scaffold-explicit-root")
    val payload =
      """
      {"scaffold_payload_version":"1.0","kind":"horizontal","name":"bill-repo-root-parity"}
      """.trimIndent()

    val defaultResult =
      CliRuntime.run(
        listOf("new-skill", "--payload", "-", "--dry-run", "--format", "json"),
        CliRuntimeContext(
          repositoryRoot = invocationRoot,
          userHome = invocationRoot,
          stdinText = payload,
        ),
      )
    val explicitResult =
      CliRuntime.run(
        listOf("new-skill", "--payload", "-", "--dry-run", "--format", "json"),
        CliRuntimeContext(
          repositoryRoot = invocationRoot,
          userHome = invocationRoot,
          stdinText =
            payload.replace(
              "\"kind\":\"horizontal\"",
              "\"kind\":\"horizontal\",\"repo_root\":\"$explicitRoot\"",
            ),
        ),
      )

    assertEquals(0, defaultResult.exitCode, defaultResult.stderr)
    assertEquals(0, explicitResult.exitCode, explicitResult.stderr)
    val defaultSkillPath = Path.of(parseJsonObject(defaultResult.stdout).stringValue("skill_path"))
    val explicitSkillPath = Path.of(parseJsonObject(explicitResult.stdout).stringValue("skill_path"))
    assertTrue(
      pathIsUnderRoot(defaultSkillPath, invocationRoot),
      "default skill path $defaultSkillPath was not under $invocationRoot",
    )
    assertTrue(
      pathIsUnderRoot(explicitSkillPath, explicitRoot),
      "explicit skill path $explicitSkillPath was not under $explicitRoot",
    )
  }

  @Test
  fun `dash body input preserves authored line endings`() {
    val body = "first\r\nsecond\r\n"

    assertEquals(body, readCliTextFile("-", CliRunState(body)))
  }

  @Test
  fun `new skill and new alias share the scaffold payload contract`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-new")
    val context = scaffoldPayloadContext(tempDir)

    val newSkillResult = scaffoldResult("new-skill", context)
    val newAliasPayload = scaffoldPayload("new", context)

    assertNewSkillScaffoldGolden(newSkillResult)
    assertEquals("ok", newAliasPayload.stringValue("status"))
    assertTrue(newAliasPayload.stringValue("skill_path").endsWith("/skills/bill-horizontal-kotlin"))
  }

  @Test
  fun `new wizard scaffolds horizontal skill without requiring payload file`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-wizard")
    val result =
      CliRuntime.run(
        listOf("new", "--dry-run", "--format", "json"),
        CliRuntimeContext(
          stdinText =
            """
            horizontal
            bill-wizard-skill
            Wizard-created skill.
            """.trimIndent(),
          userHome = tempDir,
        ),
      )
    val payload = parseJsonObject(result.stdout)

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("ok", payload.stringValue("status"))
    assertTrue(payload.stringValue("skill_path").endsWith("/skills/bill-wizard-skill"))
  }

  @Test
  fun `new interactive flag runs the same deterministic wizard`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-interactive")
    val payload =
      scaffoldPayload(
        listOf("new-skill", "--interactive", "--dry-run", "--format", "json"),
        CliRuntimeContext(
          stdinText = "1\nwizard-alias-skill\n\n",
          userHome = tempDir,
        ),
      )

    assertEquals("ok", payload.stringValue("status"))
    assertTrue(payload.stringValue("skill_path").endsWith("/skills/bill-wizard-alias-skill"))
  }

  @Test
  fun `new add-on wizard creates skeleton plan without body or raw consumer prompts`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-addon-wizard")
    val liveStdout = StringBuilder()
    val result =
      CliRuntime.run(
        listOf("new", "--dry-run", "--format", "json"),
        CliRuntimeContext(
          stdinText = listOf("3", "kotlin", "cli-addon", "").joinToString("\n"),
          userHome = tempDir,
          liveStdout = { liveStdout.append(it) },
        ),
      )
    val payload = parseJsonObject(result.stdout)
    val output = liveStdout.toString()
    val preview = platformManifestPreview(payload)

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("ok", payload.stringValue("status"))
    assertTrue(
      Path.of(payload.stringValue("skill_path"))
        .endsWith(Path.of("platform-packs", "kotlin", "addons")),
    )
    assertContains(payload["manifest_edits"].toString(), "platform-packs/kotlin/platform.yaml")
    assertContains(preview, "  code-review/bill-kotlin-code-review:")
    assertContains(preview, "    - name: \"cli-addon.md\"")
    assertContains(preview, "addon_usage:")
    assertContains(preview, "    - slug: \"cli-addon\"")
    assertFalse("Body:" in output)
    assertFalse("Consumer skill dirs" in output)
    assertTrue(
      payload["notes"]?.jsonArray.orEmpty().any {
        "edit the generated add-on body" in it.jsonPrimitive.content
      },
      "Expected edit note in ${payload["notes"]}",
    )
  }

  @Test
  fun `new agent addon wizard plans both governed source files`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-agent-addon-wizard")
    val liveStdout = StringBuilder()
    val result =
      CliRuntime.run(
        listOf("new", "--dry-run", "--format", "json"),
        CliRuntimeContext(
          stdinText = listOf("4", "review-helper", "Review helper", "codex", "skill-bill").joinToString("\n"),
          userHome = tempDir,
          liveStdout = { liveStdout.append(it) },
        ),
      )
    val payload = parseJsonObject(result.stdout)
    val createdFiles = payload["created_files"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("ok", payload.stringValue("status"))
    assertTrue(createdFiles.any { it.endsWith("agent-addons/review-helper/agent-addon.yaml") })
    assertTrue(createdFiles.any { it.endsWith("agent-addons/review-helper/content.md") })
    assertContains(liveStdout.toString(), "Supported agents:")
  }

  @Test
  fun `new platform pack wizard explains built-in routing presets and accepts blank override`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-java-preset")
    val liveStdout = StringBuilder()
    val result =
      CliRuntime.run(
        listOf("new", "--dry-run", "--format", "json"),
        CliRuntimeContext(
          stdinText = "2\njava\nJava\nStuff for Java\n\n",
          userHome = tempDir,
          liveStdout = { liveStdout.append(it) },
        ),
      )
    val payload = parseJsonObject(result.stdout)
    val preview = platformManifestPreview(payload)

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("ok", payload.stringValue("status"))
    assertContains(liveStdout.toString(), "Built-in routing preset found for 'java'")
    assertContains(liveStdout.toString(), "Press Enter to use it")
    assertFalse("Skeleton mode" in liveStdout.toString())
    assertContains(preview, "- \"pom.xml\"")
    assertContains(preview, "- \"src/main/java\"")
  }

  @Test
  fun `new platform pack wizard explains custom routing signals and accepts comma separated values`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-routing-help")
    val liveStdout = StringBuilder()
    val result =
      CliRuntime.run(
        listOf("new", "--dry-run", "--format", "json"),
        CliRuntimeContext(
          stdinText = listOf("2", "zig", "Zig", "Stuff for Zig", ".zig, build.zig").joinToString("\n"),
          userHome = tempDir,
          liveStdout = { liveStdout.append(it) },
        ),
      )
    val payload = parseJsonObject(result.stdout)
    val preview = platformManifestPreview(payload)

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("ok", payload.stringValue("status"))
    assertContains(liveStdout.toString(), "Routing signals tell Skill Bill when to use this platform pack")
    assertContains(liveStdout.toString(), "changed files, repo markers, or dependency manifests")
    assertContains(liveStdout.toString(), "Use file extensions (.kt, .go)")
    assertContains(liveStdout.toString(), "filenames (go.mod, package.json)")
    assertContains(liveStdout.toString(), "directories (src/main/java)")
    assertContains(liveStdout.toString(), "dependency coordinates, or language markers")
    assertContains(liveStdout.toString(), "Strong routing signals (comma-separated)")
    assertFalse("Skeleton mode" in liveStdout.toString())
    assertContains(preview, "- \".zig\"")
    assertContains(preview, "- \"build.zig\"")
  }

  @Test
  fun `new assisted asks kind and agent then scaffolds platform pack from language`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-assisted")
    Files.createDirectories(tempDir.resolve(".codex"))
    val liveStdout = StringBuilder()
    val result =
      CliRuntime.run(
        listOf("new", "--assisted", "--dry-run", "--format", "json"),
        CliRuntimeContext(
          stdinText = listOf("2", "codex", "java").joinToString("\n"),
          userHome = tempDir,
          liveStdout = { liveStdout.append(it) },
        ),
      )
    val payload = parseJsonObject(result.stdout)
    val preview = platformManifestPreview(payload)

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("ok", payload.stringValue("status"))
    assertContains(liveStdout.toString(), "Skill Bill assisted scaffold wizard")
    assertContains(liveStdout.toString(), "Kind: 1 horizontal, 2 platform-pack")
    assertFalse("platform-override" in liveStdout.toString())
    assertFalse("code-review-area" in liveStdout.toString())
    assertContains(liveStdout.toString(), "Available agents:")
    assertContains(liveStdout.toString(), "codex")
    assertContains(liveStdout.toString(), "Language or platform")
    assertTrue(payload.stringValue("skill_path").endsWith("/platform-packs/java"))
    assertContains(preview, "display_name: \"Java\"")
    assertContains(preview, "- \"pom.xml\"")
    assertContains(preview, "- \"src/main/java\"")
    assertContains(preview, "ui: \"code-review/bill-java-code-review-ui/content.md\"")
  }

  @Test
  fun `new assisted falls back to local assistance when no agent is detected`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-assisted-prompt")
    val liveStdout = StringBuilder()
    val result =
      CliRuntime.run(
        listOf("new-skill", "--assisted", "--dry-run", "--format", "json"),
        CliRuntimeContext(
          stdinText = listOf("2", "java").joinToString("\n"),
          userHome = tempDir,
          environment = isolatedCliEnvironment(tempDir),
          liveStdout = { liveStdout.append(it) },
        ),
      )
    val payload = parseJsonObject(result.stdout)

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("ok", payload.stringValue("status"))
    assertContains(liveStdout.toString(), "No installed agents detected")
    assertContains(liveStdout.toString(), "Language or platform")
    assertTrue(payload.stringValue("skill_path").endsWith("/platform-packs/java"))
  }

  @Test
  fun `new assisted rejects unsupported scaffold kind`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-assisted-error")
    val result =
      CliRuntime.run(
        listOf("new", "--assisted", "--dry-run", "--format", "json"),
        CliRuntimeContext(
          stdinText = "1\n",
          userHome = tempDir,
        ),
      )
    val payload = parseJsonObject(result.stdout)

    assertEquals(1, result.exitCode)
    assertEquals("error", payload.stringValue("status"))
    assertContains(payload.stringValue("error"), "currently supports platform-pack")
  }

  @Test
  fun `new assisted rejects retired partial scaffold kind alias`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-assisted-retired")
    val result =
      CliRuntime.run(
        listOf("new", "--assisted", "--dry-run", "--format", "json"),
        CliRuntimeContext(
          stdinText = "platform-override\n",
          userHome = tempDir,
        ),
      )
    val payload = parseJsonObject(result.stdout)

    assertEquals(1, result.exitCode)
    assertEquals("error", payload.stringValue("status"))
    assertContains(payload.stringValue("error"), "retired for new partial scaffold creation")
    assertContains(payload.stringValue("error"), "platform-pack")
  }

  @Test
  fun `create-and-fill dry run preserves scaffold payload contract`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-fill")
    val bodyFile = tempDir.resolve("content-body.md")
    Files.writeString(bodyFile, "Authored native file content.")
    val context =
      CliRuntimeContext(
        stdinText =
          """
          {
            "scaffold_payload_version": "1.0",
            "kind": "horizontal",
            "name": "bill-horizontal-fill"
          }
          """.trimIndent(),
        userHome = tempDir,
      )

    assertCreateAndFillNativePayloads(context, bodyFile)
  }

  @Test
  fun `create-and-fill rejects multi artifact scaffold kinds with json diagnostics`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-fill-reject")
    listOf(
      """
      {
        "scaffold_payload_version": "1.0",
        "kind": "platform-pack",
        "platform": "java"
      }
      """.trimIndent(),
      """
      {
        "scaffold_payload_version": "1.0",
        "kind": "add-on",
        "platform": "kotlin",
        "name": "review-helper"
      }
      """.trimIndent(),
    ).forEach { stdin ->
      val result =
        CliRuntime.run(
          listOf("create-and-fill", "--payload", "-", "--body", "Authored content.", "--dry-run", "--format", "json"),
          CliRuntimeContext(stdinText = stdin, userHome = tempDir),
        )
      val payload = parseJsonObject(result.stdout)

      assertEquals(1, result.exitCode)
      assertEquals("error", payload.stringValue("status"))
      assertContains(payload.stringValue("error"), "one content-managed skill")
    }
  }

  @Test
  fun `payload file that is not a json object reports the object diagnostic`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-payload-array")
    val payloadFile = tempDir.resolve("payload.json")
    Files.writeString(payloadFile, "[]")

    val result =
      CliRuntime.run(
        listOf("new-skill", "--payload", payloadFile.toString(), "--dry-run", "--format", "json"),
        CliRuntimeContext(userHome = tempDir),
      )
    val payload = parseJsonObject(result.stdout)

    assertEquals(1, result.exitCode)
    assertEquals("error", payload.stringValue("status"))
    assertEquals("Invalid JSON payload: expected an object.", payload.stringValue("error"))
  }

  @Test
  fun `new addon dry run preserves scaffold payload contract`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-addon")
    val bodyFile = tempDir.resolve("addon-body.md")
    Files.writeString(bodyFile, "Pack-owned file guidance.")
    val payload =
      scaffoldPayload(
        listOf(
          "new-addon",
          "--platform",
          "kmp",
          "--name",
          "android-new-addon",
          "--consumer-skill-dir",
          "code-review/bill-kmp-code-review-ui",
          "--body",
          "Pack-owned guidance.",
          "--dry-run",
          "--format",
          "json",
        ),
        CliRuntimeContext(userHome = tempDir),
      )
    val bodyFilePayload =
      scaffoldPayload(
        listOf(
          "new-addon",
          "--platform",
          "kmp",
          "--name",
          "android-new-addon-file",
          "--body-file",
          bodyFile.toString(),
          "--dry-run",
          "--format",
          "json",
        ),
        CliRuntimeContext(userHome = tempDir),
      )

    assertAddonScaffoldPayload(payload)
    assertAddonScaffoldPayload(bodyFilePayload)
    assertNativeBodyConflictErrors(
      listOf("new-addon", "--platform", "kmp", "--name", "android-conflict-addon"),
      CliRuntimeContext(userHome = tempDir),
    )
  }

  @Test
  fun `new platform pack dry run shows composition manifest preview`() {
    val repoRoot = compositionFixtureRepo()
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-composition")
    val platform = "cli-composition-${System.nanoTime()}"
    val result =
      CliRuntime.run(
        listOf("new", "--payload", "-", "--dry-run", "--format", "json"),
        CliRuntimeContext(
          stdinText =
            """
            {
              "scaffold_payload_version": "1.0",
              "kind": "platform-pack",
              "platform": "$platform",
              "repo_root": "$repoRoot",
              "routing_signals": {
                "strong": ["$platform.marker"]
              },
              "baseline_layers": [
                {
                  "platform": "kotlin",
                  "skill": "bill-kotlin-code-review",
                  "scope": "same-review-scope",
                  "required": true,
                  "mode": "kmp-baseline"
                }
              ]
            }
            """.trimIndent(),
          userHome = tempDir,
        ),
      )
    val payload = parseJsonObject(result.stdout)
    val manifestPath = repoRoot.resolve("platform-packs/$platform/platform.yaml").toString()
    val preview =
      payload["manifest_edit_previews"]
        ?.jsonObject
        ?.get(manifestPath)
        ?.jsonPrimitive
        ?.contentOrNull
        .orEmpty()

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("ok", payload.stringValue("status"))
    assertEquals("true", payload["dry_run"]?.jsonPrimitive?.contentOrNull)
    assertEquals(manifestPath, payload["manifest_edits"]?.jsonArray?.single()?.jsonPrimitive?.contentOrNull)
    assertContains(preview, "code_review_composition:")
    assertContains(preview, "baseline_layers:")
    assertContains(preview, "platform: \"kotlin\"")
    assertTrue(Files.notExists(repoRoot.resolve("platform-packs/$platform")))
  }

  @Test
  fun `show surfaces manifest declared review composition`() {
    val repoRoot = compositionFixtureRepo(kmpLayerRequired = false)
    val tempDir = Files.createTempDirectory("skillbill-cli-show-composition")
    val result =
      CliRuntime.run(
        listOf(
          "show",
          "bill-kmp-code-review",
          "--repo-root",
          repoRoot.toString(),
          "--content",
          "none",
          "--format",
          "json",
        ),
        CliRuntimeContext(userHome = tempDir),
      )
    val payload = parseJsonObject(result.stdout)
    val composition = payload["review_composition"]?.jsonObject
    val layer = composition?.get("baseline_layers")?.jsonArray?.single()?.jsonObject

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("platform.yaml", composition?.get("source")?.jsonPrimitive?.contentOrNull)
    assertEquals("kotlin", layer?.get("platform")?.jsonPrimitive?.contentOrNull)
    assertEquals("bill-kotlin-code-review", layer?.get("skill")?.jsonPrimitive?.contentOrNull)
    assertEquals("same-review-scope", layer?.get("scope")?.jsonPrimitive?.contentOrNull)
    assertEquals("false", layer?.get("required")?.jsonPrimitive?.contentOrNull)
    assertEquals("kmp-baseline", layer?.get("mode")?.jsonPrimitive?.contentOrNull)
    assertEquals(
      "Run 1 optional baseline layer(s) before pack-local specialists.",
      composition?.get("summary")?.jsonPrimitive?.contentOrNull,
    )
  }

  @Test
  fun `native scaffold payload errors are reported as structured cli errors`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-scaffold-errors")
    val result =
      CliRuntime.run(
        listOf("new-skill", "--payload", "-", "--dry-run", "--format", "json"),
        CliRuntimeContext(
          stdinText = """{"scaffold_payload_version":"9.99","kind":"horizontal"}""",
          userHome = tempDir,
        ),
      )
    val payload = parseJsonObject(result.stdout)

    assertEquals(1, result.exitCode)
    assertEquals("error", payload.stringValue("status"))
    assertContains(payload.stringValue("error"), "scaffold_payload_version")
  }
}

private fun assertNewSkillScaffoldGolden(result: CliExecutionResult) {
  val payload = parseJsonObject(result.stdout)
  val sessionId = payload.stringValue("session_id")
  val skillPath = payload.stringValue("skill_path")

  assertMatchesPattern(Regex("""^nss-\d{8}-[0-9a-f]{4}$"""), sessionId, "session_id")
  assertTrue(Path.of(skillPath).isAbsolute, "Expected absolute skill_path but got $skillPath")
  assertTrue(skillPath.endsWith("/skills/bill-horizontal-kotlin"))

  assertEquals(
    setOf(
      "created_files",
      "dry_run",
      "manifest_edit_previews",
      "manifest_edits",
      "notes",
      "session_id",
      "skill_path",
      "status",
    ),
    payload.keys,
  )
  assertEquals("ok", payload.stringValue("status"))
  assertEquals("true", payload["dry_run"]?.jsonPrimitive?.contentOrNull)
  assertEquals(
    listOf("$skillPath/content.md"),
    payload["created_files"]?.jsonArray?.map { it.jsonPrimitive.content },
  )
  assertEquals(
    listOf("Dry run - no filesystem changes applied."),
    payload["notes"]?.jsonArray?.map { it.jsonPrimitive.content },
  )

  val manifestEdits = payload["manifest_edits"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
  assertEquals(1, manifestEdits.size, "Expected a single README.md manifest edit but got $manifestEdits")
  val readmePath = manifestEdits.single()
  assertTrue(readmePath.endsWith("/README.md"), "Expected a README.md manifest edit but got $readmePath")

  val previews = payload["manifest_edit_previews"]?.jsonObject ?: emptyMap()
  assertEquals(setOf(readmePath), previews.keys, "manifest_edit_previews must key exactly the planned README edit")
  val readmePreview = previews[readmePath]?.jsonPrimitive?.contentOrNull.orEmpty()
  assertContains(readmePreview, "/bill-horizontal-kotlin")
}

private fun assertCreateAndFillNativePayloads(
  context: CliRuntimeContext,
  bodyFile: Path,
) {
  val payload =
    scaffoldPayload(
      listOf(
        "create-and-fill",
        "--payload",
        "-",
        "--body",
        "Authored native content.",
        "--dry-run",
        "--format",
        "json",
      ),
      context,
    )
  val bodyFilePayload =
    scaffoldPayload(
      listOf(
        "create-and-fill",
        "--payload",
        "-",
        "--body-file",
        bodyFile.toString(),
        "--dry-run",
        "--format",
        "json",
      ),
      context,
    )
  val editorResult =
    CliRuntime.run(
      listOf("create-and-fill", "--payload", "-", "--editor", "--dry-run", "--format", "json"),
      context,
    )
  val editorPayload = parseJsonObject(editorResult.stdout)

  assertEquals("ok", payload.stringValue("status"))
  assertTrue(payload.stringValue("skill_path").endsWith("/skills/bill-horizontal-fill"))
  assertEquals("ok", bodyFilePayload.stringValue("status"))
  assertTrue(bodyFilePayload.stringValue("skill_path").endsWith("/skills/bill-horizontal-fill"))
  assertEquals(1, editorResult.exitCode)
  assertEquals("unsupported", editorPayload.stringValue("status"))
  assertContains(editorPayload.stringValue("error"), "--payload --editor is not supported")
  assertNativeBodyConflictErrors(
    listOf("create-and-fill", "--payload", "-"),
    context,
  )
}

private fun assertAddonScaffoldPayload(payload: JsonObject) {
  assertEquals("ok", payload.stringValue("status"))
  assertTrue(
    Path.of(payload.stringValue("skill_path"))
      .endsWith(Path.of("platform-packs", "kmp", "addons")),
  )
  assertContains(payload["manifest_edits"].toString(), "platform-packs/kmp/platform.yaml")
}

private fun assertNativeBodyConflictErrors(
  prefix: List<String>,
  context: CliRuntimeContext,
) {
  val result =
    CliRuntime.run(
      prefix +
        listOf(
          "--body",
          "inline body",
          "--body-file",
          "-",
          "--dry-run",
          "--format",
          "json",
        ),
      context,
    )
  val payload = parseJsonObject(result.stdout)

  assertEquals(1, result.exitCode)
  assertEquals("error", payload.stringValue("status"))
  assertEquals("--body and --body-file are mutually exclusive.", payload.stringValue("error"))
}

private fun scaffoldPayloadContext(tempDir: Path): CliRuntimeContext =
  CliRuntimeContext(
    stdinText =
      """
      {
        "scaffold_payload_version": "1.0",
        "kind": "horizontal",
        "name": "bill-horizontal-kotlin"
      }
      """.trimIndent(),
    userHome = tempDir,
  )

private fun scaffoldPayload(
  command: String,
  context: CliRuntimeContext,
): JsonObject = scaffoldPayload(listOf(command, "--payload", "-", "--dry-run", "--format", "json"), context)

private fun scaffoldPayload(
  arguments: List<String>,
  context: CliRuntimeContext,
): JsonObject {
  val result = scaffoldResult(arguments, context)
  return parseJsonObject(result.stdout)
}

private fun scaffoldResult(
  command: String,
  context: CliRuntimeContext,
): CliExecutionResult = scaffoldResult(listOf(command, "--payload", "-", "--dry-run", "--format", "json"), context)

private fun scaffoldResult(
  arguments: List<String>,
  context: CliRuntimeContext,
): CliExecutionResult {
  val result = CliRuntime.run(arguments, context)
  assertEquals(0, result.exitCode, result.stdout)
  return result
}

private fun pathIsUnderRoot(
  path: Path,
  root: Path,
): Boolean {
  val normalizedPath = path.toAbsolutePath().normalize()
  var existingAncestor = normalizedPath
  while (!Files.exists(existingAncestor)) {
    existingAncestor = existingAncestor.parent ?: return false
  }
  val canonicalAncestor = existingAncestor.toRealPath()
  val canonicalPath =
    canonicalAncestor.resolve(existingAncestor.relativize(normalizedPath)).normalize()
  return canonicalPath.startsWith(root.toRealPath())
}

private fun parseJsonObject(rawJson: String): JsonObject {
  val parsed = JsonCodec.parseObjectOrNull(rawJson)
  require(parsed != null) { "Expected JSON object but got: $rawJson" }
  return parsed
}

private fun JsonObject.stringValue(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun platformManifestPreview(payload: JsonObject): String {
  val manifestPath = payload["manifest_edits"]?.jsonArray?.single()?.jsonPrimitive?.contentOrNull.orEmpty()
  return payload["manifest_edit_previews"]
    ?.jsonObject
    ?.get(manifestPath)
    ?.jsonPrimitive
    ?.contentOrNull
    .orEmpty()
}

private fun compositionFixtureRepo(kmpLayerRequired: Boolean = true): Path {
  val repoRoot = Files.createTempDirectory("skillbill-cli-composition-repo")
  seedCompositionPack(repoRoot, "kotlin")
  seedCompositionPack(
    repoRoot = repoRoot,
    slug = "kmp",
    composition =
      """
    |code_review_composition:
    |  baseline_layers:
    |    - platform: "kotlin"
    |      skill: "bill-kotlin-code-review"
    |      scope: "same-review-scope"
    |      required: $kmpLayerRequired
    |      mode: "kmp-baseline"
      """.trimMargin(),
  )
  return repoRoot
}

private fun seedCompositionPack(
  repoRoot: Path,
  slug: String,
  composition: String = "",
) {
  val skillName = "bill-$slug-code-review"
  val packRoot = repoRoot.resolve("platform-packs").resolve(slug)
  val skillRoot = packRoot.resolve("code-review").resolve(skillName)
  Files.createDirectories(skillRoot)
  Files.writeString(
    packRoot.resolve("platform.yaml"),
    """
    |platform: "$slug"
    |contract_version: "1.8"
    |display_name: "$slug"
    |
    |routing_signals:
    |  strong:
    |    - "$slug.marker"
    |  tie_breakers: []
    |
    |declared_code_review_areas: []
    |
    |declared_files:
    |  baseline: "code-review/$skillName/content.md"
    |  areas: {}
    |area_metadata: {}
    |$composition
    """.trimMargin(),
  )
  Files.writeString(
    skillRoot.resolve("content.md"),
    """
    |---
    |name: $skillName
    |description: Fixture $slug code review.
    |---
    |
    |## Review Focus
    |
    |Review $slug changes with fixture guidance.
    |
    |## Review Guidance
    |
    |- Keep fixture behavior explicit.
    """.trimMargin(),
  )
}
