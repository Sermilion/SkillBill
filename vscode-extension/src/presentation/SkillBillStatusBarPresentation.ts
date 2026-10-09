import { FEATURE_GOAL_WORKFLOW_FAMILY, MODEL_TEXT_MAX_LENGTH } from "../domain/Constants";
import { CurrentPhaseExecution, GoalPlanningInfo } from "../domain/SkillBillStatusOutcome";
import { GoalControlsPresentation } from "./GoalControlsPresentation";
import { GoalControlDescriptor } from "./GoalControlDescriptor";
import { StatusUiMapper } from "./StatusUiMapper";
import {
  formatDurationMs,
  isStaleState,
  SkillBillStatusUiState,
  stateCurrentModel,
  stateCurrentPhaseExecution,
  stateDetail,
  stateIssueKey,
  stateGoalElapsedMs,
  stateLastUpdated,
  statePauseReason,
  statePlanning,
  stateProgressCompleted,
  stateProgressTotal,
  stateSubtaskElapsedMs,
  stateProblemSummary,
  stateStepLabel,
  stateWorkflowFamily,
  stateWorkflowId,
} from "./SkillBillStatusUiState";

const BAR_TEXT_MAX_LENGTH = 48;
const UNAVAILABLE_ELAPSED = "—";
const STALE_NOTE = "(Stale — not live)";

