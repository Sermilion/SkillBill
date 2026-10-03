package skillbill.engine.goalrunner.planning.context

import skillbill.application.decomposition.parentSpecPath
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.error.shellcontent.InvalidGoalPlanningPreparationSchemaError
import skillbill.goalrunner.planning.GoalPlanningExcludedPaths
import skillbill.ports.goalrunner.planning.model.GoalPlanningContext
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus

object GoalPlanningSharedContextPacket {
  const val VERSION = "0.4"
  const val LEGACY_VERSION_0_3 = "0.3"
  const val LEGACY_VERSION_0_2 = "0.2"
  const val LEGACY_VERSION_0_1 = "0.1"
  const val MAX_GOVERNED_CONTEXT_CHARS = 65_536
  private const val MAX_PACKET_CHARS = 524_288

  val PACKET_FIELDS =
    setOf(
      GoalPlanningSharedContextPacketPayloadKeys.PACKET_VERSION,
      GoalPlanningSharedContextPacketPayloadKeys.REPOSITORY_IDENTITY,
      GoalPlanningSharedContextPacketPayloadKeys.NORMALIZED_ISSUE_KEY,
      GoalPlanningSharedContextPacketPayloadKeys.PARENT_SPEC_PATH,
      GoalPlanningSharedContextPacketPayloadKeys.PARENT_SPEC,
      GoalPlanningSharedContextPacketPayloadKeys.DECOMPOSITION_MANIFEST,
      GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY,
      GoalPlanningSharedContextPacketPayloadKeys.VALIDATION_GUIDANCE,
      GoalPlanningSharedContextPacketPayloadKeys.ORDERED_SUBTASKS,
      GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256,
    )
  val LEGACY_V01_FIELDS = PACKET_FIELDS + GoalPlanningSharedContextPacketPayloadKeys.PLATFORM_PACKS

  fun migrate(packet: Map<String, Any?>): Map<String, Any?> =
    when (
      val version = packet[GoalPlanningSharedContextPacketPayloadKeys.PACKET_VERSION]
    ) {
      VERSION -> withoutExcludedCatalogEntries(packet)
      LEGACY_VERSION_0_3 -> GoalPlanningSharedContextPacketLegacy.migrateFromPacketVersion3(packet)
      LEGACY_VERSION_0_2 ->
        GoalPlanningSharedContextPacketLegacy.migrateFromPacketVersion3(
          GoalPlanningSharedContextPacketLegacy.migrateFromPacketVersion2(packet),
        )
      LEGACY_VERSION_0_1 ->
        GoalPlanningSharedContextPacketLegacy.migrateFromPacketVersion3(
          GoalPlanningSharedContextPacketLegacy.migrateFromPacketVersion2(
            GoalPlanningSharedContextPacketLegacy.migrateFromPacketVersion1(packet),
          ),
        )
      else -> throw InvalidGoalPlanningPreparationSchemaError(
        sourceLabel = "_goal_planning_shared_context",
        fieldPath = GoalPlanningSharedContextPacketPayloadKeys.PACKET_VERSION,
        reason =
          "shared context packet version '$version' is unsupported; expected '$VERSION', " +
            "'$LEGACY_VERSION_0_3', '$LEGACY_VERSION_0_2', or '$LEGACY_VERSION_0_1'",
      )
    }

  fun validate(
    packet: Map<String, Any?>,
    repositoryIdentity: String,
    normalizedIssueKey: String,
    parentSpecPath: String,
    subtasks: List<DecompositionSubtask>,
  ) {
    validateIdentity(packet, repositoryIdentity, normalizedIssueKey, parentSpecPath)
    validateContent(packet)
    validateTopology(packet, subtasks)
    validateIntegrity(packet)
  }

