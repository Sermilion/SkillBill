package skillbill.engine.goalrunner.reset

import skillbill.contracts.JsonCodec
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.contracts.projectstore.ProjectStoreLayout
import skillbill.engine.goalrunner.archive.ParsedGoalArchiveBundle
import skillbill.engine.goalrunner.planning.context.GoalPlanningSharedContextPacket
import skillbill.engine.goalrunner.planning.context.GoalPlanningSharedContextPacketPayloadKeys
import skillbill.ports.goalrunner.model.GoalArchiveOwnedSnapshot
import skillbill.ports.goalrunner.model.GoalArchiveOwnedTables
import skillbill.ports.goalrunner.model.GoalArchiveTableRow
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.text.sha256HexUtf8
import skillbill.workflow.decomposition.encodeDecompositionManifestWireMap
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal class GoalArchiveSpecNormalizer(
  private val validator: DecompositionManifestValidator,
  private val store: DecompositionManifestStore,
  private val identity: String,
  private val issueKey: String,
) {
  fun normalize(bundle: ParsedGoalArchiveBundle): ParsedGoalArchiveBundle? {
    val paths = paths(bundle) ?: return null
    val mapper = ReferenceMapper(paths, identity, issueKey)
    val entries = entries(bundle, mapper) ?: return null
    val snapshot = snapshot(bundle.snapshot, mapper) ?: return null
    return bundle.copy(specEntries = entries, snapshot = snapshot)
  }

  private fun paths(bundle: ParsedGoalArchiveBundle): Map<Path, String>? {
    val result = mutableMapOf<Path, String>()
    for ((relative) in bundle.specEntries) {
      val path =
        try {
          Path.of(relative).normalize()
        } catch (_: InvalidPathException) {
          return null
        }
      if (relative.isBlank() || path.isAbsolute || path.startsWith("..")) continue
      val storePath = Path.of(ProjectStoreLayout.SPECS_PREFIX).resolve(path).normalize()
      val portable = ProjectStoreLayout.SPECS_PREFIX + path.toString().replace('\\', '/')
      result[storePath] = portable
      result[Path.of(LegacyProjectStatePaths.FEATURE_SPECS_PREFIX).resolve(path).normalize()] = portable
    }
    return result
  }

  private fun entries(
    bundle: ParsedGoalArchiveBundle,
    mapper: ReferenceMapper,
  ): List<Pair<String, ByteArray>>? =
    bundle.specEntries.map { (relative, bytes) ->
      if (!relative.endsWith("decomposition-manifest.yaml")) return@map relative to bytes
      val manifest = validator.validateYamlText(bytes.decodeToString(), relative)
      val parent = mapper.remap(manifest.parentSpecPath) ?: return null
      val subtasks = manifest.subtasks.map { it.copy(specPath = mapper.remap(it.specPath) ?: return null) }
      val portable = manifest.copy(parentSpecPath = parent, subtasks = subtasks)
      relative to
        if (portable == manifest) {
          bytes
        } else {
          store.encodeManifestYaml(
            encodeDecompositionManifestWireMap(portable),
          ).encodeToByteArray()
        }
    }

  private fun snapshot(
    snapshot: GoalArchiveOwnedSnapshot,
    mapper: ReferenceMapper,
  ): GoalArchiveOwnedSnapshot? {
    val shared = shared(snapshot, mapper) ?: return null
    val plans = plans(snapshot, mapper) ?: return null
    val legacy = legacy(snapshot, mapper) ?: return null
    val tables = tables(snapshot.ownedTables, mapper)
    return tables?.let {
      snapshot.copy(sharedPreplans = shared, subtaskPlans = plans, legacyPreparations = legacy, ownedTables = it)
    }
  }

  private fun shared(
    snapshot: GoalArchiveOwnedSnapshot,
    mapper: ReferenceMapper,
  ): List<SharedGoalPreplanCheckpoint>? {
    return snapshot.sharedPreplans.map {
      val payload = mapper.payload(it.preplanPayload) ?: return null
      it.copy(preplanPayload = payload, payloadSha256 = sha256HexUtf8(payload))
    }
  }

  private fun plans(
    snapshot: GoalArchiveOwnedSnapshot,
    mapper: ReferenceMapper,
  ): List<GoalSubtaskPlanCheckpoint>? {
    return snapshot.subtaskPlans.map {
      val governed = mapper.remap(it.governedSubSpecPath) ?: return null
      val payload = mapper.payload(it.planPayload) ?: return null
      it.copy(governedSubSpecPath = governed, planPayload = payload, payloadSha256 = sha256HexUtf8(payload))
    }
  }

  private fun legacy(
    snapshot: GoalArchiveOwnedSnapshot,
    mapper: ReferenceMapper,
  ): List<GoalPlanningPreparationRecord>? {
    return snapshot.legacyPreparations.map {
      val governed = mapper.remap(it.governedSubSpecPath) ?: return null
      val preplan = mapper.payload(it.preplanPayload) ?: return null
      val plan = mapper.payload(it.planPayload) ?: return null
      it.copy(governedSubSpecPath = governed, preplanPayload = preplan, planPayload = plan)
    }
  }

  private fun tables(
    tables: GoalArchiveOwnedTables,
    mapper: ReferenceMapper,
  ): GoalArchiveOwnedTables? {
    val original = tables.rows(GoalArchiveSpecReferenceKeys.EXECUTION_IDENTITIES_TABLE) ?: return tables
    val rows =
      original.map { row ->
        val values = row.orderedEntries().toMap().toMutableMap()
        val governed = values[GoalArchiveSpecReferenceKeys.GOVERNED_SPEC_PATH]?.toString().orEmpty()
        if (governed.isNotBlank()) {
          values[GoalArchiveSpecReferenceKeys.GOVERNED_SPEC_PATH] = mapper.remap(governed) ?: return null
        }
        GoalArchiveTableRow(values)
      }
    return GoalArchiveOwnedTables.of(
      tables.orderedTables().toMap() + (GoalArchiveSpecReferenceKeys.EXECUTION_IDENTITIES_TABLE to rows),
    )
  }
}

