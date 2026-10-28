package dev.skillbill.desktop.core.domain.model

/** One project's spec folder as the runtime resolved it; [specsRoot] is empty when the project has no checkout. */
data class SpecProject(val projectId: String, val displayName: String, val specsRoot: String, val repoRoot: String?)

/** Bundle counts per group, as the runtime counted them. */
data class SpecCounts(val all: Int, val active: Int, val pending: Int, val done: Int)

enum class SpecStatus { Complete, InProgress, Running, Pausing, Paused, Pending, Blocked, Canceled }

enum class SpecGroup { Active, Pending, Done }

enum class SpecFileKind { ParentSpec, SubtaskSpec, Other }

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

/** A project's tree, in the runtime's order, with its counts. */
data class SpecTree(val project: SpecProject, val roots: List<SpecNode>, val counts: SpecCounts, val anyInProgress: Boolean)

enum class SpecRunState { NotStarted, Running, PauseRequested, Paused, Blocked, Canceled, Complete, Unavailable }

/** What the runtime allows for one spec bundle's run; the app only renders these verdicts. */
data class SpecBundleControlView(
  val issueKey: String?,
  val runState: SpecRunState,
  val everStarted: Boolean,
  val workflowId: String?,
  val repositoryIdentity: String?,
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

/** The bundle a spec control reads, by its project and its path relative to the specs root. */
data class SpecBundleRef(val projectId: String, val path: String, val repoRoot: String?)

sealed interface SpecTreeState {
  data object Loading : SpecTreeState

  data class Loaded(val tree: SpecTree, val storePath: String) : SpecTreeState

  data object NoSpecs : SpecTreeState

  data object NoCheckout : SpecTreeState

  data class Unavailable(val problem: ReadProblem) : SpecTreeState
}

sealed interface SpecControlState {
  data object Loading : SpecControlState

  /**
   * The live goal, if any, is [SpecBundleControlView.workflowId]; its row and verdicts come from the board observation, since
   * the detail read carries no running or membership verdicts.
   */
  data class Loaded(val control: SpecBundleControlView) : SpecControlState

  data class Unavailable(val problem: ReadProblem) : SpecControlState
}

enum class OpenOutcome { Opened, NotOpened }
