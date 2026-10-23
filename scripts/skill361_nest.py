#!/usr/bin/env python3
"""SKILL-361 subtask 3: nest flat packages under sibling-count ceiling."""
from __future__ import annotations

import os
import re
import subprocess
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

REPO = Path("/home/sermilion/StudioProjects/skill-bill")
SOURCE_KINDS = ("main", "test", "testFixtures", "repoTest")
SCAN_ROOTS = [REPO / "runtime-kotlin"]
PKG_RE = re.compile(r"^package\s+([\w.]+)\s*", re.M)
IMPORT_RE = re.compile(r"^import\s+([\w.]+(?:\.\*)?)\s*$", re.M)

REMAINDER_PARENTS = [
    "skillbill.application.review",
    "skillbill.application.telemetry",
    "skillbill.application.workflow",
    "skillbill.cli.goal",
    "skillbill.cli.install",
    "skillbill.cli.kernel",
    "skillbill.cli.scaffold",
    "skillbill.contracts.workflow",
    "skillbill.di",
    "skillbill.error",
    "skillbill.infrastructure.contracts.workflow",
    "skillbill.infrastructure.launcher.process",
    "skillbill.infrastructure.skills",
    "skillbill.infrastructure.skills.install.nativeagent",
    "skillbill.infrastructure.skills.install.staging",
    "skillbill.infrastructure.skills.scaffold.platformpack",
    "skillbill.infrastructure.skills.scaffold.runtime",
    "skillbill.infrastructure.skills.scaffold.validation",
    "skillbill.infrastructure.sqlite.core",
    "skillbill.infrastructure.sqlite.goalrunner",
    "skillbill.infrastructure.sqlite.review",
    "skillbill.infrastructure.sqlite.telemetry",
    "skillbill.infrastructure.sqlite.workflow",
    "skillbill.infrastructure.workflow",
    "skillbill.ports.review",
    "skillbill.ports.telemetry",
    "skillbill.review",
    "skillbill.review.context.model",
    "skillbill.workflow.taskruntime",
    "skillbill.workflow.taskruntime.model",
]

# (subpackage_suffix, prefixes...) — first matching prefix wins; longer prefixes should come first.
PREFIX_RULES: dict[str, list[tuple[str, tuple[str, ...]]]] = {}

def add_rules(pkg: str, rules: list[tuple[str, tuple[str, ...]]]) -> None:
    PREFIX_RULES[pkg] = rules

add_rules("skillbill.application.review", [
    ("parallel.planning", ("ParallelCodeReviewRunnerPlanning", "ParallelCodeReviewRunnerRubric", "ParallelReviewPreparation")),
    ("parallel.verification", ("ParallelCodeReviewRunnerVerification", "ParallelCodeReviewRunnerFailure", "ParallelCodeReviewRunnerLanePlan")),
    ("parallel.core", ("ParallelCodeReview", "ParallelReview")),
    ("stats", ("ReviewStats", "ReviewAccounting")),
    ("packet", ("ReviewPacket", "ReviewEnvelope", "ReviewCommitEnvelope", "ReviewHunk", "ReviewLocator")),
    ("spec", ("SpecIntent", "ReviewSpec")),
    ("preparation", ("ReviewPreparation", "ReviewStageResume")),
    ("verification", ("ReviewClaimVerification", "ReviewIntegrationPass")),
    ("service", ("ReviewService", "RuntimeOwnedReviewMode", "RequestedReviewMode", "ReviewContractMappers")),
])

add_rules("skillbill.application.telemetry", [
    ("lifecycle", ("LifecycleTelemetry", "GoalLifecycleTelemetry", "GoalTelemetryRecord")),
    ("validation", ("FeatureVerifyTelemetryValidator", "QualityCheckTelemetryValidator", "CommonTelemetryValidators")),
    ("service", ("TelemetryService", "TelemetryLevelMutation", "BlockedReasonNormalizer", "RuntimeExceptionTelemetry", "PrDescriptionEditDetection")),
])

add_rules("skillbill.application.workflow", [
    ("decomposition", ("DecompositionWorkflow", "PendingDecomposition", "GoalBlockedPhaseRetry")),
    ("persist", ("WorkflowServiceArtifact", "WorkflowWireProjections", "WorkflowServiceIdentity", "WorkflowServiceInputMapping")),
    ("service", ("WorkflowService", "WorkflowFamilyKindMapping", "LegacyGoalRunner", "ContinuationStepResult")),
])

