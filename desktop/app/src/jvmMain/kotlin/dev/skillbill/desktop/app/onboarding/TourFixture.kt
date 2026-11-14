package dev.skillbill.desktop.app.onboarding

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.component.ToastData
import dev.skillbill.desktop.core.designsystem.component.ToastLead
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.overlay.CommandGroup
import dev.skillbill.desktop.core.designsystem.overlay.CommandItem
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
  DiffLine, DiffPlus, AddReview, FinishSteps, PrCard, SparkLayer,
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

internal sealed interface TourChatLine {
  data class User(val text: String) : TourChatLine
  data class Agent(val text: String) : TourChatLine
  data object AgentThinking : TourChatLine
}

internal data class TourDiffFile(val path: String, val adds: Int, val deletes: Int)

internal data class TourDiffLine(val sign: Char, val number: Int?, val text: String, val target: Boolean = false)

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

  // Step 05 seeds subtask 2 mid-flight; appended lines take stamps after 14:10 through `logTotal` = 8.
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

  val reviewFiles = listOf(
    TourDiffFile("src/export/csv.ts", 64, 0),
    TourDiffFile("src/export/csv.test.ts", 41, 0),
    TourDiffFile("src/api/invoices/export.ts", 28, 3),
    TourDiffFile("src/ui/InvoicesTable.tsx", 19, 5),
    TourDiffFile("src/ui/ExportButton.tsx", 4, 0),
  )
  val reviewDiff = listOf(
    TourDiffLine(' ', 1, "import { Invoice } from \"../model\";"),
    TourDiffLine('+', 2, "import { formatMoney, formatDate } from \"../format\";"),
    TourDiffLine(' ', 3, ""),
    TourDiffLine('+', 4, "export function toCsv(rows: Invoice[]): string {"),
    TourDiffLine('+', 5, "  const header = [\"Number\", \"Customer\", \"Date\", \"Total\"];"),
    TourDiffLine('+', 6, "  const lines = rows.map((r) => ["),
    TourDiffLine('+', 7, "    r.number,"),
    TourDiffLine('+', 8, "    r.customer.replace(/,/g, \" \"),", target = true),
    TourDiffLine('+', 9, "    formatDate(r.issuedAt),"),
    TourDiffLine('+', 10, "    formatMoney(r.total),"),
    TourDiffLine('+', 11, "  ].join(\",\"));"),
    TourDiffLine('+', 12, "  return [header.join(\",\"), ...lines].join(\"\\n\");"),
    TourDiffLine('+', 13, "}"),
    TourDiffLine(' ', 14, ""),
    TourDiffLine('-', null, "export const CSV_MIME = \"text/plain\";"),
    TourDiffLine('+', 15, "export const CSV_MIME = \"text/csv\";"),
  )
  const val csvFixedLine = "    escapeCsvCell(r.customer),"
  const val reviewComment = "Commas get dropped here. Use the shared escapeCsvCell helper so they are quoted instead."
  const val reviewCommentThread =
    "Commas get dropped here. Use the shared `escapeCsvCell` helper so they are quoted instead."
  const val reviewDraftFooter = "Line 8 · pending in this review"
  const val reviewReply =
    "Fixed in `9b1e04d`. The serializer now calls `escapeCsvCell`, so commas are quoted, not dropped."
  val reviewToast =
    ToastData("Review added · 1 comment sent to Claude", lead = ToastLead.Status(StatusKind.Completed))

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

  // The staged palette mirrors the real catalog's sections; Show tour sits last in Navigation, as the shell places it.
  val commandGroups = listOf(
    CommandGroup("Navigation", listOf(
      CommandItem("nav:new-task", "Go to New task", LucideIcons.pencil, "G N"),
      CommandItem("nav:work", "Go to Work", LucideIcons.target, "G W"),
      CommandItem("nav:history", "Go to History", LucideIcons.history, "G H"),
      CommandItem("tour:show", "Show tour", LucideIcons.play),
    )),
    CommandGroup("Panes", listOf(
      CommandItem("pane:specs", "Open specs pane", LucideIcons.fileText, "G S"),
      CommandItem("pane:term", "Toggle terminal", LucideIcons.terminal, "⌃ `"),
    )),
    CommandGroup(goalKey, listOf(
      CommandItem("goal:pause", "Pause after subtask", LucideIcons.pause, "P"),
      CommandItem("goal:pause-now", "Pause now", LucideIcons.stop, "⇧ P"),
      CommandItem("goal:resume", "Resume", LucideIcons.play, "R"),
    )),
  )
  const val palettePlaceholder = "Type a command or search…"
  const val paletteQuery = "pause"

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
  val tipPausedNeedsYou = TourTip("Needs you", "Paused runs and runs waiting on a decision show up here, and in a notification.")
  val tipCommentLine = TourTip("Comment on a line", "Hover any line and click +.")
  val tipSendReview = TourTip("Send it as one review", "Comments stay pending while you read other files, then go out together.")
  val tipGoalFinish = TourTip("Goal finish", "One last pass over the whole goal, not just the last subtask.")
  val tipReadyForYou = TourTip("Ready for you", "Merge it yourself. Sona never merges on its own.")
}