private class ReferenceMapper(
  private val paths: Map<Path, String>,
  private val identity: String,
  private val issueKey: String,
) {
  private var changed = false
  private var unmappable = false

  fun remap(reference: String): String? {
    val path =
      try {
        Path.of(reference)
      } catch (_: InvalidPathException) {
        return null
      }
    if (!path.isAbsolute) {
      return paths[path.normalize()]
        ?: if (reference.startsWith(LegacyProjectStatePaths.FEATURE_SPECS_PREFIX)) null else reference
    }
    return paths.filterKeys { path.toAbsolutePath().normalize().endsWith(it) }.values.distinct().singleOrNull()
  }

  fun payload(payload: String): String? {
    val parsed = JsonCodec.parseObjectOrNull(payload) ?: return payload
    changed = false
    unmappable = false
    val rewritten = JsonCodec.anyToStringAnyMap(rewrite(JsonCodec.jsonElementToValue(parsed))) ?: return null
    if (unmappable || !changed) return if (unmappable) null else payload
    val integrityKey = GoalArchiveSpecReferenceKeys.INTEGRITY_SHA256
    val result =
      if (integrityKey in rewritten) {
        val withoutDigest = rewritten - integrityKey
        withoutDigest + (integrityKey to GoalPlanningSharedContextPacket.digest(withoutDigest))
      } else {
        rewritten
      }
    return JsonCodec.mapToJsonString(result)
  }

  private fun rewrite(
    value: Any?,
    key: String? = null,
  ): Any? =
    when (value) {
      is String -> rewriteString(value, key)
      is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to rewrite(v, k.toString()) }
      is List<*> -> value.map { rewrite(it, key) }
      else -> value
    }

  private fun rewriteString(
    value: String,
    key: String?,
  ): String? {
    val replacement =
      when (key) {
        GoalPlanningSharedContextPacketPayloadKeys.REPOSITORY_IDENTITY -> identity
        GoalPlanningSharedContextPacketPayloadKeys.NORMALIZED_ISSUE_KEY -> issueKey
        DecompositionPlanningPayloadKeys.PARENT_SPEC_PATH, DecompositionPlanningPayloadKeys.SPEC_PATH,
        GoalArchiveSpecReferenceKeys.GOVERNED_SUB_SPEC_PATH, GoalArchiveSpecReferenceKeys.GOVERNED_SPEC_PATH,
        GoalArchiveSpecReferenceKeys.SUBTASK_SPEC_PATHS,
        -> remap(value)
        else -> value
      }
    if (replacement == null) unmappable = true
    if (replacement != value) changed = true
    return replacement
  }
}