add_rules("skillbill.workflow.taskruntime", [
    ("handoff", ("FeatureTaskRuntimeHandoff", "PhaseHandoff")),
    ("phase", ("FeatureTaskRuntimePhase", "ProsePhaseOutput", "UpstreamPlanningProjection")),
    ("artifact", ("FeatureTaskRuntimeWireArtifact", "FeatureTaskRuntimeWorkflowArtifact", "FeatureTaskRuntimeRequiredArtifact", "FeatureTaskRuntimeRunEvidence")),
    ("validation", ("FeatureTaskRuntimePhaseOutputValidator", "ValidationGateFailure", "FeatureTaskRuntimeProviderLimit", "FeatureTaskRuntimeQualityGate", "FeatureTaskRuntimeTransition")),
])

add_rules("skillbill.workflow.taskruntime.model", [
    ("handoff", ("FeatureTaskRuntimeHandoff", "PhaseHandoff", "SettlementEnvelope")),
    ("phase", ("FeatureTaskRuntimePhase", "FeatureTaskRuntimePlanOutcome", "FeatureTaskRuntimePlanningProjection", "FeatureTaskRuntimeTransition", "FeatureTaskRuntimeDeliveredProjection")),
    ("repair", ("CorrectiveRepair", "FeatureTaskRuntimeCorrectiveRepair", "FeatureTaskRuntimeRepair", "FeatureTaskRuntimeOperatorBlock")),
    ("validation", ("FeatureTaskRuntimeValidation", "ValidationGateStatus", "FeatureTaskRuntimeVerdict", "FeatureTaskRuntimeFindingVerification")),
    ("audit", ("FeatureTaskRuntimeAudit", "FeatureTaskRuntimeDiagnostic", "FeatureTaskRuntimeQuarantine", "FeatureTaskRuntimeAcceptanceCriteria")),
    ("persistence", ("FeatureTaskRuntimePersistence", "FeatureTaskRuntimeCheckpoint", "FeatureTaskRuntimeGoalContinuation", "FeatureTaskRuntimeRunInvariant", "DurableArtifactMapReader", "FeatureTaskRuntimeImplementationAttempt", "FeatureTaskRuntimePrior")),
    ("review", ("FeatureTaskRuntimeReview", "FeatureTaskRuntimeSharedEvidence")),
    ("core", ("FeatureTaskRuntimeWireArtifactKind", "FeatureTaskRuntimeResolvedBranch", "FeatureTaskRuntimeRepositoryCheckpoint", "FeatureTaskRuntimeProjectionCanonical", "FeatureTaskRuntimeProviderLimitSignal", "FeatureTaskRuntimeQualityGateSelection", "FeatureTaskRuntimeDecomposeTerminal")),
])

add_rules("skillbill.review", [
    ("parallel", ("ParallelReview",)),
    ("finding", ("ReviewFinding", "ReviewTableFinding", "TriageDecision")),
    ("parsing", ("ReviewParser", "ReviewParsing", "ReviewLaneAggregation", "ReviewRunLane")),
    ("stage", ("ReviewStageDegradation",)),
    ("attribution", ("ReviewAttribution", "NormalizedStackLabel", "ReviewIssueCategory")),
])

add_rules("skillbill.review.context.model", [
    ("accounting", ("ReviewAccounting",)),
    ("packet", ("ReviewContextPacket", "ReviewPacketConsumer", "ReviewLaneBundle", "ReviewExpansionRecord")),
    ("launch", ("ReviewContextLaunch", "GovernedReviewAdjudication", "ReviewClaimVerification", "ReviewSpecAdjudication", "ReviewIntegrationPass", "CodeReviewExecutionMode")),
    ("commit", ("ReviewContextCommit", "ReviewAssignment")),
    ("hunk", ("ReviewContextHunk", "ReviewContextReference", "ReviewContextBudget", "ReviewContextWireLimits", "ReviewEvidenceLimits")),
    ("execution", ("ReviewContextExecution", "ReviewContextCanonical", "ReviewOperationPolicy", "ReviewLaneDecision", "ReviewSpecialistSummary", "GovernedReviewJsonRpc", "SpecIntentProjection")),
    ("bundle", ("ReviewContextBundle",)),
])

