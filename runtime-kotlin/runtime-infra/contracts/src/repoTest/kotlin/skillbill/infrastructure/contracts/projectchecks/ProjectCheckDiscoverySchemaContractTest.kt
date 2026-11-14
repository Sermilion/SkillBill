package skillbill.infrastructure.contracts.projectchecks

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.config.projectchecks.PROJECT_CHECK_DISCOVERY_CONTRACT_VERSION
import skillbill.contracts.config.projectchecks.ProjectCheckCategory
import skillbill.contracts.config.projectchecks.ProjectCheckDiscoveryKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.projectchecks.ProjectCheckConfigFailureCode
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.locator.ProjectCheckDiscoverySchemaPaths
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectCheckDiscoverySchemaContractTest {
  @Test
  fun `schema version and discovery keys match the Kotlin owner`() {
    val schema =
      YAMLMapper().readTree(
        Files.readString(repoRootFromTest().resolve(ProjectCheckDiscoverySchemaPaths.REPO_RELATIVE_PATH)),
      )
    val properties = schema.path("properties")
    val categories = properties.path(ProjectCheckDiscoveryKeys.CATEGORIES).path("properties")

    assertEquals(
      PROJECT_CHECK_DISCOVERY_CONTRACT_VERSION,
      properties.path(ProjectCheckDiscoveryKeys.CONTRACT_VERSION).path("const").asText(),
    )
    assertEquals(
      setOf(
        ProjectCheckDiscoveryKeys.BUILD,
        ProjectCheckDiscoveryKeys.LINT,
        ProjectCheckDiscoveryKeys.TEST,
        ProjectCheckDiscoveryKeys.FORMATTING,
        ProjectCheckDiscoveryKeys.VALIDATION,
      ),
      categories.fieldNames().asSequence().toSet(),
    )
  }

  @Test
  fun `validator accepts explicit empty command lists for requested categories`() {
    val validated =
      ProjectCheckDiscoverySchemaValidator().validate(
        """
        {"contract_version":"0.1","project_id":"p-1","categories":{"validation":{"commands":[]}}}
        """.trimIndent(),
        "p-1",
        setOf(ProjectCheckCategory.VALIDATION),
        "agent stdout",
      )

    assertEquals(emptyList(), validated.categories.getValue(ProjectCheckCategory.VALIDATION).commands)
  }

  @Test
  fun `invalid discovery output uses discovery failure codes`() {
    val validator = ProjectCheckDiscoverySchemaValidator()
    val malformed =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validate("{", "p-1", setOf(ProjectCheckCategory.BUILD), "agent stdout")
      }
    val unsupported =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validate(
          """
          {"contract_version":"9","project_id":"p-1","categories":{"build":{"commands":[]}}}
          """.trimIndent(),
          "p-1",
          setOf(ProjectCheckCategory.BUILD),
          "agent stdout",
        )
      }
    assertEquals(ProjectCheckConfigFailureCode.PROJECT_CHECK_DISCOVERY_INVALID, malformed.code)
    assertEquals(ProjectCheckConfigFailureCode.PROJECT_CHECK_DISCOVERY_UNSUPPORTED_VERSION, unsupported.code)
    assertTrue(malformed.message.orEmpty().contains("Retry discovery"))
  }

  @Test
  fun `validator enforces requested category correspondence`() {
    val validator = ProjectCheckDiscoverySchemaValidator()
    val missingCategory =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validate(
          """
          {"contract_version":"0.1","project_id":"p-1","categories":{"build":{"commands":[]}}}
          """.trimIndent(),
          "p-1",
          setOf(ProjectCheckCategory.BUILD, ProjectCheckCategory.VALIDATION),
          "agent stdout",
        )
      }
    val extraCategory =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validate(
          """
          {"contract_version":"0.1","project_id":"p-1",
           "categories":{"build":{"commands":[]},"validation":{"commands":[]}}}
          """.trimIndent(),
          "p-1",
          setOf(ProjectCheckCategory.BUILD),
          "agent stdout",
        )
      }
    assertEquals(ProjectCheckConfigFailureCode.PROJECT_CHECK_DISCOVERY_INVALID, missingCategory.code)
    assertEquals(ProjectCheckConfigFailureCode.PROJECT_CHECK_DISCOVERY_INVALID, extraCategory.code)
    val wrongProject =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validate(
          """
          {"contract_version":"0.1","project_id":"other","categories":{"build":{"commands":[]}}}
          """.trimIndent(),
          "p-1",
          setOf(ProjectCheckCategory.BUILD),
          "agent stdout",
        )
      }
    val nullCategory =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validate(
          """
          {"contract_version":"0.1","project_id":"p-1","categories":{"build":null}}
          """.trimIndent(),
          "p-1",
          setOf(ProjectCheckCategory.BUILD),
          "agent stdout",
        )
      }
    val missingVersion =
      assertFailsWith<SkillBillRuntimeException> {
        validator.validate(
          """
          {"project_id":"p-1","categories":{"build":{"commands":[]}}}
          """.trimIndent(),
          "p-1",
          setOf(ProjectCheckCategory.BUILD),
          "agent stdout",
        )
      }
    assertEquals(ProjectCheckConfigFailureCode.PROJECT_CHECK_DISCOVERY_INVALID, wrongProject.code)
    assertEquals(ProjectCheckConfigFailureCode.PROJECT_CHECK_DISCOVERY_INVALID, nullCategory.code)
    assertEquals(ProjectCheckConfigFailureCode.PROJECT_CHECK_DISCOVERY_INVALID, missingVersion.code)
  }

  @Test
  fun `duplicate fields and trailing JSON cannot be decoded as valid discovery output`() {
    val validator = ProjectCheckDiscoverySchemaValidator()
    val valid =
      """
      {"contract_version":"0.1","project_id":"p-1","categories":{"build":{"commands":[{"argv":["tool"],"working_directory":"."}]}}}
      """.trimIndent()
    val ambiguous =
      """
      {"contract_version":"0.1","project_id":"p-1",
       "categories":{"build":{"commands":[{"argv":["tool"],"argv":["other"],"working_directory":"."}]}}}
      """.trimIndent()
    listOf(ambiguous, "$valid {}").forEach { document ->
      val failure =
        assertFailsWith<SkillBillRuntimeException> {
          validator.validate(document, "p-1", setOf(ProjectCheckCategory.BUILD), "agent stdout")
        }
      assertEquals(ProjectCheckConfigFailureCode.PROJECT_CHECK_DISCOVERY_INVALID, failure.code)
    }
  }

  @Test
  fun `validator follows the canonical schema for directory names and command fields`() {
    val schema =
      ClasspathContractSchemaLoader.compileUncachedYamlNode(
        YAMLMapper().readTree(
          Files.readString(repoRootFromTest().resolve(ProjectCheckDiscoverySchemaPaths.REPO_RELATIVE_PATH)),
        ),
      )
    val mapper = ClasspathContractSchemaLoader.sharedObjectMapper()
    val validator = ProjectCheckDiscoverySchemaValidator()
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
        val response = validator.validate(document, "p-1", setOf(ProjectCheckCategory.BUILD), "agent stdout")
        assertEquals(
          mapper.readTree(command).path("working_directory").asText(),
          response.categories.getValue(ProjectCheckCategory.BUILD).commands.single().workingDirectory,
        )
      } else {
        val failure =
          assertFailsWith<SkillBillRuntimeException> {
            validator.validate(document, "p-1", setOf(ProjectCheckCategory.BUILD), "agent stdout")
          }
        assertEquals(ProjectCheckConfigFailureCode.PROJECT_CHECK_DISCOVERY_INVALID, failure.code)
      }
    }
  }

  private fun document(command: String): String =
    """
    {"contract_version":"0.1","project_id":"p-1","categories":{"build":{"commands":[$command]}}}
    """.trimIndent()
}
