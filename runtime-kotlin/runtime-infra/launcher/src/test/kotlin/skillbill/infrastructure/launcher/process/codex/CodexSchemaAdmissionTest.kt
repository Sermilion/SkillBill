package skillbill.infrastructure.launcher.process.codex

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CodexSchemaAdmissionTest {
  @Test
  fun `installed baseline schema admits responses with mandatory inbound fields and rejects new request obligations`() {
    val directory = Files.createTempDirectory("codex-schema-admission")
    try {
      writeCodexSchemaFixture(directory)
      assertNull(CodexSchemaAdmission.inspect(directory, WORKER))
      assertNull(CodexSchemaAdmission.inspect(directory, PLAIN))
      val file = directory.resolve("v2/ThreadStartParams.json")
      val mapper = ObjectMapper()
      val schema = mapper.readTree(file.toFile())
      val required = mapper.createArrayNode().add("newMandatoryField")
      (schema as ObjectNode).set<ArrayNode>("required", required)
      mapper.writeValue(file.toFile(), schema)
      assertNotNull(CodexSchemaAdmission.inspect(directory, WORKER))
    } finally {
      directory.toFile().deleteRecursively()
    }
  }

  @Test
  fun `local image inputs are admitted only while the schema offers a local image variant with a path`() {
    val directory = Files.createTempDirectory("codex-schema-images")
    try {
      writeCodexSchemaFixture(directory)
      assertNull(CodexSchemaAdmission.inspect(directory, PLAIN, localImages = true))
      val file = directory.resolve("v2/TurnStartParams.json")
      val mapper = ObjectMapper()
      val schema = mapper.readTree(file.toFile())
      val variants = schema.path("definitions").path("UserInput").path("oneOf") as ArrayNode
      val localImage =
        variants.indexOfFirst { variant ->
          variant.path("properties").path("type").path("enum").any { it.asText() == "localImage" }
        }
      variants.remove(localImage)
      mapper.writeValue(file.toFile(), schema)
      assertNull(CodexSchemaAdmission.inspect(directory, PLAIN))
      assertNotNull(CodexSchemaAdmission.inspect(directory, PLAIN, localImages = true))
    } finally {
      directory.toFile().deleteRecursively()
    }
  }

  @Test
  fun `bypass refuses a schema without never approvals on any opening or turn`() {
    val directory = Files.createTempDirectory("codex-schema-bypass")
    val mapper = ObjectMapper()
    try {
      for (name in listOf("ThreadStartParams", "ThreadResumeParams", "TurnStartParams")) {
        writeCodexSchemaFixture(directory)
        val file = directory.resolve("v2/$name.json")
        val schema = mapper.readTree(file.toFile())
        val approval = schema.path("properties").path("approvalPolicy") as ObjectNode
        approval.removeAll()
        approval.put("type", "string")
        approval.set<ArrayNode>("enum", mapper.createArrayNode().add("on-request"))
        mapper.writeValue(file.toFile(), schema)

        assertNotNull(CodexSchemaAdmission.inspect(directory, PLAIN), name)
      }
    } finally {
      directory.toFile().deleteRecursively()
    }
  }

  @Test
  fun `only the supported codex-cli version is admitted`() {
    assertNull(codexVersionRefusal("codex-cli $CODEX_PROTOCOL_VERSION\n"))
    assertNotNull(codexVersionRefusal("codex-cli 0.165.0"))
    assertNotNull(codexVersionRefusal("codex-cli ${CODEX_PROTOCOL_VERSION}1"))
  }

  private companion object {
    val WORKER =
      CodexThreadSettings("/work", "worker-model", "high", workspaceRoots = listOf("/extra"))
    val PLAIN = CodexThreadSettings("/work", null, null)
  }
}