add_rules("skillbill.ports.review", [
    ("evidence", ("ReviewEvidence", "GovernedReviewEvidence", "ReviewSnapshot", "ReviewStoredHunk")),
    ("launch", ("ReviewLaunch", "ReviewNativeAgentPreflight", "DeclaredReviewSpecialists")),
    ("preparation", ("ReviewPreparation", "ReviewInputSource", "ReviewRubric", "ReviewAttribution")),
    ("repository", ("ReviewRepository", "ReviewRunCompleteness", "ReviewSpecialistContract")),
])

add_rules("skillbill.ports.telemetry", [
    ("lifecycle", ("LifecycleTelemetry", "FeatureTaskRuntimeLifecycle", "FeatureVerifyLifecycle", "GoalLifecycle", "PrDescriptionLifecycle", "QualityCheckLifecycle")),
    ("transport", ("RemoteTransport", "TelemetryClient", "TelemetryConfig", "TelemetryOutbox", "TelemetryReconciliation", "TelemetrySettings", "TelemetryLevel")),
])

add_rules("skillbill.error", [
    ("shellcontent", ("ShellContent", "AgentAddonShell", "FeatureTaskRuntimeShell", "GovernedReviewShell", "InstallShell", "ManifestShell", "ReviewContextShell", "ScaffoldShell", "SkillStagingShell", "WorkflowShell")),
    ("featuretask", ("FeatureTaskRuntime", "InvalidFeatureTaskRuntime")),
    ("goalrunner", ("GoalRunnerLaunch",)),
    ("core", ("DatabaseAccess", "DurableExternal", "ExternalAddon", "FeatureSpecPreparation", "InvalidMcp", "MalformedJson", "RejectedOutput", "SkillBillRuntime", "TelemetryHttp", "UnresolvedEnvironment", "FailureWireCode", "ShellContentContract")),
])

add_rules("skillbill.di", [
    ("core", ("RuntimeBootstrapBindings", "RuntimeComponent", "SkillBillVersion", "RuntimeDiagnosticsProvides")),
    ("featuretask", ("RuntimeFeatureTask",)),
    ("goal", ("RuntimeGoal",)),
    ("install", ("RuntimeInstall",)),
    ("review", ("RuntimeReview",)),
    ("scaffold", ("RuntimeScaffold",)),
    ("telemetry", ("RuntimeTelemetryProvides",)),
    ("workflow", ("RuntimeWorkflow",)),
    ("featurespec", ("RuntimeFeatureSpecProvides",)),
])

add_rules("skillbill.contracts.workflow", [
    ("schema", ("SchemaPaths", "SchemaContract")),
    ("payload", ("PayloadKeys", "WorkflowArtifactKeys", "WorktreeEditJournal")),
    ("session", ("WorkflowContinueSession", "WorkflowSessionSummary")),
    ("identity", ("FeatureTaskExecutionIdentity", "FeatureTaskRuntimeWorkerOwnership", "FeatureTaskRuntimeCommitPush", "FeatureTaskRuntimeGoalContinuation", "ImplementationReturnContract", "ProducerOutputEvidence", "RejectedOutputDiagnostic", "ValidationEvidence", "GoalSubtaskReviewInput", "GoalSubtaskReviewState", "IdeStatus")),
    ("featuretask", ("FeatureTaskRuntimeSchema", "FeatureTaskRuntime", "DecompositionManifestSchema")),
    ("goal", ("GoalObservability", "GoalPlanningPreparation", "GoalProgressEvent")),
])

add_rules("skillbill.cli.scaffold", [
    ("wizard", ("ScaffoldWizard", "ScaffoldCliWizard")),
    ("payload", ("ScaffoldPayload", "ScaffoldCliPayload", "ScaffoldCommandRequest")),
    ("commands", ("ScaffoldCli", "ScaffoldNew", "ScaffoldTopLevel", "ScaffoldAuthoring", "AssistedPlatform", "ScaffoldAssisted")),
])

add_rules("skillbill.cli.goal", [
    ("run", ("GoalCliRun", "GoalRunInput", "GoalRunPresenter", "GoalDiffCli")),
    ("status", ("GoalCliStatus",)),
    ("control", ("GoalCliControl",)),
    ("purge", ("GoalPurge", "GoalCliPurge")),
    ("core", ("GoalCliCommands", "GoalCliExit", "GoalCliFormatting", "GoalCliWatch")),
])