export const SkillBillStatusBarPresentation = {
  BAR_TEXT_MAX_LENGTH,
  UNAVAILABLE_ELAPSED,
  STALE_NOTE,

  map(state: SkillBillStatusUiState, now?: Date): MappedPresentation {
    const anchored = now ? StatusUiMapper.withElapsed(state, now) : state;
    const lifecycle = lifecycleLabel(anchored);
    const step = normalizeLabel(stateStepLabel(anchored));
    const goalText = elapsedLabel(stateGoalElapsedMs(anchored));
    const subtaskText = elapsedLabel(stateSubtaskElapsedMs(anchored));
    const selectedSlot = selectDisplaySlot(statePlanning(anchored), stateCurrentPhaseExecution(anchored));
    const progress =
      selectedSlot?.kind === "planning"
        ? [selectedSlot.planned, selectedSlot.total]
        : validProgress(stateProgressCompleted(anchored), stateProgressTotal(anchored));
    const progressText = progress
      ? selectedSlot?.kind === "planning"
        ? `${progress[0]}/${progress[1]}`
        : `${renderedPosition(anchored, progress[0], progress[1])}/${progress[1]}`
      : undefined;
    const currentModel = stateCurrentModel(anchored);
    const modelText = currentModel
      ? (() => {
          const qualifier: string[] = [];
          if (currentModel.phaseId && stateWorkflowFamily(anchored) === FEATURE_GOAL_WORKFLOW_FAMILY) {
            qualifier.push(currentModel.phaseId);
          }
          if (currentModel.effort) {
            qualifier.push(`effort: ${currentModel.effort}`);
          }
          const composed =
            qualifier.length === 0
              ? currentModel.model
              : `${currentModel.model} (${qualifier.join(", ")})`;
          const normalized = normalizeLabel(composed);
          return normalized ? truncateForBar(normalized, MODEL_TEXT_MAX_LENGTH) : undefined;
        })()
      : undefined;
    const slotSegment = selectedSlot?.barSegment;

    const fullBar = (() => {
      const prefix = anchored.executionScope === "standalone_phase" ? "Phase" : "Skill Bill";
      switch (anchored.kind) {
        case "active":
          return buildRunBar(
            prefix,
            step ?? (anchored.kind === "active" ? anchored.stepLabel : undefined),
            slotSegment,
            goalText,
            subtaskText,
            progressText,
          );
        case "paused":
          return buildRunBar(`${prefix} · paused`, step, slotSegment, goalText, subtaskText, progressText);
        case "stale":
          return buildRunBar(`${prefix} · stale`, step, slotSegment, goalText, subtaskText, progressText);
        case "done":
          return buildRunBar(`${prefix} · done`, step, undefined, goalText, subtaskText, progressText);
        case "blocked":
          return `${prefix} · blocked`;
        case "failed":
          return `${prefix} · failed`;
        case "unavailable":
          return "Skill Bill · unavailable";
        case "incompatible":
          return "Skill Bill · incompatible";
        case "idle":
          return "Skill Bill · idle";
        default: {
          const _exhaustive: never = anchored;
          return `Skill Bill · ${_exhaustive}`;
        }
      }
    })();

    const barText = truncateForBar(normalizeLabel(fullBar) ?? fullBar ?? "Skill Bill");
    const slotFullLine = selectedSlot?.fullLine;
    const tooltip = buildTooltip(anchored, lifecycle, step, goalText, subtaskText, progressText, slotFullLine);
    const accessibleName = `Skill Bill status: ${lifecycle}`;
    const accessibleDescription = buildAccessibilityDescription(
      lifecycle,
      anchored.executionScope,
      anchored.phaseId,
      anchored.currentActivity,
      step,
      goalText,
      subtaskText,
      progressText,
      stateDetail(anchored),
      elapsedNoun(anchored),
      slotFullLine,
    );

    return {
      barText,
      tooltipText: tooltip,
      accessibleName,
      accessibleDescription,
      showActivityAnimation: anchored.kind === "active",
      isStaleMarked: isStaleState(anchored),
      details: {
        issueKey: stateIssueKey(anchored),
        workflowId: stateWorkflowId(anchored),
        lifecycleState: lifecycle,
        stepLabel: step ?? stateStepLabel(anchored),
        modelText,
        selectedSlotLabel: selectedSlot?.popupLabel,
        selectedSlotText: selectedSlot?.popupValue,
        progressText,
        goalElapsedText: goalText,
        subtaskElapsedText: subtaskText,
        elapsedNoun: elapsedNoun(anchored),
        lastUpdateText: stateLastUpdated(anchored)?.toISOString(),
        problemSummary: stateProblemSummary(anchored) ?? stateDetail(anchored),
        staleNote: isStaleState(anchored) ? STALE_NOTE : undefined,
        pauseReasonText: pauseReasonText(anchored),
        scopeText: anchored.executionScope === "standalone_phase" ? "Standalone phase" : undefined,
        phaseId: anchored.phaseId,
        currentActivity: anchored.currentActivity,
      },
      controls: GoalControlsPresentation.controlsFor(anchored),
    };
  },

  validProgress(completed: number | undefined, total: number | undefined): [number, number] | undefined {
    if (completed === undefined || total === undefined) {
      return undefined;
    }
    if (total <= 0 || completed < 0 || completed > total) {
      return undefined;
    }
    return [completed, total];
  },

  normalizeLabel(raw: string | undefined): string | undefined {
    if (raw === undefined) {
      return undefined;
    }
    const cleaned = raw
      .replace(/[\u0000-\u001F\u007F]/g, " ")
      .trim()
      .replace(/\s+/g, " ");
    return cleaned.length > 0 ? cleaned : undefined;
  },

  truncateForBar(text: string, maxLength: number = BAR_TEXT_MAX_LENGTH): string {
    if (text.length <= maxLength) {
      return text;
    }
    if (maxLength <= 1) {
      return "…";
    }
    return `${text.slice(0, maxLength - 1)}…`;
  },

  selectDisplaySlot(
    planning: GoalPlanningInfo | undefined,
    execution: CurrentPhaseExecution | undefined,
  ): DisplaySlot | undefined {
    if (planning) {
      const planned = planning.plannedSubtaskCount;
      const total = planning.totalSubtaskCount;
      const stateLabel = planningStateLabel(planning.state);
      return {
        kind: "planning",
        planned,
        total,
        barSegment: `Planning ${planned}/${total}`,
        fullLine: `Planning: ${stateLabel}, ${planned}/${total} plans saved`,
        popupLabel: "Planning",
        popupValue: `${stateLabel}, ${planned}/${total} plans saved`,
      };
    }
    if (execution) {
      const wording = executionWording(execution);
      return {
        kind: "execution",
        barSegment: wording,
        fullLine: wording,
        popupLabel: "Current phase",
        popupValue: wording,
      };
    }
    return undefined;
  },
};

export interface MappedPresentation {
  barText: string;
  tooltipText: string;
  accessibleName: string;
  accessibleDescription: string;
  showActivityAnimation: boolean;
  isStaleMarked: boolean;
  details: StatusBarDetails;
  controls: GoalControlDescriptor[];
}

