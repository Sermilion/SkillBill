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
      assertNull(CodexSchemaAdmission.inspect(directory, "worker-model", "high", true))
      assertNull(CodexSchemaAdmission.inspect(directory, null, null, false))
      assertNull(CodexSchemaAdmission.inspect(directory, "worker-model", "high", true, readOnly = true))
      val file = directory.resolve("v2/ThreadStartParams.json")
      val mapper = ObjectMapper()
      val schema = mapper.readTree(file.toFile())
      val required = mapper.createArrayNode().add("newMandatoryField")
      (schema as ObjectNode).set<ArrayNode>("required", required)
      mapper.writeValue(file.toFile(), schema)
      assertNotNull(CodexSchemaAdmission.inspect(directory, "worker-model", "high", true))
    } finally {
      directory.toFile().deleteRecursively()
    }
  }
}