add_rules("skillbill.cli.install", [
    ("nativeagent", ("InstallNativeAgent", "InstallAgent", "NativeAgentCli")),
    ("mcp", ("InstallMcp",)),
    ("apply", ("InstallApply", "InstallReconcile", "InstallRequest", "InstallCliApply", "InstallCliMutations")),
    ("core", ("InstallCli", "InstallTopLevel")),
])

add_rules("skillbill.cli.kernel", [
    ("cli", ("CliFormat", "CliOutput", "CliPresenters", "CliRunState", "CliRepository", "CliCompletion", "DocumentedCliCommand")),
    ("agent", ("AgentAddon", "InvokingAgent", "ModelDirective", "UnavailableAgent")),
    ("payload", ("LearningCli", "WorkflowUpdateCli")),
])

add_rules("skillbill.infrastructure.workflow", [
    ("review.broker", ("FileSystemReviewEvidenceBroker",)),
    ("review.specialists", ("FileSystemReview", "ClasspathReview", "FileSystemDeclaredReview", "ImmutableReview", "ReviewCheckpoint", "ReviewCoordinate")),
    ("decomposition", ("DecompositionManifest", "FileSystemDecompositionManifest")),
    ("git", ("Git", "GhGoal")),
    ("featuretask", ("FileSystemFeatureTaskRuntime",)),
    ("filesystem", ("FileSystemCheckedOut", "FileSystemDiff", "FileSystemFeatureSpec", "FileSystemSpec")),
])

add_rules("skillbill.infrastructure.contracts.workflow", [
    ("decomposition", ("DecompositionManifest",)),
    ("featuretask.handoff", ("FeatureTaskRuntimeHandoff",)),
    ("featuretask.phase", ("FeatureTaskRuntimePhase", "FeatureTaskRuntimePlanningProjection", "FeatureTaskRuntimeProjectionCanonicalization", "FeatureTaskRuntimeQuarantine", "FeatureTaskRuntimeImplementationAttempt", "FeatureTaskRuntimeCheckpointIdentity", "FeatureTaskRuntimeBuildReceipt", "FeatureTaskRuntimePersistenceReview", "FeatureTaskRuntimeSharedEvidence", "FeatureTaskRuntimeValidationEvidence", "FeatureTaskRuntimeWorkerOwnership")),
    ("featuretask.schema", ("FeatureTaskRuntimeSchema", "FeatureTaskExecutionIdentity")),
    ("goal", ("GoalObservability", "GoalPlanningPreparation", "GoalProgressEvent", "GoalSubtaskReviewState", "IdeStatus")),
])

add_rules("skillbill.infrastructure.sqlite.workflow", [
    ("goalrunner", ("GoalRunner", "GoalChild", "GoalPlanning", "GoalShared", "GoalSubtask", "LegacyGoalPlanning")),
    ("featuretask", ("FeatureTask", "FeatureImplement", "FeatureVerify", "AgentActivity", "WorkerLease")),
    ("decomposition", ("DecompositionWorkflow",)),
    ("workflow", ("WorkflowState", "WorktreeEditJournal")),
])

add_rules("skillbill.infrastructure.sqlite.core", [
    ("migration", ("DatabaseMigration", "DatabaseColumnMigration", "MigrationLedger", "GoalPlanningSchemaMigration", "DiagnosticEvidenceRepairTurnMigration", "ReviewAttributionBackfillMigration", "LegacyGoalRunnerControlLedgerMigration")),
    ("schema", ("DatabaseSchema", "DatabaseReview", "DatabaseIdentity", "DatabasePaths", "DatabaseRuntime", "DatabaseWriteReadinessGate")),
    ("ops", ("ConnectionTransactions", "InternalSqliteDiagnostics", "StaleReconciliation", "StaleSessionReconciler", "DegradedValuePreview")),
])

add_rules("skillbill.infrastructure.sqlite.telemetry", [
    ("lifecycle", ("LifecycleTelemetry",)),
    ("goal", ("GoalTelemetry", "GoalIssueProgress")),
    ("outbox", ("TelemetryOutbox",)),
    ("redaction", ("TelemetryRedaction", "TelemetryAnonymous", "FeedbackEventMigration", "SkillBillRuntimeVersion", "QualityCheckTelemetry")),
])

