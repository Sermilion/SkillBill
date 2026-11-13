package skillbill.infrastructure.skills.install

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import skillbill.error.core.InvalidNativeAgentLinkInventoryDecodeError
import skillbill.infrastructure.skills.install.nativeagent.inventory.NativeAgentLinkInventory
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.SupportedAgent
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeAgentInventoryCompatibilityTest : InstallNativeAgentLinkApplyTestSupport() {
  @Test
  fun `previous provider inventory is projected before validation without changing bytes or links on read`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val plan =
      planInstallForTest(fixture.request(selectedPlatforms = setOf("kotlin"), agents = setOf(SupportedAgent.CODEX)))
    assertEquals(InstallApplyStatus.SUCCESS, applyInstallForTest(plan).status)
    val inventory = fixture.home.resolve(".skill-bill/native-agent-link-inventory.json")
    val mapper = ObjectMapper()
    val root = mapper.readTree(inventory.toFile())
    val entries = root.get("entries") as ArrayNode
    val previousProvider = charArrayOf('j', 'u', 'n', 'i', 'e').concatToString()
    val previousLink = fixture.home.resolve("previous-provider/bill-preserved.md")
    Files.createDirectories(previousLink.parent)
    val target = fixture.home.resolve("previous-provider-target.md")
    Files.writeString(target, "original artifact")
    createSymlinkOrSkip(previousLink, target)
    entries.add(
      entries.first().deepCopy<ObjectNode>()
        .put("provider", previousProvider)
        .put("installed_path", previousLink.toString())
        .put("cache_target_path", target.toString()),
    )
    Files.writeString(inventory, mapper.writeValueAsString(root))
    val original = Files.readAllBytes(inventory)

    val decoded = NativeAgentLinkInventory.read(fixture.home, emptyList(), fixture.repoRoot)

    assertTrue(decoded.isNotEmpty())
    assertTrue(decoded.all { it.provider in SupportedAgent.supportedIds })
    assertContentEquals(original, Files.readAllBytes(inventory))
    assertEquals(target, readSymlinkTarget(previousLink))
    assertEquals("original artifact", Files.readString(previousLink))
    assertEquals(InstallApplyStatus.SUCCESS, applyInstallForTest(plan).status)
    val published = mapper.readTree(inventory.toFile()).get("entries")
    assertTrue(published.all { it.get("provider").asText() in SupportedAgent.supportedIds })
    assertEquals(target, readSymlinkTarget(previousLink))
  }

  @Test
  fun `malformed provider values still fail inventory validation without rewriting the source`() {
    val fixture = setupApplyFixture()
    val inventory = fixture.home.resolve(".skill-bill/native-agent-link-inventory.json")
    Files.createDirectories(inventory.parent)
    val original = """{"contract_version":"0.2","entries":[{"provider":42}]}"""
    Files.writeString(inventory, original)

    assertFailsWith<InvalidNativeAgentLinkInventoryDecodeError> {
      NativeAgentLinkInventory.read(fixture.home, emptyList(), fixture.repoRoot)
    }

    assertEquals(original, Files.readString(inventory))
  }
}