export interface StatusBarDetails {
  issueKey?: string;
  workflowId?: string;
  lifecycleState: string;
  stepLabel?: string;
  modelText?: string;
  selectedSlotLabel?: string;
  selectedSlotText?: string;
  progressText?: string;
  goalElapsedText: string;
  subtaskElapsedText: string;
  elapsedNoun: string;
  lastUpdateText?: string;
  problemSummary?: string;
  staleNote?: string;
  pauseReasonText?: string;
  scopeText?: string;
  phaseId?: string;
  currentActivity?: string;
}

type DisplaySlot =
  | {
      kind: "planning";
      planned: number;
      total: number;
      barSegment: string;
      fullLine: string;
      popupLabel: string;
      popupValue: string;
    }
  | {
      kind: "execution";
      barSegment: string;
      fullLine: string;
      popupLabel: string;
      popupValue: string;
    };

function elapsedLabel(durationMs: number | undefined): string {
  return durationMs === undefined
    ? SkillBillStatusBarPresentation.UNAVAILABLE_ELAPSED
    : formatDurationMs(durationMs);
}

function renderedPosition(state: SkillBillStatusUiState, completed: number, total: number): number {
  const inFlight = state.kind === "active" || state.kind === "stale" || state.kind === "paused";
  return inFlight && completed < total ? completed + 1 : completed;
}

function buildRunBar(
  prefix: string,
  stepLabel: string | undefined,
  slotSegment: string | undefined,
  goalText: string,
  subtaskText: string,
  progressText: string | undefined,
): string {
  const progressRank = slotSegment === undefined ? 2 : 0;
  const optional: Array<[string, number]> = [
    [subtaskText, 1],
    ...(progressText ? [[progressText, progressRank] as [string, number]] : []),
  ];
  let dropRank = -1;
  while (true) {
    let bar = prefix;
    if (stepLabel) {
      bar += ` · ${stepLabel}`;
    }
    if (slotSegment) {
      bar += ` · ${slotSegment}`;
    }
    if (dropRank < 3) {
      bar += ` · ${goalText}`;
    }
    for (const [text, rank] of optional) {
      if (rank > dropRank) {
        bar += ` · ${text}`;
      }
    }
    if (bar.length <= SkillBillStatusBarPresentation.BAR_TEXT_MAX_LENGTH || dropRank >= 3) {
      return bar;
    }
    dropRank += 1;
  }
}

function pauseReasonText(state: SkillBillStatusUiState): string | undefined {
  const reason = statePauseReason(state);
  if (!reason) {
    return undefined;
  }
  return reason.label ?? reason.code.replace(/_/g, " ");
}

function buildTooltip(
  state: SkillBillStatusUiState,
  lifecycle: string,
  step: string | undefined,
  goalText: string,
  subtaskText: string,
  progressText: string | undefined,
  slotFullLine: string | undefined,
): string {
  const lines = [`Skill Bill — ${lifecycle}`];
  const issueKey = stateIssueKey(state);
  if (issueKey) {
    lines.push(`Issue: ${issueKey}`);
  }
  const workflowId = stateWorkflowId(state);
  if (workflowId) {
    lines.push(`Workflow: ${workflowId}`);
  }
  if (state.executionScope === "standalone_phase") {
    lines.push(`Standalone phase: ${state.phaseId ?? "unknown"}`);
  }
  if (step) {
    lines.push(`Step: ${step}`);
  }
  if (slotFullLine) {
    lines.push(slotFullLine);
  }
  const noun = elapsedNoun(state);
  lines.push(`Goal ${noun}: ${goalText}`);
  lines.push(`Subtask ${noun}: ${subtaskText}`);
  if (progressText) {
    lines.push(`Progress: ${progressText}`);
  }
  if (state.currentActivity) {
    lines.push(`Activity: ${state.currentActivity}`);
  }
  const lastUpdated = stateLastUpdated(state);
  if (lastUpdated) {
    lines.push(`Last update: ${lastUpdated.toISOString()}`);
  }
  const problem = stateProblemSummary(state) ?? stateDetail(state);
  if (problem?.trim()) {
    lines.push(problem);
  }
  if (isStaleState(state)) {
    lines.push(SkillBillStatusBarPresentation.STALE_NOTE);
  }
  const pauseReason = pauseReasonText(state);
  if (pauseReason) {
    lines.push(`Pause reason: ${pauseReason}`);
  }
  if (state.kind === "unavailable") {
    lines.push(`Reason: ${state.reasonCode}`);
  }
  if (state.kind === "incompatible" && state.foundContractVersion) {
    lines.push(`Found contract: ${state.foundContractVersion}`);
  }
  return lines.join("\n");
}

