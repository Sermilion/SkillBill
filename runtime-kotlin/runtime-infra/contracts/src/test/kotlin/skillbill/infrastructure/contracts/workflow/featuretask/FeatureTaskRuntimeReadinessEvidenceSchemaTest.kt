package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_READINESS_EVIDENCE_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimeReadinessEvidenceSchemaPaths
import skillbill.testing.repoRootFromTest
import skillbill.workflow.taskruntime.artifact.decodeReadinessEvidenceFromArtifact
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeReadinessCheckStatus
import skillbill.workflow.taskruntime.model.validation.ReadinessEvidencePayloadKeys
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeReadinessEvidenceSchemaTest {
  @Test
  fun `schema version and identity stay in parity`() {
    val path = repoRootFromTest().resolve(FeatureTaskRuntimeReadinessEvidenceSchemaPaths.REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(path))
    val schema = YAMLMapper().readTree(Files.readString(path))
    assertEquals(
      FEATURE_TASK_RUNTIME_READINESS_EVIDENCE_CONTRACT_VERSION,
      schema.path("properties").path(ReadinessEvidencePayloadKeys.CONTRACT_VERSION).path("const").asText(),
    )
    assertEquals(FeatureTaskRuntimeReadinessEvidenceSchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
  }

  @Test
  fun `validator accepts passing selected checks`() {
    FeatureTaskRuntimeReadinessEvidenceSchemaValidator.validate(sampleEvidenceMap(), "test")
    requireNotNull(decodeReadinessEvidenceFromArtifact(sampleEvidenceMap(), "test")).requireReady(
      "test",
      expectedSourceTreeSha = "tree1",
      expectedBaseRefSha = "base1",
      expectedHeadSha = "head1",
    )
  }

  @Test
  fun `missing selected check result cannot validate as passing`() {
    val payload =
      sampleEvidenceMap(
        selectedChecks = listOf("pack-collect-all", "plugin-ci"),
        checkResults =
          listOf(
            checkResult("pack-collect-all", 0, FeatureTaskRuntimeReadinessCheckStatus.PASSED),
          ),
      )
    FeatureTaskRuntimeReadinessEvidenceSchemaValidator.validate(payload, "missing-selected")
    assertFailsWith<SkillBillRuntimeException> {
      requireNotNull(decodeReadinessEvidenceFromArtifact(payload, "missing-selected")).requireReady(
        "missing-selected",
        "tree1",
        "base1",
        "head1",
      )
    }
  }

  @Test
  fun `unpersisted selected check cannot validate as passing`() {
    val payload =
      sampleEvidenceMap(
        checkResults =
          listOf(
            checkResult("pack-collect-all", 0, FeatureTaskRuntimeReadinessCheckStatus.UNPERSISTED),
          ),
      )
    FeatureTaskRuntimeReadinessEvidenceSchemaValidator.validate(payload, "unpersisted")
    assertFailsWith<SkillBillRuntimeException> {
      requireNotNull(decodeReadinessEvidenceFromArtifact(payload, "unpersisted")).requireReady(
        "unpersisted",
        "tree1",
        "base1",
        "head1",
      )
    }
  }

  @Test
  fun `nonzero exit on selected check cannot validate as passing`() {
    val payload =
      sampleEvidenceMap(
        checkResults =
          listOf(
            checkResult("pack-collect-all", 1, FeatureTaskRuntimeReadinessCheckStatus.FAILED),
          ),
      )
    FeatureTaskRuntimeReadinessEvidenceSchemaValidator.validate(payload, "nonzero-exit")
    assertFailsWith<SkillBillRuntimeException> {
      requireNotNull(decodeReadinessEvidenceFromArtifact(payload, "nonzero-exit")).requireReady(
        "nonzero-exit",
        "tree1",
        "base1",
        "head1",
      )
    }
  }

  @Test
  fun `plugin check failure outside the runtime gate cannot validate as passing`() {
    val payload =
      sampleEvidenceMap(
        selectedChecks = listOf("plugin-ci:check"),
        checkResults =
          listOf(
            checkResult(
              "plugin-ci:check",
              1,
              FeatureTaskRuntimeReadinessCheckStatus.FAILED,
            ).toMutableMap().apply {
              this[ReadinessEvidencePayloadKeys.COMMAND] =
                "(cd intellij-plugin && ./gradlew clean check --no-build-cache)"
            },
          ),
      )
    FeatureTaskRuntimeReadinessEvidenceSchemaValidator.validate(payload, "plugin-failure")
    assertFailsWith<SkillBillRuntimeException> {
      requireNotNull(decodeReadinessEvidenceFromArtifact(payload, "plugin-failure")).requireReady(
        "plugin-failure",
        "tree1",
        "base1",
        "head1",
      )
    }
  }

  @Test
  fun `schema is available on the classpath`() {
    assertNotNull(
      javaClass.classLoader.getResourceAsStream(
        FeatureTaskRuntimeReadinessEvidenceSchemaPaths.CLASSPATH_RESOURCE,
      ),
    )
  }

  private fun sampleEvidenceMap(
    selectedChecks: List<String> = listOf("pack-collect-all"),
    checkResults: List<Map<String, Any?>> =
      listOf(
        checkResult("pack-collect-all", 0, FeatureTaskRuntimeReadinessCheckStatus.PASSED),
      ),
  ): Map<String, Any?> =
    mapOf(
      ReadinessEvidencePayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_READINESS_EVIDENCE_CONTRACT_VERSION,
      ReadinessEvidencePayloadKeys.SOURCE_TREE_SHA to "tree1",
      ReadinessEvidencePayloadKeys.BASE_REF_SHA to "base1",
      ReadinessEvidencePayloadKeys.HEAD_SHA to "head1",
      ReadinessEvidencePayloadKeys.SELECTED_CHECKS to selectedChecks,
      ReadinessEvidencePayloadKeys.CHECK_RESULTS to checkResults,
    )

  private fun checkResult(
    checkId: String,
    exitCode: Int,
    status: FeatureTaskRuntimeReadinessCheckStatus,
  ): Map<String, Any?> =
    mapOf(
      ReadinessEvidencePayloadKeys.CHECK_ID to checkId,
      ReadinessEvidencePayloadKeys.COMMAND to "./gradlew check",
      ReadinessEvidencePayloadKeys.EXIT_CODE to exitCode,
      ReadinessEvidencePayloadKeys.STATUS to status.wireValue,
    )
}