add_rules("skillbill.infrastructure.sqlite.review", [
    ("stats", ("ReviewStats", "ReviewFindingStats", "ReviewHealth", "ReviewWorkflowStats", "GoalStats", "GoalWorkflowStats", "LoopRecordedOutcome", "FeatureTaskRuntimeStats", "ReviewPlatformSlug")),
    ("accounting", ("ReviewAccounting",)),
    ("stage", ("ReviewStage", "ReviewFinished", "ReviewTelemetryState", "ReviewRuntime", "ReviewSqlConstants", "ReviewRowMappers", "ReviewLaneAttribution", "Triage")),
    ("core", ("InvalidGoalTelemetryRowError",)),
])

add_rules("skillbill.infrastructure.sqlite.goalrunner", [
    ("manifest", ("DecompositionManifestProjection", "GoalParentProjection", "WorkflowGoalRunnerManifest")),
    ("control", ("GoalRunnerControl", "GoalRepositoryIdentity", "GoalContinuation")),
    ("outcome", ("WorkflowGoalRunnerOutcome", "WorkflowGoalRunnerBlock", "WorkflowGoalRunnerChild", "WorkflowGoalRunnerCrash", "WorkflowGoalRunnerProgress", "WorkflowGoalRunnerScoped", "WorkflowGoalRunnerStale")),
])

add_rules("skillbill.infrastructure.skills", [
    ("externaladdon", ("FileExternalAddon", "FileSystemExternalAddon")),
    ("install", ("FileSystemInstall", "FileSystemInstalled", "FileSystemBaseline", "FileSystemUninstall")),
    ("nativeagent", ("FileSystemNativeAgent",)),
    ("scaffold", ("FileSystemScaffold", "FileSystemRepoValidation")),
])

add_rules("skillbill.infrastructure.skills.scaffold.runtime", [
    ("validation", ("RepoValidation",)),
    ("service", ("ScaffoldService", "ScaffoldContract", "ScaffoldStandalone", "ScaffoldSupportPointer")),
])

add_rules("skillbill.infrastructure.skills.scaffold.platformpack", [
    ("loader.skillclass", ("SkillClassLoader",)),
    ("loader", ("ShellContentLoader",)),
    ("manifest", ("PlatformManifest", "PlatformPackSchema", "QualityCheckRoute", "ReadmeCatalog")),
])

add_rules("skillbill.infrastructure.skills.scaffold.validation", [
    ("review", ("ReviewSkillStructure",)),
    ("shape", ("SkillMdShape", "AuthoredContent", "GovernedSkillDrift")),
])

add_rules("skillbill.infrastructure.skills.install.nativeagent", [
    ("inventory", ("NativeAgentLinkInventory",)),
    ("install", ("InstallNativeAgent", "InstallCursor", "InstallJunie", "NativeAgentEmbedded", "NativeAgentLinkProvider")),
])

add_rules("skillbill.infrastructure.skills.install.staging", [
    ("staging", ("InstallStaging", "StageInstalledSkill", "InstallContentHash", "GeneratedSupportPointer", "InstallSupportPointer", "InternalSidecar")),
])

add_rules("skillbill.infrastructure.launcher.process", [
    ("waitloop", ("JvmAgentRunProcessWaitLoop", "ProcessWaitLoop", "ProbeRead", "CappedUtf8")),
    ("launch", ("AgentRunProcess", "JvmAgentRunProcessLaunch", "JvmAgentRunProcessOutput", "JvmAgentRunProcessRunner", "ProcessRun")),
    ("support", ("LauncherContentDigest",)),
])


def camel_tokens(name: str) -> list[str]:
    parts = re.findall(r"[A-Z][a-z0-9]*|[a-z0-9]+", name)
    return [p for p in parts if p]


def ceiling_for_package(pkg: str) -> int:
    return 20 if pkg.split(".")[-1] == "model" else 12


def assign_suffix(parent_pkg: str, basename: str) -> str:
    rules = PREFIX_RULES.get(parent_pkg, [])
    for suffix, prefixes in rules:
        for prefix in prefixes:
            if basename.startswith(prefix):
                return suffix
    tokens = camel_tokens(basename)
    if tokens:
        return tokens[0].lower()
    return "support"


