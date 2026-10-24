package dev.skillbill.tray.core.domain.model

/** One project's spec folder as the runtime resolved it; [specsRoot] is empty when the project has no checkout. */
data class SpecProject(
  val projectId: String,
  val displayName: String,
  val specsRoot: String,
  val repoRoot: String?,
)

data class SpecSnapshot(
  val project: SpecProject,
  val roots: List<SpecNode>,
  val counts: SpecCounts,
  val anyInProgress: Boolean,
  val failure: String?,
)

/** Bundle counts per group, as the runtime counted them. */
data class SpecCounts(val all: Int, val active: Int, val pending: Int, val done: Int)

enum class SpecStatus { COMPLETE, IN_PROGRESS, RUNNING, PAUSING, PAUSED, PENDING, BLOCKED, CANCELED }

enum class SpecGroup { ACTIVE, PENDING, DONE }

enum class SpecFileKind { PARENT_SPEC, SUBTASK_SPEC, OTHER }

/** A tree node; [path] is the `/`-separated path relative to the specs root and is the stable key. */
sealed interface SpecNode {
  val name: String
  val path: String
  val absolutePath: String

  data class Folder(
    override val name: String,
    override val path: String,
    override val absolutePath: String,
    val children: List<SpecNode>,
    val bundleCount: Int,
    val statusCounts: Map<SpecStatus, Int>,
  ) : SpecNode

  data class Bundle(
    override val name: String,
    override val path: String,
    override val absolutePath: String,
    val issueKey: String?,
    val title: String,
    val status: SpecStatus,
    val group: SpecGroup,
    val subtasksDone: Int?,
    val subtasksTotal: Int?,
    val children: List<SpecNode>,
  ) : SpecNode

  data class File(
    override val name: String,
    override val path: String,
    override val absolutePath: String,
    val kind: SpecFileKind,
    val subtaskId: Int?,
    val subtaskName: String?,
    val status: SpecStatus?,
  ) : SpecNode
}

enum class SpecRunState { NOT_STARTED, RUNNING, PAUSE_REQUESTED, PAUSED, BLOCKED, CANCELED, COMPLETE, UNAVAILABLE }

/** What the runtime allows for one spec folder's run; the desktop only renders these verdicts. */
data class SpecBundleControl(
  val issueKey: String?,
  val runState: SpecRunState,
  val everStarted: Boolean,
  val work: WorkIdentity?,
  val canStart: Boolean,
  val startLabel: String,
  val canPause: Boolean,
  val pauseRequested: Boolean,
  val canCancel: Boolean,
  val purgeVisible: Boolean,
  val canPurge: Boolean,
  val agentId: String?,
  val agentChoices: List<String>,
  val unavailableReason: String?,
)
