package dev.skillbill.desktop.app.onboarding

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.designsystem.usage.UsageUiMeter
import dev.skillbill.desktop.core.designsystem.usage.UsageUiState
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatSuggestedAction
import dev.skillbill.desktop.core.domain.model.NewTaskActionKind

internal enum class TourNav { NewTask, Work, History, Specs, Settings }

/** The tour targets primitives can point at; later steps append their own entries. */
internal enum class TourTarget {
  NavNewTask, NavWork, NavHistory, NavSpecs, NavSettings,
  Usage, StartCard, StartGoalButton,
  LaneNeeds, LaneGoals, LanePhases, LaneOps, LaneDone, NewRow,
  Subtasks, Pipeline, ActivityLog,
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

internal data class DemoCursor(val target: TourTarget, val presses: Int = 0)

internal data class CardFlight(val from: TourTarget, val to: TourTarget, val id: Int)

internal data class Keycap(val key: String, val label: String, val presses: Int = 0)

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

internal sealed interface TourChatLine {
  data class User(val text: String) : TourChatLine
  data class Agent(val text: String) : TourChatLine
  data object AgentThinking : TourChatLine
}

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
  val newGoalRowSettled = TourBoardRow(
    goalKey, goalTitle,
    status = StatusKind.Active, iconProgress = 0.15f,
    agent = claude, branch = goalBranch, label = "Subtask 1/3", progress = 0.12f, time = "1m",
  )

  val subtaskTitles = listOf(
    "CSV serializer for invoice rows",
    "Export endpoint with active filters",
    "Export button and tests",
  )
  val phaseNames = listOf("preplan", "plan", "implement", "review", "validate", "commit")

  const val newTaskHeading = "What should we build?"
  const val newTaskBody = "Describe the work in your own words. The agent prepares it with you before anything runs."
  const val composerPlaceholder = "Describe a change, paste an issue, or ask a question"
  val homeCounts = listOf("Needs you · 0", "Running · 3", "Finished today · 2")

  const val chatMessage1 = "Add CSV export to the invoices table. It should respect the active filters."
  const val chatReply1 = "I read `InvoicesTable.tsx` and `api/invoices`. One question first: should archived invoices be included?"
  const val chatMessage2 = "Only what the table shows."
  const val chatReply2 = "Clear. I'd split this into three subtasks: a CSV serializer, an export endpoint that applies the active filters, and the button with tests."

  // The card shows `instruction` as its tooltip, so it stays a plain sentence.
  val startAction = ChatSuggestedAction(
    label = "Start goal",
    instruction = "Start a goal that adds CSV export to the invoices table, split into three subtasks.",
    mode = ChatPermissionMode.Act,
    start = NewTaskActionKind.Goal,
    title = goalTitle,
  )

  const val logPreplan = "preplan · read 14 files, 2 specs"
  const val logPlan = "plan · 4 steps written"
  const val logEditCsv = "edit src/export/csv.ts"
  const val logEditCsvTest = "edit src/export/csv.test.ts"
  const val logReview = "review · 0 blocking findings"
  const val logTest = "npm test · 48 passed"
  const val logCommit = "commit 3f9a2c1"
  const val logSubtaskDone = "subtask 1 done · starting subtask 2"

  val tipNavNewTask = TourTip("New task", "Chat with your agent to shape the next piece of work.", listOf("G N"))
  val tipNavWork = TourTip("Work", "Every running goal, phase and operation, grouped by what it needs.", listOf("G W"))
  val tipNavHistory = TourTip("History", "Finished runs, with their full record.", listOf("G H"))
  val tipNavSpecs = TourTip("Specs", "The plans Sona writes, stored as files in your repo.", listOf("G S"))
  val tipNavSettings = TourTip("Settings", "Notifications, models, agents and paths.", listOf("G ,"))
  val tipUsage = TourTip("Usage", "How much of your agent's limits you have left, always in view.")
  val tipYourCall = TourTip("Your call", "Starting the goal is always a click. The agent never launches work on its own.")
  val tipStarted = TourTip("Started", "The goal is on your Work board now.")
  val tipLaneNeeds = TourTip("Needs you", "Runs waiting on your decision. Empty is good.")
  val tipLaneGoals = TourTip("Goal runs", "Features Sona plans, builds and ships end to end.")
  val tipLanePhases = TourTip("Standalone phases", "One review, QA or validation pass on a branch.")
  val tipLaneOps = TourTip("Operations", "Verify, release and other one-off jobs.")
  val tipLaneDone = TourTip("Finished today", "Older runs move to History.")
  val tipNewGoal = TourTip("Your new goal", "Next, we open it.")
  val tipSubtasks = TourTip("Subtasks", "The plan, split into pieces small enough to review.")
  val tipPipeline = TourTip("The same steps every time", "Plan, implement, review, validate, commit. Failed checks loop back for a fix before anything is committed.")
  val tipActivity = TourTip("Live activity", "What the agent is doing right now.")
}
