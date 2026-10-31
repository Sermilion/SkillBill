package skillbill.engine.specbrowser.model

import java.nio.file.Path

public data class SpecBrowserProject(
  val projectId: String,
  val displayName: String,
  val specsRoot: Path?,
  val repoRoot: Path?,
)

public data class SpecBrowserCounts(
  val all: Int,
  val active: Int,
  val pending: Int,
  val done: Int,
)

public data class SpecBrowserSnapshot(
  val project: SpecBrowserProject,
  val roots: List<SpecBrowserNode>,
  val counts: SpecBrowserCounts,
  val anyInProgress: Boolean,
  val failure: String?,
)

public enum class SpecBrowserFileKind {
  PARENT_SPEC,
  SUBTASK_SPEC,
  OTHER,
}

public sealed interface SpecBrowserNode {
  public val name: String
  public val path: String
  public val absolutePath: Path

  public data class Folder(
    override val name: String,
    override val path: String,
    override val absolutePath: Path,
    val children: List<SpecBrowserNode>,
    val bundleCount: Int,
    val statusCounts: Map<SpecBrowserStatus, Int>,
  ) : SpecBrowserNode

  public data class Bundle(
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

  public data class File(
    override val name: String,
    override val path: String,
    override val absolutePath: Path,
    val kind: SpecBrowserFileKind,
    val subtaskId: Int?,
    val subtaskName: String?,
    val status: SpecBrowserStatus?,
  ) : SpecBrowserNode
}
