package skillbill.mcp.scaffold

import skillbill.application.scaffold.decodeScaffoldCommandRequest
import skillbill.contracts.JsonCodec
import skillbill.error.shellcontent.InvalidScaffoldPayloadError
import skillbill.error.shellcontent.RetiredScaffoldKindError
import skillbill.error.shellcontent.ScaffoldPayloadVersionMismatchError
import skillbill.error.shellcontent.UnknownSkillKindError
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.command.ScaffoldCommandRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun parseMcpScaffoldCommandRequest(payload: Map<String, Any?>): ScaffoldCommandRequest =
  decodeScaffoldCommandRequest(JsonCodec.mapToJsonString(payload))

class McpScaffoldCommandRequestParserTest {
  @Test
  fun `parses horizontal skill request with all fields`() {
    val request =
      parseMcpScaffoldCommandRequest(
        mapOf(
          "scaffold_payload_version" to "1.0",
          "kind" to "horizontal",
          "name" to "bill-foo",
          "description" to "do the foo",
          "content_body" to "## body",
          "subagent_specialists" to listOf("ui", "security"),
          "repo_root" to "/repo",
        ),
      )

    val horizontal = request as ScaffoldCommandRequest.HorizontalSkill
    assertEquals("bill-foo", horizontal.name)
    assertEquals("do the foo", horizontal.description)
    assertEquals("## body", horizontal.contentBody)
    assertEquals(listOf("ui", "security"), horizontal.subagentSpecialists)
    assertEquals(false, horizontal.suppressSubagents)
    assertEquals("1.0", horizontal.scaffoldPayloadVersion)
    assertEquals("/repo", horizontal.repoRoot)
  }

  @Test
  fun `parses platform pack request with routing override`() {
    val request =
      parseMcpScaffoldCommandRequest(
        mapOf(
          "scaffold_payload_version" to "1.0",
          "kind" to "platform-pack",
          "platform" to "kotlin",
          "display_name" to "Kotlin",
          "routing_signals" to
            mapOf(
              "strong" to listOf(".kt"),
              "tie_breakers" to listOf("Prefer Kotlin"),
            ),
          "baseline_layers" to
            listOf(
              mapOf(
                "platform" to "kmp",
                "skill" to "bill-kmp-code-review",
                "scope" to "same-review-scope",
                "required" to true,
                "mode" to "kmp-baseline",
              ),
            ),
        ),
      )

    val pack = request as ScaffoldCommandRequest.PlatformPack
    assertEquals("kotlin", pack.platform)
    assertEquals("Kotlin", pack.displayName)
    assertEquals(listOf(".kt"), pack.routingSignals?.strong)
    assertEquals(listOf("Prefer Kotlin"), pack.routingSignals?.tieBreakers)
    assertEquals(1, pack.baselineLayers.size)
    val layer = pack.baselineLayers.single()
    assertEquals("kmp", layer.platform)
    assertEquals(CodeReviewCompositionScope.SameReviewScope, layer.scope)
    assertEquals(CodeReviewCompositionMode.KmpBaseline, layer.mode)
  }

  @Test
  fun `platform pack payload maps pack location and registration and ignores addon location`() {
    val request =
      parseMcpScaffoldCommandRequest(
        mapOf(
          "scaffold_payload_version" to "1.0",
          "kind" to "platform-pack",
          "platform" to "acme",
          "pack_location_path" to "~/dev/company-packs/acme",
          "pack_registration" to "register",
          "addon_location_path" to "/tmp/not-a-pack",
        ),
      ) as ScaffoldCommandRequest.PlatformPack

    assertEquals("~/dev/company-packs/acme", request.packLocationPath)
    assertEquals("register", request.packRegistration)
  }

  @Test
  fun `platform pack request rejects retired skeleton_mode with migration message`() {
    val error =
      assertFailsWith<InvalidScaffoldPayloadError> {
        parseMcpScaffoldCommandRequest(
          mapOf(
            "scaffold_payload_version" to "1.0",
            "kind" to "platform-pack",
            "platform" to "kotlin",
            "skeleton_mode" to "starter",
          ),
        )
      }
    val message = error.message.orEmpty()
    assertTrue("skeleton_mode" in message, "Got: $message")
    assertTrue("no longer supported" in message, "Got: $message")
    assertTrue("remove unwanted focus areas" in message, "Got: $message")
  }

