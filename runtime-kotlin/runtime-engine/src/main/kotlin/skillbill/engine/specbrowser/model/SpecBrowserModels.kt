package skillbill.engine.specbrowser.model

import java.nio.file.Path

data class SpecBrowserProject(
  val projectId: String,
  val displayName: String,
  val specsRoot: Path?,
  val repoRoot: Path?,
)

data class SpecBrowserCounts(
  val all: Int,
  val active: Int,
  val pending: Int,
  val done: Int,
)

data class SpecBrowserSnapshot(
  val project: SpecBrowserProject,
  val roots: List<SpecBrowserNode>,
  val counts: SpecBrowserCounts,
  val anyInProgress: Boolean,
  val failure: String?,
)

enum class SpecBrowserFileKind {
  PARENT_SPEC,
  SUBTASK_SPEC,
  OTHER,
}

sealed interface SpecBrowserNode {
  val name: String
  val path: String
  val absolutePath: Path

  data class Folder(
    override val name: String,
    override val path: String,
    override val absolutePath: Path,
    val children: List<SpecBrowserNode>,
    val bundleCount: Int,
    val statusCounts: Map<SpecBrowserStatus, Int>,
  ) : SpecBrowserNode

  data class Bundle(
    override val name: String,
    override val path: String,
    override val absolutePath: Path,
    val issueKey: String?,
    val title: String,
    val status: SpecBrowserStatus,
    val group: SpecBrowserStatusGroup,
    val subtasksDone: Int?,
    val subtasksTotal: Int?,
    val children: List<SpecBrowserNode>,
  ) : SpecBrowserNode

  data class File(
    override val name: String,
    override val path: String,
    override val absolutePath: Path,
    val kind: SpecBrowserFileKind,
    val subtaskId: Int?,
    val subtaskName: String?,
    val status: SpecBrowserStatus?,
  ) : SpecBrowserNode
}