KOTLIN_KEYWORDS = {
    "return",
    "class",
    "object",
    "when",
    "in",
    "is",
    "fun",
    "val",
    "var",
    "if",
    "else",
    "for",
    "while",
    "do",
    "try",
    "catch",
    "finally",
    "throw",
    "break",
    "continue",
    "null",
    "true",
    "false",
    "this",
    "super",
    "typealias",
    "typeof",
    "where",
    "by",
    "companion",
    "init",
}

KEYWORD_REPLACEMENTS = {
    "return": "implementationreturn",
    "class": "skillclass",
    "object": "objects",
    "when": "whenbranch",
    "in": "input",
    "is": "predicate",
}


def sanitize_segment(segment: str) -> str:
    lowered = segment.lower()
    if lowered in KOTLIN_KEYWORDS:
        return KEYWORD_REPLACEMENTS.get(lowered, f"{lowered}pkg")
    return segment


def sanitize_suffix(suffix: str) -> str:
    if not suffix:
        return suffix
    parts = suffix.split(".")
    cleaned: list[str] = []
    for part in parts:
        part = sanitize_segment(part)
        if cleaned and cleaned[-1].lower() == part.lower():
            continue
        cleaned.append(part)
    return ".".join(cleaned)


def sanitize_package(package: str) -> str:
    return ".".join(sanitize_segment(part) for part in package.split("."))


def split_oversized(parent_pkg: str, suffix: str, basenames: list[str], depth: int = 0) -> dict[str, list[str]]:
    suffix = sanitize_suffix(suffix)
    target_pkg = sanitize_package(f"{parent_pkg}.{suffix}" if suffix else parent_pkg)
    limit = ceiling_for_package(target_pkg)
    if len(basenames) <= limit:
        return {suffix: basenames}
    groups: dict[str, list[str]] = defaultdict(list)
    for name in basenames:
        tokens = camel_tokens(name)
        token_index = min(depth + 1, len(tokens) - 1) if len(tokens) > 1 else 0
        key = tokens[token_index].lower() if tokens else "misc"
        groups[key].append(name)
    result: dict[str, list[str]] = {}
    for key, names in sorted(groups.items()):
        child_suffix = sanitize_suffix(f"{suffix}.{key}" if suffix else key)
        if len(names) > limit:
            result.update(split_oversized(parent_pkg, child_suffix, sorted(names), depth + 1))
        else:
            result[child_suffix] = names
    return result


@dataclass
class KotlinFile:
    path: Path
    package: str
    basename: str


def discover_kotlin_files() -> list[KotlinFile]:
    out: list[KotlinFile] = []
    for scan_root in SCAN_ROOTS:
        if not scan_root.is_dir():
            continue
        for dirpath, _, files in os.walk(scan_root):
            for fname in files:
                if not fname.endswith(".kt"):
                    continue
                path = Path(dirpath) / fname
                rel = path.as_posix()
                if "/build/" in rel:
                    continue
                if "/src/" not in rel:
                    continue
                text = path.read_text(encoding="utf-8", errors="replace")
                m = PKG_RE.search(text)
                if not m:
                    continue
                out.append(KotlinFile(path=path, package=m.group(1), basename=fname[:-3]))
    return out


def plan_moves(files: list[KotlinFile]) -> dict[Path, tuple[Path, str, str]]:
    """Map src path -> (dst path, old_fqn_prefix, new_fqn_prefix) per file (class fqn updated separately)."""
    moves: dict[Path, tuple[Path, str, str]] = {}
    by_parent: dict[str, list[KotlinFile]] = defaultdict(list)
    for kf in files:
        for parent in REMAINDER_PARENTS:
            if kf.package == parent:
                by_parent[parent].append(kf)
                break

    for parent, group in by_parent.items():
        suffix_map: dict[str, list[str]] = defaultdict(list)
        for kf in group:
            suffix = assign_suffix(parent, kf.basename)
            suffix_map[suffix].append(kf.basename)
        final_map: dict[str, list[str]] = {}
        for suffix, names in suffix_map.items():
            for sub_suffix, sub_names in split_oversized(parent, suffix, sorted(names)).items():
                final_map.setdefault(sub_suffix, []).extend(sub_names)
        basename_to_suffix = {}
        for suffix, names in final_map.items():
            for n in names:
                basename_to_suffix[n] = suffix
        for kf in group:
            suffix = sanitize_suffix(basename_to_suffix[kf.basename])
            new_pkg = sanitize_package(f"{parent}.{suffix}" if suffix else parent)
            if new_pkg == kf.package:
                continue
            rel_pkg_path = new_pkg.replace(".", "/")
            parts = kf.path.parts
            src_idx = parts.index("src")
            kind = parts[src_idx + 1]
            module_root = Path(*parts[: src_idx + 1])
            new_dir = module_root / kind / "kotlin" / rel_pkg_path
            new_path = new_dir / kf.path.name
            moves[kf.path] = (new_path, kf.package, new_pkg)
    return moves