  @Test
  fun `platform pack request rejects retired specialist_areas with migration message`() {
    val error =
      assertFailsWith<InvalidScaffoldPayloadError> {
        parseMcpScaffoldCommandRequest(
          mapOf(
            "scaffold_payload_version" to "1.0",
            "kind" to "platform-pack",
            "platform" to "kotlin",
            "specialist_areas" to listOf("ui"),
          ),
        )
      }
    val message = error.message.orEmpty()
    assertTrue("specialist_areas" in message, "Got: $message")
    assertTrue("no longer supported" in message, "Got: $message")
    assertTrue("remove unwanted focus areas" in message, "Got: $message")
  }

  @Test
  fun `parses platform pack request with baseline layers`() {
    val request =
      parseMcpScaffoldCommandRequest(
        mapOf(
          "scaffold_payload_version" to "1.0",
          "kind" to "platform-pack",
          "platform" to "kotlin",
          "baseline_layers" to
            listOf(
              mapOf(
                "platform" to "kmp",
                "skill" to "bill-kmp-code-review",
                "scope" to "same-review-scope",
                "required" to false,
                "mode" to "kmp-baseline",
              ),
            ),
        ),
      )

    val pack = request as ScaffoldCommandRequest.PlatformPack
    val layer = pack.baselineLayers.single()
    assertEquals("kmp", layer.platform)
    assertEquals(CodeReviewCompositionScope.SameReviewScope, layer.scope)
    assertEquals(CodeReviewCompositionMode.KmpBaseline, layer.mode)
    assertEquals(false, layer.required)
  }

  @Test
  fun `parses add-on request`() {
    val request =
      parseMcpScaffoldCommandRequest(
        mapOf(
          "scaffold_payload_version" to "1.0",
          "kind" to "add-on",
          "name" to "bill-grill",
          "platform" to "kotlin",
          "body" to "## body",
          "addon_location_path" to "/tmp/private-addons",
          "consumer_skill_dirs" to listOf("code-review/bill-kotlin-code-review"),
        ),
      )

    val addon = request as ScaffoldCommandRequest.AddOn
    assertEquals("bill-grill", addon.name)
    assertEquals("kotlin", addon.platform)
    assertEquals("## body", addon.body)
    assertEquals("/tmp/private-addons", addon.addonLocationPath)
    assertEquals(listOf("code-review/bill-kotlin-code-review"), addon.consumerSkillDirs)
  }

  @Test
  fun `version mismatch throws ScaffoldPayloadVersionMismatchError with version detail`() {
    val error =
      assertFailsWith<ScaffoldPayloadVersionMismatchError> {
        parseMcpScaffoldCommandRequest(
          mapOf("scaffold_payload_version" to "9.9", "kind" to "horizontal", "name" to "bill-foo"),
        )
      }
    assertTrue(error.message.orEmpty().contains("9.9"))
  }

  @Test
  fun `unknown kind throws UnknownSkillKindError with kind detail`() {
    val error =
      assertFailsWith<UnknownSkillKindError> {
        parseMcpScaffoldCommandRequest(
          mapOf("scaffold_payload_version" to "1.0", "kind" to "not-a-kind"),
        )
      }
    assertTrue(error.message.orEmpty().contains("not-a-kind"))
  }

  @Test
  fun `retired partial kind aliases throw RetiredScaffoldKindError`() {
    listOf("platform-override-piloted", "platform-override", "override", "code-review-area", "area", "specialist")
      .forEach { kind ->
        val error =
          assertFailsWith<RetiredScaffoldKindError> {
            parseMcpScaffoldCommandRequest(
              mapOf("scaffold_payload_version" to "1.0", "kind" to kind),
            )
          }
        val message = error.message.orEmpty()
        assertTrue(kind in message, "Got: $message")
        assertTrue("platform-pack" in message, "Got: $message")
        assertTrue("edit/remove existing platform-pack content" in message, "Got: $message")
      }
  }

  @Test
  fun `missing required field throws InvalidScaffoldPayloadError with field path`() {
    val error =
      assertFailsWith<InvalidScaffoldPayloadError> {
        parseMcpScaffoldCommandRequest(
          mapOf("scaffold_payload_version" to "1.0", "kind" to "horizontal"),
        )
      }
    assertTrue(error.message.orEmpty().contains("'name'"))
  }

