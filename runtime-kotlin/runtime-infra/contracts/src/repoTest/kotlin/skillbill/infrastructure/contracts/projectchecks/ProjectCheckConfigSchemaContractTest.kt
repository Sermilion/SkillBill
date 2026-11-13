package skillbill.infrastructure.contracts.projectchecks

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.config.projectchecks.PROJECT_CHECK_CONFIG_CONTRACT_VERSION
import skillbill.contracts.config.projectchecks.ProjectCheckConfigKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.projectchecks.ProjectCheckConfigFailureCode
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.locator.ProjectCheckConfigSchemaPaths
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectCheckConfigSchemaContractTest {
  @Test
  fun `schema version and row keys match the Kotlin owner`() {
    val schema =
      YAMLMapper().readTree(
        Files.readString(repoRootFromTest().resolve(ProjectCheckConfigSchemaPaths.REPO_RELATIVE_PATH)),
      )
    val properties = schema.path("properties")

    assertEquals(
      PROJECT_CHECK_CONFIG_CONTRACT_VERSION,
      properties.path(ProjectCheckConfigKeys.CONTRACT_VERSION).path("const").asText(),
    )
    assertEquals(
      setOf(
        ProjectCheckConfigKeys.CONTRACT_VERSION,
        ProjectCheckConfigKeys.PROJECT_ID,
        ProjectCheckConfigKeys.BUILD_COMMANDS,
        ProjectCheckConfigKeys.LINT_COMMANDS,
        ProjectCheckConfigKeys.TEST_COMMANDS,
        ProjectCheckConfigKeys.FORMATTING_COMMANDS,
        ProjectCheckConfigKeys.VALIDATION_COMMANDS,
      ),
      properties.fieldNames().asSequence().toSet(),
    )
  }

  @Test
  fun `validator preserves known empty categories and empty command arguments`() {
    val config =
      ProjectCheckConfigSchemaValidator().validate(
        """
        {"contract_version":"0.1","project_id":"p-1","build_commands":{"commands":[]},
         "lint_commands":null,"test_commands":{"commands":[{"argv":["tool",""],"working_directory":"."}]},
         "formatting_commands":null,"validation_commands":null}
        """.trimIndent(),
        "p-1",
        "/tmp/runtime.db",
      )

    assertEquals(emptyList(), config.buildCommands?.commands)
    assertEquals("", config.testCommands?.commands?.single()?.argv?.last())
    assertNull(config.lintCommands)
  }

  @Test
  fun `invalid stored values fail with project config error codes`() {
    val validator = ProjectCheckConfigSchemaValidator()
    val malformed =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validate("{", "p-1", "/tmp/runtime.db")
      }
    val unsupported =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validate(
          """
          {"contract_version":"9","project_id":"p-1","build_commands":null,"lint_commands":null,
           "test_commands":null,"formatting_commands":null,"validation_commands":null}
          """.trimIndent(),
          "p-1",
          "/tmp/runtime.db",
        )
      }
    assertEquals(ProjectCheckConfigFailureCode.PROJECT_CHECK_CONFIG_INVALID, malformed.code)
    assertEquals(ProjectCheckConfigFailureCode.PROJECT_CHECK_CONFIG_UNSUPPORTED_VERSION, unsupported.code)
    assertTrue(malformed.message.orEmpty().contains("/tmp/runtime.db"))
    assertTrue(unsupported.message.orEmpty().contains("Preserve a copy"))
  }

  @Test
  fun `validator follows the canonical schema for directory names and command fields`() {
    val schema =
      ClasspathContractSchemaLoader.compileUncachedYamlNode(
        YAMLMapper().readTree(
          Files.readString(repoRootFromTest().resolve(ProjectCheckConfigSchemaPaths.REPO_RELATIVE_PATH)),
        ),
      )
    val mapper = ClasspathContractSchemaLoader.sharedObjectMapper()
    val validator = ProjectCheckConfigSchemaValidator()
    val commands =
      listOf(
        """{"argv":["tool",""],"working_directory":" "}""",
        """{"argv":["tool"],"working_directory":"module dir"}""",
        """{"argv":["tool"],"working_directory":"../outside"}""",
        """{"argv":["tool"],"working_directory":"module","unknown":true}""",
        """{"argv":[],"working_directory":"."}""",
      )

    commands.forEach { command ->
      val document = document(command)
      val schemaAccepts = ClasspathContractSchemaLoader.validate(schema, mapper.readTree(document)).isEmpty()
      if (schemaAccepts) {
        val config = validator.validate(document, "p-1", "/tmp/runtime.db")
        assertEquals(
          mapper.readTree(command).path("working_directory").asText(),
          config.buildCommands?.commands?.single()?.workingDirectory,
        )
      } else {
        val failure =
          assertFailsWith<SkillBillRuntimeException> {
            validator.validate(document, "p-1", "/tmp/runtime.db")
          }
        assertEquals(ProjectCheckConfigFailureCode.PROJECT_CHECK_CONFIG_INVALID, failure.code)
      }
    }
  }

  @Test
  fun `duplicate fields and trailing JSON cannot be decoded as a valid command`() {
    val valid = document("""{"argv":["tool"],"working_directory":"."}""")
    val ambiguous = document("""{"argv":["tool"],"argv":["other"],"working_directory":"."}""")
    listOf(ambiguous, "$valid {}").forEach { document ->
      val failure =
        assertFailsWith<SkillBillRuntimeException> {
          ProjectCheckConfigSchemaValidator().validate(document, "p-1", "/tmp/runtime.db")
        }
      assertEquals(ProjectCheckConfigFailureCode.PROJECT_CHECK_CONFIG_INVALID, failure.code)
    }
  }

  private fun document(command: String): String =
    """
    {"contract_version":"0.1","project_id":"p-1","build_commands":{"commands":[$command]},
     "lint_commands":null,"test_commands":null,"formatting_commands":null,"validation_commands":null}
    """.trimIndent()
}
