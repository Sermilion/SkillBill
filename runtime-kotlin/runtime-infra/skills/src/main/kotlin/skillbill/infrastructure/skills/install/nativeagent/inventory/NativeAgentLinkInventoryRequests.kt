package skillbill.infrastructure.skills.install.nativeagent.inventory

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import java.nio.file.Path

internal data class NativeAgentLinkInventoryReconcileRequest(
  val home: Path,
  val provider: String,
  val desired: List<NativeAgentLinkInventoryEntry>,
  val managedRoots: List<Path>,
  val sourceRoot: Path,
  val environment: Map<String, String>,
  val beforeMutation: (Path) -> Unit = {},
  val afterTemporaryCreation: (Path) -> Unit = {},
)

internal data class NativeAgentLinkInventoryLockedReconcileRequest(
  val path: Path,
  val home: Path,
  val provider: String,
  val desired: List<NativeAgentLinkInventoryEntry>,
  val managedRoots: List<Path>,
  val sourceRoot: Path,
  val environment: Map<String, String>,
  val mapper: ObjectMapper,
  val schema: JsonSchema,
  val beforeMutation: (Path) -> Unit,
  val afterTemporaryCreation: (Path) -> Unit,
)

internal data class NativeAgentLinkInventoryWriteRequest(
  val path: Path,
  val entries: List<NativeAgentLinkInventoryEntry>,
  val home: Path,
  val managedRoots: List<Path>,
  val environment: Map<String, String>,
  val mapper: ObjectMapper,
  val schema: JsonSchema,
  val beforeMutation: (Path) -> Unit,
  val afterTemporaryCreation: (Path) -> Unit,
)
