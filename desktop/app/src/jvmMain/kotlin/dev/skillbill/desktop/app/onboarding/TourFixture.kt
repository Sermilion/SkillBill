package dev.skillbill.desktop.app.onboarding

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.designsystem.usage.UsageUiMeter
import dev.skillbill.desktop.core.designsystem.usage.UsageUiState

internal enum class TourNav { NewTask, Work, History, Specs, Settings }

/** The tour targets primitives can point at; later steps append their own entries. */
internal enum class TourTarget {
  NavGroup, NavNewTask, NavWork, NavHistory, NavSpecs, NavSettings,
  Usage,
  BoardLanes, LaneNeeds, LaneGoals, LanePhases, LaneOps, LaneDone, NewRow,
  Subtasks, Pipeline, ActivityLog,
  FinishSteps, PrCard, SparkLayer,
}

internal enum class TipSide { Right, Left, Top, Bottom, BottomLeft }

internal data class TourTip(val title: String, val body: String, val keys: List<String> = emptyList())

internal data class Spotlight(
  val target: TourTarget,
  val tip: TourTip? = null,
  val side: TipSide = TipSide.Right,
  val pad: Dp = 6.dp,
  val radius: Dp = 9.dp,
)

internal data class DemoCursor(val target: TourTarget, val presses: Int = 0) {
  fun pressed() = copy(presses = presses + 1)
}

internal data class CardFlight(val from: TourTarget, val to: TourTarget, val id: Int)

internal data class Keycap(val key: String, val label: String, val presses: Int = 0) {
  fun pressed() = copy(presses = presses + 1)
}

internal data class StageOverlay(
  val spotlight: Spotlight? = null,
  val cursor: DemoCursor? = null,
  val flight: CardFlight? = null,
  val keycap: Keycap? = null,
)

internal data class StageChrome(val activeNav: TourNav, val workAttention: Int? = null)

internal data class TourAgent(val name: String, val dataSlot: Int)

internal data class TourNavEntry(
  val nav: TourNav,
  val target: TourTarget,
  val label: String,
  val icon: ImageVector,
)

internal data class TourBoardRow(
  val key: String,
  val title: String,
  val phaseTag: String? = null,
  val status: StatusKind,
  val iconProgress: Float? = null,
  val agent: TourAgent,
  val branch: String,
  val label: String,
  val progress: Float,
  val time: String,
  val fresh: Boolean = false,
)

internal data class TourSubtask(
  val title: String,
  val status: StatusKind,
  val progress: Float? = null,
  val current: Boolean = false,
)

internal data class TourLogLine(val time: String, val text: String)

internal data class TourChanges(val files: Int, val adds: Int, val deletes: Int)

internal data class TourFinishStep(val phase: String, val outcome: String)

/** Every string and value the staged tour shares, copied from `design/onboarding/index.html`. */
internal object TourFixture {
  const val goalKey = "ACME-121"
  const val goalTitle = "Add CSV export to invoices"
  const val goalBranch = "feat/invoice-csv"
  const val workspace = "acme"
  val repositories = listOf("acme-web", "acme-api")

  val claude = TourAgent("Claude", 4)
  val codex = TourAgent("Codex", 2)
  val cursor = TourAgent("Cursor", 5)

  val usage = UsageUiState(
    provider = "Claude",
    agentId = "claude",
    meters = listOf(
      UsageUiMeter("five-hour", "5-hour", "23%", 0.23f, resetIn = "2h 14m"),
      UsageUiMeter("weekly", "Weekly", "41%", 0.41f, resetIn = "4d 6h"),
    ),
  )

  val navItems = listOf(
    TourNavEntry(TourNav.NewTask, TourTarget.NavNewTask, "New task", LucideIcons.pencil),
    TourNavEntry(TourNav.Work, TourTarget.NavWork, "Work", LucideIcons.target),
    TourNavEntry(TourNav.History, TourTarget.NavHistory, "History", LucideIcons.history),
    TourNavEntry(TourNav.Specs, TourTarget.NavSpecs, "Specs", LucideIcons.fileText),
    TourNavEntry(TourNav.Settings, TourTarget.NavSettings, "Settings", LucideIcons.sliders),
  )