  @Test
  fun `missing required add-on platform field throws InvalidScaffoldPayloadError`() {
    val error =
      assertFailsWith<InvalidScaffoldPayloadError> {
        parseMcpScaffoldCommandRequest(
          mapOf("scaffold_payload_version" to "1.0", "kind" to "add-on", "name" to "bill-x"),
        )
      }
    assertTrue(error.message.orEmpty().contains("'platform'"))
  }

  @Test
  fun `wrong field type throws InvalidScaffoldPayloadError with field path`() {
    val error =
      assertFailsWith<InvalidScaffoldPayloadError> {
        parseMcpScaffoldCommandRequest(
          mapOf(
            "scaffold_payload_version" to "1.0",
            "kind" to "horizontal",
            "name" to "bill-foo",
            "no_subagents" to "true",
          ),
        )
      }
    assertTrue(error.message.orEmpty().contains("'no_subagents'"))
  }

  @Test
  fun `invalid baseline layer scope throws InvalidScaffoldPayloadError with field prefix`() {
    val error =
      assertFailsWith<InvalidScaffoldPayloadError> {
        parseMcpScaffoldCommandRequest(
          mapOf(
            "scaffold_payload_version" to "1.0",
            "kind" to "platform-pack",
            "platform" to "kotlin",
            "routing_signals" to mapOf("strong" to listOf(".kt")),
            "baseline_layers" to
              listOf(
                mapOf(
                  "platform" to "kmp",
                  "skill" to "bill-kmp-code-review",
                  "scope" to "bogus-scope",
                  "required" to true,
                  "mode" to "kmp-baseline",
                ),
              ),
          ),
        )
      }
    val message = error.message.orEmpty()
    assertTrue("baseline_layers[0].scope" in message, "Got: $message")
    assertTrue("bogus-scope" in message, "Got: $message")
  }

  @Test
  fun `empty baseline_layers list loud-fails with InvalidScaffoldPayloadError`() {
    val error =
      assertFailsWith<InvalidScaffoldPayloadError> {
        parseMcpScaffoldCommandRequest(
          mapOf(
            "scaffold_payload_version" to "1.0",
            "kind" to "platform-pack",
            "platform" to "kotlin",
            "routing_signals" to mapOf("strong" to listOf(".kt")),
            "baseline_layers" to emptyList<Map<String, Any?>>(),
          ),
        )
      }
    assertTrue("at least one layer" in error.message.orEmpty(), "Got: ${error.message}")
  }

  @Test
  fun `routing_signals strong field present but wrong type loud-fails`() {
    val error =
      assertFailsWith<InvalidScaffoldPayloadError> {
        parseMcpScaffoldCommandRequest(
          mapOf(
            "scaffold_payload_version" to "1.0",
            "kind" to "platform-pack",
            "platform" to "kotlin",
            "routing_signals" to mapOf("strong" to "not-a-list"),
          ),
        )
      }
    val message = error.message.orEmpty()
    assertTrue("routing_signals.strong" in message, "Got: $message")
    assertTrue("must be a list of strings" in message, "Got: $message")
  }

  @Test
  fun `routing_signals tie_breakers field present but wrong type loud-fails`() {
    val error =
      assertFailsWith<InvalidScaffoldPayloadError> {
        parseMcpScaffoldCommandRequest(
          mapOf(
            "scaffold_payload_version" to "1.0",
            "kind" to "platform-pack",
            "platform" to "kotlin",
            "routing_signals" to
              mapOf(
                "strong" to listOf(".kt"),
                "tie_breakers" to "not-a-list",
              ),
          ),
        )
      }
    val message = error.message.orEmpty()
    assertTrue("routing_signals.tie_breakers" in message, "Got: $message")
    assertTrue("must be a list of strings" in message, "Got: $message")
  }

  @Test
  fun `agent-addon payload parses all governed fields`() {
    val request =
      parseMcpScaffoldCommandRequest(
        mapOf(
          "scaffold_payload_version" to "1.0",
          "kind" to "agent-addon",
          "slug" to "review-helper",
          "description" to "Review helper",
          "agent_ids" to listOf("codex"),
          "consumers" to listOf("skill-bill"),
        ),
      ) as ScaffoldCommandRequest.AgentAddon
    assertEquals("review-helper", request.slug)
    assertEquals(listOf("skill-bill"), request.consumers)
  }
}