  fun validateSource(
    packet: Map<String, Any?>,
    repositoryIdentity: String,
    normalizedIssueKey: String,
    parentSpecPath: String,
    subtasks: List<DecompositionSubtask>,
  ) {
    if (packet[GoalPlanningSharedContextPacketPayloadKeys.PACKET_VERSION] == VERSION) {
      validate(packet, repositoryIdentity, normalizedIssueKey, parentSpecPath, subtasks)
      return
    }
    val version = packet[GoalPlanningSharedContextPacketPayloadKeys.PACKET_VERSION]
    if (version !in setOf(LEGACY_VERSION_0_3, LEGACY_VERSION_0_2, LEGACY_VERSION_0_1)) return
    validateSourceHeader(packet, repositoryIdentity, normalizedIssueKey, parentSpecPath, version)
    if (!GoalPlanningSharedContextPacketValidation.isStringMap(
        packet[GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY],
      )
    ) {
      invalidGoalPlanningSharedContextPacket(
        GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY,
        "shared context source boundary memory is invalid",
      )
    }
    if (version == LEGACY_VERSION_0_1 &&
      !GoalPlanningSharedContextPacketValidation.isStringMap(
        packet[GoalPlanningSharedContextPacketPayloadKeys.PLATFORM_PACKS],
      )
    ) {
      invalidGoalPlanningSharedContextPacket(
        GoalPlanningSharedContextPacketPayloadKeys.PLATFORM_PACKS,
        "shared context source platform packs are invalid",
      )
    }
    val sourceTopology =
      GoalPlanningSharedContextPacketValidation.normalizedSubtasks(
        packet[GoalPlanningSharedContextPacketPayloadKeys.ORDERED_SUBTASKS],
      ).map { it - GoalPlanningSharedContextPacketPayloadKeys.PLANNING_DISPOSITION }
    val expectedTopology =
      GoalPlanningSharedContextPacketValidation.normalizedSubtasks(orderedSubtasks(subtasks))
        .map { it - GoalPlanningSharedContextPacketPayloadKeys.PLANNING_DISPOSITION }
    if (sourceTopology != expectedTopology) {
      invalidGoalPlanningSharedContextPacket(
        GoalPlanningSharedContextPacketPayloadKeys.ORDERED_SUBTASKS,
        "shared context source topology is invalid",
      )
    }
    if (packet[GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256] !=
      digest(packet - GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256)
    ) {
      invalidGoalPlanningSharedContextPacket(
        GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256,
        "shared context source integrity is invalid",
      )
    }
  }

  private fun validateSourceHeader(
    packet: Map<String, Any?>,
    repositoryIdentity: String,
    normalizedIssueKey: String,
    parentSpecPath: String,
    version: Any?,
  ) {
    val fields = if (version == LEGACY_VERSION_0_1) LEGACY_V01_FIELDS else PACKET_FIELDS
    if (packet.keys != fields) {
      invalidGoalPlanningSharedContextPacket(
        "<root>",
        "shared context source fields are invalid",
      )
    }
    if (packet[GoalPlanningSharedContextPacketPayloadKeys.REPOSITORY_IDENTITY] != repositoryIdentity ||
      packet[GoalPlanningSharedContextPacketPayloadKeys.NORMALIZED_ISSUE_KEY] != normalizedIssueKey ||
      packet[GoalPlanningSharedContextPacketPayloadKeys.PARENT_SPEC_PATH] != parentSpecPath
    ) {
      invalidGoalPlanningSharedContextPacket("<identity>", "shared context source identity is invalid")
    }
    if (packet[GoalPlanningSharedContextPacketPayloadKeys.PARENT_SPEC] !is String ||
      (packet[GoalPlanningSharedContextPacketPayloadKeys.DECOMPOSITION_MANIFEST] as? String)
        ?.length?.let { it <= MAX_GOVERNED_CONTEXT_CHARS } != true ||
      packet[GoalPlanningSharedContextPacketPayloadKeys.VALIDATION_GUIDANCE] !is String
    ) {
      invalidGoalPlanningSharedContextPacket("<content>", "shared context source content is invalid")
    }
  }

  private fun validateIdentity(
    packet: Map<String, Any?>,
    repositoryIdentity: String,
    normalizedIssueKey: String,
    parentSpecPath: String,
  ) {
    if (packet.keys != PACKET_FIELDS) {
      invalidGoalPlanningSharedContextPacket("<root>", "shared context packet fields are invalid")
    }
    if (packet[GoalPlanningSharedContextPacketPayloadKeys.PACKET_VERSION] != VERSION) {
      invalidGoalPlanningSharedContextPacket(
        GoalPlanningSharedContextPacketPayloadKeys.PACKET_VERSION,
        "shared context packet version is invalid",
      )
    }
    if (packet[GoalPlanningSharedContextPacketPayloadKeys.REPOSITORY_IDENTITY] != repositoryIdentity) {
      invalidGoalPlanningSharedContextPacket(
        GoalPlanningSharedContextPacketPayloadKeys.REPOSITORY_IDENTITY,
        "shared context repository identity is invalid",
      )
    }
    if (packet[GoalPlanningSharedContextPacketPayloadKeys.NORMALIZED_ISSUE_KEY] != normalizedIssueKey) {
      invalidGoalPlanningSharedContextPacket(
        GoalPlanningSharedContextPacketPayloadKeys.NORMALIZED_ISSUE_KEY,
        "shared context issue key is invalid",
      )
    }
    if (packet[DecompositionPlanningPayloadKeys.PARENT_SPEC_PATH] != parentSpecPath) {
      invalidGoalPlanningSharedContextPacket(
        DecompositionPlanningPayloadKeys.PARENT_SPEC_PATH,
        "shared context parent spec path is invalid",
      )
    }
  }