TOP_LEVEL_SYMBOL_RE = re.compile(
    r"^(?:(?:private|internal|public)\s+)?(?:tailrec\s+|suspend\s+)?(?:fun|val|var)\s+(\w+)",
    re.M,
)


def moved_symbols(old_pkg: str, new_pkg: str, text: str, class_name: str) -> dict[str, str]:
    mapping = {f"{old_pkg}.{class_name}": f"{new_pkg}.{class_name}"}
    for match in TOP_LEVEL_SYMBOL_RE.finditer(text):
        symbol = match.group(1)
        mapping[f"{old_pkg}.{symbol}"] = f"{new_pkg}.{symbol}"
    return mapping


def apply_moves(moves: dict[Path, tuple[Path, str, str]]) -> dict[str, str]:
    fqn_map: dict[str, str] = {}
    for src, (dst, old_pkg, new_pkg) in sorted(moves.items(), key=lambda x: str(x[0])):
        class_name = src.stem
        dst.parent.mkdir(parents=True, exist_ok=True)
        if dst.exists():
            raise SystemExit(f"destination exists: {dst}")
        subprocess.run(["git", "mv", str(src), str(dst)], cwd=REPO, check=True)
        text = dst.read_text(encoding="utf-8")
        text = PKG_RE.sub(f"package {new_pkg}\n", text, count=1)
        dst.write_text(text, encoding="utf-8")
        fqn_map.update(moved_symbols(old_pkg, new_pkg, text, class_name))
    return fqn_map


def update_imports(fqn_map: dict[str, str]) -> None:
    if not fqn_map:
        return
    ordered = sorted(fqn_map.items(), key=lambda x: -len(x[0]))
    kotlin_paths: list[Path] = []
    for scan_root in SCAN_ROOTS:
        for dirpath, _, files in os.walk(scan_root):
            for fname in files:
                if fname.endswith(".kt"):
                    kotlin_paths.append(Path(dirpath) / fname)
    for path in kotlin_paths:
        text = path.read_text(encoding="utf-8")
        original = text
        for old, new in ordered:
            text = text.replace(f"import {old}", f"import {new}")
        if text != original:
            path.write_text(text, encoding="utf-8")


def census_violations() -> list[str]:
    counts: dict[str, int] = defaultdict(int)
    for scan_root in [REPO / "runtime-kotlin"]:
        for mod in scan_root.iterdir():
            main = mod / "src/main/kotlin"
            if not main.is_dir():
                continue
            for dirpath, _, files in os.walk(main):
                for f in files:
                    if not f.endswith(".kt"):
                        continue
                    p = Path(dirpath) / f
                    m = PKG_RE.search(p.read_text(encoding="utf-8", errors="replace"))
                    if m:
                        counts[m.group(1)] += 1
    violations = []
    for pkg, c in sorted(counts.items()):
        lim = ceiling_for_package(pkg)
        if c > lim:
            violations.append(f"{pkg} has {c} > {lim}")
    return violations


def main() -> None:
    os.chdir(REPO)
    files = discover_kotlin_files()
    moves = plan_moves(files)
    print(f"Planned {len(moves)} file moves")
    fqn_map = apply_moves(moves)
    print(f"Applied moves; updating {len(fqn_map)} FQN imports")
    update_imports(fqn_map)
    violations = census_violations()
    print(f"Census violations: {len(violations)}")
    for v in violations[:50]:
        print(v)
    if len(violations) > 50:
        print("...")


if __name__ == "__main__":
    main()
