package skillbill.infrastructure.workflow.monitor

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.contracts.locator.StandaloneMonitorOwnershipSchemaPaths
import skillbill.ports.monitor.model.MonitorOwnershipState
import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorRepositoryIdentity
import skillbill.ports.monitor.model.MonitorRetentionReason
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import skillbill.infrastructure.workflow.monitor.StandaloneMonitorOwnershipKeys as Keys

private const val SHA = "0123456789abcdef0123456789abcdef01234567"
private const val WORKTREE = "/work/repo/.git/skill-bill/worktree"
private val ACQUIRED = Instant.parse("2026-10-16T10:00:00Z")

internal fun sampleOwnershipRecord(
  state: MonitorOwnershipState,
  generation: Long = 3,
): MonitorOwnershipRecord {
  val prepared = state == MonitorOwnershipState.PREPARED || state == MonitorOwnershipState.RETAINED
  return MonitorOwnershipRecord(
    target = MonitorPullRequestIdentity(MonitorRepositoryIdentity.canonical("github.com", "Acme", "Repo"), 42),
    invocationId = "invocation-1",
    ownerToken = "owner-token-0123456789",
    generation = generation,
    process = FeatureTaskRuntimeProcessIdentity("host", "boot", 4242, "birth"),
    callerRoot = "/work/repo",
    primaryRoot = "/work/repo",
    commonDir = "/work/repo/.git",
    state = state,
    executionPath = WORKTREE.takeIf { prepared },
    executionBranch = "skill-bill/monitor/owner".takeIf { prepared },
    ownedRefs = if (state == MonitorOwnershipState.RELEASED) emptyList() else listOf("refs/skill-bill/monitor/o/head"),
    pinnedHeadSha = SHA.takeIf { prepared },
    pinnedBaseSha = SHA.takeIf { prepared },
    lastPublishedSha = SHA.takeIf { prepared },
    acquiredAt = ACQUIRED,
    updatedAt = ACQUIRED.plusSeconds(5),
    retention =
      MonitorRetentionRecord(MonitorRetentionReason.DIRTY_WORKTREE, "two files changed", WORKTREE)
        .takeIf { state == MonitorOwnershipState.RETAINED },
  )
}

class StandaloneMonitorOwnershipCodecTest {
  private val mapper = ObjectMapper()

  @Test
  fun `every ownership state round trips through the strict codec`() {
    MonitorOwnershipState.entries.forEach { state ->
      val record = sampleOwnershipRecord(state)
      val decoded = StandaloneMonitorOwnershipCodec.decode(StandaloneMonitorOwnershipCodec.encode(record), "t")
      assertEquals(record, decoded)
    }
  }

  @Test
  fun `wire keys match the canonical ownership schema exactly`() {
    val resource = StandaloneMonitorOwnershipSchemaPaths.CLASSPATH_RESOURCE
    val stream = assertNotNull(javaClass.classLoader.getResourceAsStream(resource))
    val properties = stream.use { YAMLMapper().readTree(it) }.path("properties")

    assertEquals(Keys.TOP_LEVEL, fieldsOf(properties))
    assertEquals(Keys.TARGET_FIELDS, fieldsOf(properties.path(Keys.TARGET).path("properties")))
    assertEquals(Keys.PROCESS_FIELDS, fieldsOf(properties.path(Keys.PROCESS).path("properties")))
    assertEquals(Keys.RETENTION_FIELDS, fieldsOf(properties.path(Keys.RETENTION).path("properties")))
  }

  private fun fieldsOf(node: JsonNode): Set<String> = node.fieldNames().asSequence().toSet()

  @Test
  fun `a document with an unknown key at any level is rejected`() {
    listOf<(ObjectNode) -> Unit>(
      { it.put("surprise", true) },
      { (it.path(Keys.TARGET) as ObjectNode).put("surprise", true) },
      { (it.path(Keys.PROCESS) as ObjectNode).put("surprise", true) },
    ).forEach { corrupt ->
      assertRejected(StandaloneMonitorFailureCode.INVALID_OWNERSHIP_SCHEMA, corrupt = corrupt)
    }
  }

  @Test
  fun `a legacy or absent contract version is rejected before any field is read`() {
    assertRejected(StandaloneMonitorFailureCode.UNSUPPORTED_OWNERSHIP_VERSION) { it.put(Keys.CONTRACT_VERSION, "0.9") }
    assertRejected(StandaloneMonitorFailureCode.UNSUPPORTED_OWNERSHIP_VERSION) { it.remove(Keys.CONTRACT_VERSION) }
  }

  @Test
  fun `malformed scalar fields are rejected with the schema failure code`() {
    listOf<(ObjectNode) -> Unit>(
      { it.put(Keys.OWNER_TOKEN, "short") },
      { it.put(Keys.GENERATION, 0) },
      { it.put(Keys.STATE, "paused") },
      { it.put(Keys.PINNED_HEAD_SHA, "nothex") },
      { it.put(Keys.ACQUIRED_AT, "yesterday") },
      { it.remove(Keys.PROCESS) },
      { (it.path(Keys.TARGET) as ObjectNode).put(Keys.TARGET_URL, "https://github.com/acme/repo/pull/43") },
    ).forEach { corrupt ->
      assertRejected(StandaloneMonitorFailureCode.INVALID_OWNERSHIP_SCHEMA, MonitorOwnershipState.PREPARED, corrupt)
    }
  }

  @Test
  fun `incoherent state tuples are rejected`() {
    assertRejected(StandaloneMonitorFailureCode.INCOHERENT_OWNERSHIP, MonitorOwnershipState.PREPARED) {
      it.remove(Keys.PINNED_BASE_SHA)
    }
    assertRejected(StandaloneMonitorFailureCode.INCOHERENT_OWNERSHIP, MonitorOwnershipState.RETAINED) {
      it.remove(Keys.RETENTION)
    }
    assertRejected(StandaloneMonitorFailureCode.INCOHERENT_OWNERSHIP, MonitorOwnershipState.PREPARED) {
      val retention = mapper.createObjectNode().put(Keys.RETENTION_REASON, "dirty_worktree")
      it.set<ObjectNode>(Keys.RETENTION, retention.put(Keys.DETAIL, "x"))
    }
    assertRejected(StandaloneMonitorFailureCode.INCOHERENT_OWNERSHIP, MonitorOwnershipState.RELEASED) {
      it.put(Keys.EXECUTION_PATH, "/work/repo/.git/skill-bill/worktree")
    }
    assertRejected(StandaloneMonitorFailureCode.INCOHERENT_OWNERSHIP, MonitorOwnershipState.ACQUIRING) {
      it.put(Keys.UPDATED_AT, "2026-10-16T09:00:00Z")
    }
  }

  private fun assertRejected(
    code: StandaloneMonitorFailureCode,
    state: MonitorOwnershipState = MonitorOwnershipState.ACQUIRING,
    corrupt: (ObjectNode) -> Unit,
  ) {
    val document = mapper.readTree(StandaloneMonitorOwnershipCodec.encode(sampleOwnershipRecord(state))) as ObjectNode
    corrupt(document)
    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        StandaloneMonitorOwnershipCodec.decode(mapper.writeValueAsString(document), "t")
      }
    assertEquals(code, failure.code)
  }
}