  private fun validateContent(packet: Map<String, Any?>) {
    if (packet[GoalPlanningSharedContextPacketPayloadKeys.PARENT_SPEC] !is String) {
      invalidGoalPlanningSharedContextPacket(
        GoalPlanningSharedContextPacketPayloadKeys.PARENT_SPEC,
        "shared context parent spec is invalid",
      )
    }
    if ((packet[GoalPlanningSharedContextPacketPayloadKeys.DECOMPOSITION_MANIFEST] as? String)
        ?.length?.let { it <= MAX_GOVERNED_CONTEXT_CHARS } != true
    ) {
      invalidGoalPlanningSharedContextPacket(
        GoalPlanningSharedContextPacketPayloadKeys.DECOMPOSITION_MANIFEST,
        "shared context decomposition manifest is malformed",
      )
    }
    GoalPlanningSharedContextPacketValidation.requireValidCatalog(
      packet[GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY],
    )
    if (packet[GoalPlanningSharedContextPacketPayloadKeys.VALIDATION_GUIDANCE] !is String) {
      invalidGoalPlanningSharedContextPacket(
        GoalPlanningSharedContextPacketPayloadKeys.VALIDATION_GUIDANCE,
        "shared context validation guidance is invalid",
      )
    }
  }

  private fun validateTopology(
    packet: Map<String, Any?>,
    subtasks: List<DecompositionSubtask>,
  ) {
    val recoveredTopology =
      GoalPlanningSharedContextPacketValidation.normalizedSubtasks(
        packet[GoalPlanningSharedContextPacketPayloadKeys.ORDERED_SUBTASKS],
      ).map { it - GoalPlanningSharedContextPacketPayloadKeys.PLANNING_DISPOSITION }
    val expectedTopology =
      GoalPlanningSharedContextPacketValidation.normalizedSubtasks(orderedSubtasks(subtasks))
        .map { it - GoalPlanningSharedContextPacketPayloadKeys.PLANNING_DISPOSITION }
    if (recoveredTopology != expectedTopology) {
      invalidGoalPlanningSharedContextPacket(
        GoalPlanningSharedContextPacketPayloadKeys.ORDERED_SUBTASKS,
        "shared context ordered subtasks are invalid",
      )
    }
  }

  private fun validateIntegrity(packet: Map<String, Any?>) {
    if (JsonCodec.mapToJsonString(packet).length > MAX_PACKET_CHARS) {
      invalidGoalPlanningSharedContextPacket("<root>", "shared context packet exceeds the size limit")
    }
    if (packet[GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256] !=
      digest(packet - GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256)
    ) {
      invalidGoalPlanningSharedContextPacket(
        GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256,
        "shared context packet integrity is invalid",
      )
    }
  }

  private fun withoutExcludedCatalogEntries(packet: Map<String, Any?>): Map<String, Any?> {
    val catalog =
      (packet[GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY] as? Map<*, *>)
        ?.get(GoalPlanningSharedContextPacketPayloadKeys.CATALOG) as? List<*> ?: return packet
    val retained =
      catalog.filter { entry ->
        val sourcePath = (entry as? Map<*, *>)?.get(GoalPlanningSharedContextPacketPayloadKeys.SOURCE_PATH) as? String
        sourcePath != null && !GoalPlanningExcludedPaths.isExcluded(sourcePath)
      }
    if (retained.size == catalog.size) return packet
    if (packet[GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256] !=
      digest(packet - GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256)
    ) {
      invalidGoalPlanningSharedContextPacket(
        GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256,
        "shared context packet integrity is invalid",
      )
    }
    val migrated = packet.toMutableMap()
    migrated[GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY] =
      linkedMapOf<String, Any?>(
        GoalPlanningSharedContextPacketPayloadKeys.CATALOG to retained,
        GoalPlanningSharedContextPacketPayloadKeys.TRUNCATED to true,
      )
    migrated.remove(GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256)
    return migrated + (
      GoalPlanningSharedContextPacketPayloadKeys.INTEGRITY_SHA256 to digest(migrated)
    )
  }

  fun emptyCatalog(): Map<String, Any?> =
    linkedMapOf(
      GoalPlanningSharedContextPacketPayloadKeys.CATALOG to emptyList<Map<String, Any?>>(),
      GoalPlanningSharedContextPacketPayloadKeys.TRUNCATED to false,
    )

  fun discardedCatalog(): Map<String, Any?> =
    linkedMapOf(
      GoalPlanningSharedContextPacketPayloadKeys.CATALOG to emptyList<Map<String, Any?>>(),
      GoalPlanningSharedContextPacketPayloadKeys.TRUNCATED to true,
    )