  val boardGoalRow = TourBoardRow(
    "ACME-117", "Move auth middleware to the edge runtime",
    status = StatusKind.Active, iconProgress = 0.57f,
    agent = codex, branch = "feat/edge-auth", label = "Subtask 4/7", progress = 0.57f, time = "38m",
  )
  val boardPhaseRow = TourBoardRow(
    "ACME-115", "Search facets", phaseTag = "review",
    status = StatusKind.Active, iconProgress = 0.70f,
    agent = claude, branch = "feat/search-facets", label = "Pass 2", progress = 0.70f, time = "6m",
  )
  val boardOpsRow = TourBoardRow(
    "ACME-109", "Saved filters acceptance criteria", phaseTag = "verify",
    status = StatusKind.Active, iconProgress = 0.30f,
    agent = cursor, branch = "main", label = "Auditing", progress = 0.30f, time = "3m",
  )
  val boardDoneRows = listOf(
    TourBoardRow(
      "ACME-112", "Fix timezone drift in invoice dates",
      status = StatusKind.Completed,
      agent = codex, branch = "fix/invoice-tz", label = "PR #479", progress = 1f, time = "2h",
    ),
    TourBoardRow(
      "ACME-110", "Upgrade to React 19",
      status = StatusKind.Completed,
      agent = claude, branch = "chore/react-19", label = "PR #476", progress = 1f, time = "5h",
    ),
  )

  // The staged ACME-121 insert; the mock keeps its 35% status arc after it turns active.
  val newGoalRow = TourBoardRow(
    goalKey, goalTitle,
    status = StatusKind.Planning, iconProgress = 0.35f,
    agent = claude, branch = goalBranch, label = "Planning", progress = 0f, time = "0s", fresh = true,
  )
  val newGoalRowActive = newGoalRow.copy(status = StatusKind.Active, label = "Subtask 1/3", progress = 0.08f, time = "12s")

  val subtaskTitles = listOf(
    "CSV serializer for invoice rows",
    "Export endpoint with active filters",
    "Export button and tests",
  )
  val phaseNames = listOf("preplan", "plan", "implement", "review", "validate", "commit")

  const val logCommit = "commit 3f9a2c1"

  // The steer sequence seeds subtask 2 mid-flight; appended lines take stamps after 14:10 through `logTotal` = 8.
  val controlSeedLog = listOf(
    TourLogLine("14:07", logCommit),
    TourLogLine("14:08", "preplan · read 9 files"),
    TourLogLine("14:09", "plan · 3 steps written"),
    TourLogLine("14:10", "edit src/api/invoices/export.ts"),
  )
  const val controlSeedLogTotal = 8
  const val logPauseRequested = "pause requested · finishing subtask 2"
  const val logCommitSubtaskTwo = "commit 8d41e07"
  const val logPaused = "paused after subtask 2"
  const val logResumed = "resumed · subtask 3"
  val controlChanges = TourChanges(3, 118, 3)
  const val pauseBannerTitle = "Paused after subtask 2"
  const val pauseBannerBody = "Two of three subtasks are committed. Resume when you're ready, or switch agents first."

  val finishSteps = listOf(
    TourFinishStep("goal_assessment", "All 3 subtasks match the spec"),
    TourFinishStep("qa", "3 of 3 journeys passed"),
    TourFinishStep("validate", "Lint, typecheck and 51 tests"),
    TourFinishStep("pr", "Opened #482"),
    TourFinishStep("monitor", "CI green, 6 of 6 checks"),
  )
  val checkNames = listOf("build", "lint", "typecheck", "unit", "e2e", "preview")
  const val prNumber = "#482"
  const val prStatusRunning = "Checks running"
  const val prStatusReady = "Ready to merge"
  const val prRange = "feat/invoice-csv → main"

  val tipBoardLanes = TourTip(
    "Grouped by what it needs",
    "Needs you sits on top — paused runs and decisions wait there. Goal runs, phases and operations follow; finished runs sink to the bottom.",
  )
  val tipRunKinds = TourTip(
    "One board, three kinds",
    "A goal runs the whole pipeline end to end. A standalone phase runs a single stage of it. An operation does one focused job.",
  )
  val tipNewGoal = TourTip("Your new goal", "Next, we open it.")
  val tipPipeline = TourTip("The same steps every time", "Plan, implement, review, validate, commit. Failed checks loop back for a fix before anything is committed.")
  val tipPausedNeedsYou = TourTip("Needs you", "Paused runs and runs waiting on a decision show up here, and in a notification.")
  val tipGoalFinish = TourTip("Goal finish", "One last pass over the whole goal, not just the last subtask.")
  val tipReadyForYou = TourTip("Ready for you", "Merge it yourself. Sona never merges on its own.")
}
