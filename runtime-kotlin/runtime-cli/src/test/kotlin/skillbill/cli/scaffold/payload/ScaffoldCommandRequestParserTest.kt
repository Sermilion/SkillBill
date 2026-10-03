package skillbill.cli.scaffold.payload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import skillbill.application.scaffold.decodeScaffoldCommandRequest
import skillbill.contracts.JsonCodec
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ScaffoldFailureCode
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.command.ScaffoldCommandRequest

private fun parseScaffoldCommandRequest(payload: Map<String, Any?>): ScaffoldCommandRequest =
  decodeScaffoldCommandRequest(JsonCodec.mapToJsonString(payload))

class ScaffoldCommandRequestParserTest {
  @Test
  fun `parses horizontal skill request`() {
    val request =
      parseScaffoldCommandRequest(
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
      parseScaffoldCommandRequest(
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
      parseScaffoldCommandRequest(
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
      assertFailsWith<SkillBillRuntimeException> {
        parseScaffoldCommandRequest(
          mapOf(
            "scaffold_payload_version" to "1.0",
            "kind" to "platform-pack",
            "platform" to "kotlin",
            "skeleton_mode" to "starter",
          ),
        )
      }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
    val message = error.message.orEmpty()
    assertTrue("skeleton_mode" in message, "Got: $message")
    assertTrue("no longer supported" in message, "Got: $message")
    assertTrue("remove unwanted focus areas" in message, "Got: $message")
  }

  @Test
  fun `platform pack request rejects retired specialist_areas with migration message`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        parseScaffoldCommandRequest(
          mapOf(
            "scaffold_payload_version" to "1.0",
            "kind" to "platform-pack",
            "platform" to "kotlin",
            "specialist_areas" to listOf("ui"),
          ),
        )
      }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
    val message = error.message.orEmpty()
    assertTrue("specialist_areas" in message, "Got: $message")
    assertTrue("no longer supported" in message, "Got: $message")
    assertTrue("remove unwanted focus areas" in message, "Got: $message")
  }

  @Test
  fun `parses add-on request`() {
    val request =
      parseScaffoldCommandRequest(
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
  fun `version mismatch throws scaffoldPayloadVersionMismatchError`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        parseScaffoldCommandRequest(
          mapOf("scaffold_payload_version" to "9.9", "kind" to "horizontal", "name" to "bill-foo"),
        )
      }.also { assertEquals(ScaffoldFailureCode.PAYLOAD_VERSION_MISMATCH, it.code) }
    assertTrue(error.message.orEmpty().contains("9.9"))
  }

  @Test
  fun `unknown kind throws unknownSkillKindError`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        parseScaffoldCommandRequest(
          mapOf("scaffold_payload_version" to "1.0", "kind" to "not-a-kind"),
        )
      }.also { assertEquals(ScaffoldFailureCode.UNKNOWN_SKILL_KIND, it.code) }
    assertTrue(error.message.orEmpty().contains("not-a-kind"))
  }

  @Test
  fun `missing required field throws invalidScaffoldPayloadError`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        parseScaffoldCommandRequest(
          mapOf("scaffold_payload_version" to "1.0", "kind" to "horizontal"),
        )
      }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
    assertTrue(error.message.orEmpty().contains("'name'"))
  }

  @Test
  fun `wrong field type throws invalidScaffoldPayloadError`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        parseScaffoldCommandRequest(
          mapOf(
            "scaffold_payload_version" to "1.0",
            "kind" to "horizontal",
            "name" to "bill-foo",
            "no_subagents" to "true",
          ),
        )
      }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
    assertTrue(error.message.orEmpty().contains("'no_subagents'"))
  }

  @Test
  fun `retired partial kind aliases throw retiredScaffoldKindError`() {
    listOf("platform-override-piloted", "platform-override", "override", "code-review-area", "area", "specialist")
      .forEach { kind ->
        val error =
          assertFailsWith<SkillBillRuntimeException> {
            parseScaffoldCommandRequest(
              mapOf("scaffold_payload_version" to "1.0", "kind" to kind),
            )
          }.also { assertEquals(ScaffoldFailureCode.RETIRED_KIND, it.code) }
        val message = error.message.orEmpty()
        assertTrue(kind in message, "Got: $message")
        assertTrue("platform-pack" in message, "Got: $message")
        assertTrue("edit/remove existing platform-pack content" in message, "Got: $message")
      }
  }

  @Test
  fun `empty baseline_layers list loud-fails with invalidScaffoldPayloadError`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        parseScaffoldCommandRequest(
          mapOf(
            "scaffold_payload_version" to "1.0",
            "kind" to "platform-pack",
            "platform" to "kotlin",
            "routing_signals" to mapOf("strong" to listOf(".kt")),
            "baseline_layers" to emptyList<Map<String, Any?>>(),
          ),
        )
      }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
    assertTrue("at least one layer" in error.message.orEmpty(), "Got: ${error.message}")
  }

  @Test
  fun `routing_signals strong field present but wrong type loud-fails`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        parseScaffoldCommandRequest(
          mapOf(
            "scaffold_payload_version" to "1.0",
            "kind" to "platform-pack",
            "platform" to "kotlin",
            "routing_signals" to mapOf("strong" to "not-a-list"),
          ),
        )
      }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
    val message = error.message.orEmpty()
    assertTrue("routing_signals.strong" in message, "Got: $message")
    assertTrue("must be a list of strings" in message, "Got: $message")
  }

  @Test
  fun `invalid baseline layer scope throws invalidScaffoldPayloadError with field prefix`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        parseScaffoldCommandRequest(
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
      }.also { assertEquals(ScaffoldFailureCode.INVALID_PAYLOAD, it.code) }
    val message = error.message.orEmpty()
    assertTrue("baseline_layers[0].scope" in message, "Got: $message")
    assertTrue("bogus-scope" in message, "Got: $message")
  }

  @Test
  fun `agent-addon payload parses all governed fields`() {
    val request =
      parseScaffoldCommandRequest(
        mapOf(
          "scaffold_payload_version" to "1.0",
          "kind" to "agent-addon",
          "slug" to "review-helper",
          "description" to "Review helper",
          "agent_ids" to listOf("codex", "claude"),
          "consumers" to listOf("skill-bill"),
          "content_body" to "Use the helper.",
        ),
      ) as ScaffoldCommandRequest.AgentAddon
    assertEquals("review-helper", request.slug)
    assertEquals(listOf("codex", "claude"), request.agentIds)
    assertEquals(listOf("skill-bill"), request.consumers)
  }
}