  fun catalogHeadingIds(packet: Map<String, Any?>): Set<String> =
    (
      (packet[GoalPlanningSharedContextPacketPayloadKeys.BOUNDARY_MEMORY] as? Map<*, *>)
        ?.get(GoalPlanningSharedContextPacketPayloadKeys.CATALOG) as? List<*>
    )
      .orEmpty()
      .mapNotNull {
          entry ->
        (entry as? Map<*, *>)?.get(GoalPlanningSharedContextPacketPayloadKeys.HEADING_ID) as? String
      }
      .toSet()

  fun catalog(context: GoalPlanningContext): Map<String, Any?> =
    linkedMapOf(
      GoalPlanningSharedContextPacketPayloadKeys.CATALOG to
        context.boundaryCatalog.map { heading ->
          linkedMapOf<String, Any?>(
            GoalPlanningSharedContextPacketPayloadKeys.HEADING_ID to heading.headingId,
            GoalPlanningSharedContextPacketPayloadKeys.SOURCE_PATH to heading.sourcePath,
            GoalPlanningSharedContextPacketPayloadKeys.KIND to heading.kind.wireValue,
            GoalPlanningSharedContextPacketPayloadKeys.HEADING to heading.heading,
          )
        },
      GoalPlanningSharedContextPacketPayloadKeys.TRUNCATED to context.boundaryCatalogTruncated,
    )

  fun orderedSubtasks(subtasks: List<DecompositionSubtask>): List<Map<String, Any?>> =
    subtasks.map { subtask ->
      linkedMapOf(
        DecompositionPlanningPayloadKeys.ID to subtask.id,
        DecompositionPlanningPayloadKeys.NAME to subtask.name,
        DecompositionPlanningPayloadKeys.SPEC_PATH to subtask.specPath,
        GoalPlanningSharedContextPacketPayloadKeys.PLANNING_DISPOSITION to
          if (
            subtask.status.decompositionStatus() == DecompositionStatus.SKIPPED
          ) {
            DecompositionStatus.SKIPPED.wireValue
          } else {
            "included"
          },
        DecompositionPlanningPayloadKeys.DEPENDENCIES to
          subtask.dependencies.map { dependency ->
            linkedMapOf(
              SharedPayloadKeys.SUBTASK_ID to dependency.subtaskId,
              DecompositionPlanningPayloadKeys.OPTIONAL to dependency.optional,
              DecompositionPlanningPayloadKeys.SKIPPED to dependency.skipped,
            )
          },
      )
    }

  fun includedSubtaskIds(packet: Map<String, Any?>): Set<Int> {
    val subtasks =
      GoalPlanningSharedContextPacketValidation.normalizedSubtasks(
        packet[GoalPlanningSharedContextPacketPayloadKeys.ORDERED_SUBTASKS],
      )
    return subtasks.mapNotNull { subtask ->
      (subtask[DecompositionPlanningPayloadKeys.ID] as Int).takeIf {
        subtask[GoalPlanningSharedContextPacketPayloadKeys.PLANNING_DISPOSITION] == "included"
      }
    }.toSet()
  }

  fun digest(packet: Map<String, Any?>): String = GoalPlanningSharedContextPacketValidation.digest(packet)
}

internal fun invalidGoalPlanningSharedContextPacket(
  fieldPath: String,
  reason: String,
  cause: Throwable? = null,
): Nothing =
  throw InvalidGoalPlanningPreparationSchemaError(
    sourceLabel = "_goal_planning_shared_context",
    fieldPath = fieldPath,
    reason = reason,
    cause = cause,
  )

object GoalPlanningSpecCanonicalization {
  private const val FRONTMATTER_FENCE = "---"
  private val STATUS_FRONTMATTER_LINE = Regex("^status\\s*:.*$")

  fun canonical(spec: String): String {
    val lines = spec.lines()
    if (lines.firstOrNull() != FRONTMATTER_FENCE) return spec
    val closingFenceIndex = lines.indexOfFirstFrom(1) { it == FRONTMATTER_FENCE }
    if (closingFenceIndex < 0) return spec
    val frontmatter = lines.subList(1, closingFenceIndex)
    val withoutStatus = frontmatter.filterNot { STATUS_FRONTMATTER_LINE.matches(it) }
    if (withoutStatus.size == frontmatter.size) return spec
    val body = lines.drop(closingFenceIndex + 1)
    return if (withoutStatus.all(String::isBlank)) {
      body.dropWhileAtMostOne(String::isBlank).joinToString("\n")
    } else {
      (listOf(FRONTMATTER_FENCE) + withoutStatus + FRONTMATTER_FENCE + body).joinToString("\n")
    }
  }

  private fun <T> List<T>.indexOfFirstFrom(
    startIndex: Int,
    predicate: (T) -> Boolean,
  ): Int {
    for (index in startIndex until size) {
      if (predicate(this[index])) return index
    }
    return -1
  }

  private fun <T> List<T>.dropWhileAtMostOne(predicate: (T) -> Boolean): List<T> =
    if (firstOrNull()?.let(predicate) == true) drop(1) else this
}
