import * as vscode from "vscode";
import { GoalControlKind } from "../presentation/GoalControlDescriptor";
import { MappedPresentation } from "../presentation/SkillBillStatusBarPresentation";

export interface StatusDetailsCallbacks {
  onRefresh: () => void;
  onStop: (issueKey: string) => Promise<string | undefined>;
  onPause: (issueKey: string) => Promise<string | undefined>;
}

export function showStatusDetails(presentation: MappedPresentation, callbacks: StatusDetailsCallbacks): void {
  const details = presentation.details;
  const lines: string[] = [`Lifecycle: ${details.lifecycleState}`];
  if (details.issueKey) {
    lines.push(`Issue: ${details.issueKey}`);
  }
  if (details.workflowId) {
    lines.push(`Workflow: ${details.workflowId}`);
  }
  if (details.scopeText) {
    lines.push(`Scope: ${details.scopeText}`);
  }
  if (details.phaseId) {
    lines.push(`Phase: ${details.phaseId}`);
  }
  if (details.stepLabel) {
    lines.push(`Step: ${details.stepLabel}`);
  }
  if (details.selectedSlotLabel && details.selectedSlotText) {
    lines.push(`${details.selectedSlotLabel}: ${details.selectedSlotText}`);
  }
  if (details.modelText) {
    lines.push(`Model: ${details.modelText}`);
  }
  lines.push(`Goal ${details.elapsedNoun}: ${details.goalElapsedText}`);
  lines.push(`Subtask ${details.elapsedNoun}: ${details.subtaskElapsedText}`);
  if (details.progressText) {
    lines.push(`Progress: ${details.progressText}`);
  }
  if (details.currentActivity) {
    lines.push(`Current activity: ${details.currentActivity}`);
  }
  if (details.lastUpdateText) {
    lines.push(`Last update: ${details.lastUpdateText}`);
  }
  if (details.problemSummary) {
    lines.push(details.problemSummary);
  }
  if (details.staleNote) {
    lines.push(details.staleNote);
  }
  if (details.pauseReasonText) {
    lines.push(`Pause reason: ${details.pauseReasonText}`);
  }
  for (const control of presentation.controls) {
    if (!control.enabled) {
      lines.push(control.text);
    }
  }

  const actions: string[] = ["Refresh"];
  const controlByLabel = new Map<string, (typeof presentation.controls)[number]>();
  for (const control of presentation.controls) {
    if (control.enabled) {
      actions.push(control.text);
      controlByLabel.set(control.text, control);
    }
  }

  void vscode.window.showInformationMessage(lines.join("\n"), { modal: false }, ...actions).then((choice) => {
    if (!choice) {
      return;
    }
    if (choice === "Refresh") {
      callbacks.onRefresh();
      return;
    }
    const control = controlByLabel.get(choice);
    if (!control) {
      return;
    }
    void (async () => {
      const failure =
        control.kind === GoalControlKind.STOP
          ? await callbacks.onStop(control.issueKey)
          : await callbacks.onPause(control.issueKey);
      if (failure) {
        void vscode.window.showWarningMessage(failure);
      } else {
        callbacks.onRefresh();
      }
    })();
  });
}