function buildAccessibilityDescription(
  lifecycle: string,
  executionScope: SkillBillStatusUiState["executionScope"],
  phaseId: SkillBillStatusUiState["phaseId"],
  currentActivity: SkillBillStatusUiState["currentActivity"],
  step: string | undefined,
  goalText: string,
  subtaskText: string,
  progressText: string | undefined,
  detail: string | undefined,
  elapsedNoun: string,
  slotFullLine: string | undefined,
): string {
  let text = `Skill Bill. State: ${lifecycle}.`;
  if (step) {
    text += ` Step: ${step}.`;
  }
  if (executionScope === "standalone_phase") {
    text += ` Standalone phase: ${phaseId ?? "unknown"}.`;
  }
  if (slotFullLine) {
    text += ` ${slotFullLine}.`;
  }
  text += ` Goal ${elapsedNoun}: ${goalText}. Subtask ${elapsedNoun}: ${subtaskText}.`;
  if (progressText) {
    text += ` Progress: ${progressText}.`;
  }
  if (detail) {
    text += ` ${detail}`;
  }
  if (currentActivity) {
    text += ` Activity: ${currentActivity}.`;
  }
  return text;
}

function elapsedNoun(state: SkillBillStatusUiState): string {
  switch (state.kind) {
    case "stale":
    case "blocked":
    case "failed":
    case "paused":
    case "done":
      return "ran";
    default:
      return "elapsed";
  }
}

function lifecycleLabel(state: SkillBillStatusUiState): string {
  return state.kind;
}

function planningStateLabel(state: string): string {
  switch (state) {
    case "not_started":
      return "not started";
    case "preplanned":
      return "preplanned";
    case "partially_planned":
      return "partially planned";
    case "blocked":
      return "blocked";
    case "prepared":
      return "prepared";
    default:
      return state.replace(/_/g, " ");
  }
}

function executionWording(execution: CurrentPhaseExecution): string {
  const phase = phaseDisplayName(execution.phaseId);
  switch (execution.kind) {
    case "semantic_loop":
      return `${phase} loop ${execution.count}`;
    case "pass":
      return `${phase} pass ${execution.count}`;
    case "gate_run":
      return `${phase} gate ${execution.count}`;
    case "attempt":
      return `${phase} attempt ${execution.count}`;
    case "bounded_edge":
      return execution.total !== undefined
        ? `${phase} ${execution.count}/${execution.total}`
        : `${phase} ${execution.count}`;
    default:
      return `${phase} ${execution.count}`;
  }
}

function phaseDisplayName(phaseId: string): string {
  const cleaned = SkillBillStatusBarPresentation.normalizeLabel(phaseId) ?? "Phase";
  const parts = cleaned
    .split("_")
    .filter((part) => part.length > 0)
    .map((part: string) => part.charAt(0).toUpperCase() + part.slice(1));
  return parts.length > 0 ? parts.join(" ") : cleaned;
}

function validProgress(completed: number | undefined, total: number | undefined): [number, number] | undefined {
  return SkillBillStatusBarPresentation.validProgress(completed, total);
}

function normalizeLabel(raw: string | undefined): string | undefined {
  return SkillBillStatusBarPresentation.normalizeLabel(raw);
}

function truncateForBar(text: string, maxLength?: number): string {
  return SkillBillStatusBarPresentation.truncateForBar(text, maxLength);
}

function selectDisplaySlot(
  planning: GoalPlanningInfo | undefined,
  execution: CurrentPhaseExecution | undefined,
): DisplaySlot | undefined {
  return SkillBillStatusBarPresentation.selectDisplaySlot(planning, execution);
}
