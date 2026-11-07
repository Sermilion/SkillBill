package skillbill.infrastructure.launcher.process.codex

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

internal fun writeCodexSchemaFixture(directory: Path) {
  val mapper = ObjectMapper()
  val resource = requireNotNull(CodexSchemaAdmissionTest::class.java.getResourceAsStream("/codex-0.166.0-schema.json"))
  val bundle = resource.use(mapper::readTree)
  bundle.properties().forEach { (name, schema) ->
    val file = directory.resolve(name)
    Files.createDirectories(file.parent)
    mapper.writeValue(file.toFile(), schema)
  }
}
