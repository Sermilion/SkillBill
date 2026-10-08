# SKILL-406 Subtask 1 - Implement the requested change

Parent spec: [.feature-specs/SKILL-406-freshly-resolved-repository-checkpoint/spec.md](spec.md)
Issue key: SKILL-406

## Scope

SKILL-406 Freshly resolved repository checkpoint

freshly resolved repository checkpoint

Supplied requirements are authoritative and need no tracker lookup. Locally allocated issue keys do not require a tracker connection. Only an explicit unresolved tracker reference without requirements needs lookup through its connected tracker before planning. Use the returned requirements, not the URL title. If that lookup fails, block with the returned reason before implementation; never infer or substitute requirements.

## Acceptance Criteria

1. SKILL-406 Freshly resolved repository checkpoint freshly resolved repository checkpoint

## Non-Goals

- None

## Dependency Notes

Depends on: none
The full goal owns planning and execution of the supplied requirements.

## Validation Strategy

Run the repository's required checks and verify every supplied acceptance criterion.

## Next Path

Complete the goal and prepare its pull request.

## Spec Path

.feature-specs/SKILL-406-freshly-resolved-repository-checkpoint/spec_subtask_1_implement-the-requested-change.md

## Implementation Details

The supplied text repeats the feature title and forbids inferring a requirement or calling the tracker. That title matches the shipped domain handoff: a checkpoint-aware policy carries a freshly resolved repository checkpoint. Subtask 1 stays “Implement the requested change.” Implement leaves production source unchanged. Acceptance is the behavior already enforced and tested below.

Assumption for implement to confirm: `FeatureTaskRuntimeRunLoop` already resolves the checkpoint through `WorkflowGitOperations` and the existing `repositoryFingerprint` extension. The preplan digest did not open that call site. This subtask leaves the loop alone. If implement finds the loop supplying a null or stale checkpoint into a checkpoint-aware handoff, stop and report that fact. Do not invent a loop change from the title.

Assumption: `FeatureTaskRuntimePhaseWorkflowProjectionDeclarations` already sets both checkpoint-aware policies on the phases that need them. The digest does not name those phases. Implement leaves the declarations unchanged.

### Task 1 — Keep the shipped handoff contract

Serves acceptance criterion 1 (AC-001).

End state: no production edit. The symbols below stay as the digest describes them.

Paths and symbols:

- `FeatureTaskRuntimeHandoffProjectionValidator.validate(inputs: FeatureTaskRuntimeHandoffProjectionInputs): FeatureTaskRuntimeHandoffEnvelope` throws `invalidFeatureTaskRuntimeHandoffProjection` on rejection.
- `validateToResult` accepts an envelope whose `repositoryCheckpoint` is `inputs.resolvedCheckpoint`.
- `resolveCheckpointFields` calls `FeatureTaskRuntimeHandoffProjectionEnvelopeWire.enforceCheckpointPolicy(inputs, declaration, resolvedFields.orEmpty())`.
- `enforceCheckpointPolicy` reads `declaration.checkpointPolicy` (`PhaseHandoffProjectionDeclaration.checkpointPolicy`, from `delivery.checkpointPolicy`).
- `NOT_REQUIRED` (`wireValue` `not_required`) returns the fields unchanged.
- `REFRESH_FROM_REPOSITORY` (`refresh_from_repository`) and `MUST_MATCH` (`must_match`) reject with `FeatureTaskRuntimeHandoffProjectionFailureKind.CHECKPOINT_POLICY_VIOLATION` when `inputs.resolvedCheckpoint` is null. The messages stay `checkpoint-aware policy requires a freshly resolved repository checkpoint, none was supplied.` and `must_match requires a freshly resolved repository checkpoint, none was supplied.`
- A non-null `resolvedCheckpoint` satisfies both checkpoint-aware policies.
- The wire rewrites a `repository_checkpoint` compact reference of kind `REPOSITORY_CHECKPOINT` to `resolvedFingerprint`, and when the carried value contains a claim, appends `FeatureTaskRuntimeHandoffProjectionValidator.CHECKPOINT_PRODUCER_CLAIM_SEPARATOR` (`+producer-claimed:`) plus that claim. When `repository_checkpoint` is a declared field name and still absent, the wire appends that compact reference. `REPOSITORY_CHECKPOINT_FIELD` is `"repository_checkpoint"`.
- `FeatureTaskRuntimeRepositoryCheckpoint` is `fingerprint: String`, `baseRef: String? = null`, `headRef: String? = null`, `workingTreeOwnedPaths: List<String> = emptyList()`. Construction fails when `fingerprint` is blank, longer than `MAX_REPOSITORY_FINGERPRINT_LENGTH` (256 in `FeatureTaskRuntimeHandoffSharedValues`), or when any owned path is blank. The blank-fingerprint reason states that an unidentified checkpoint cannot satisfy `must_match` or `refresh_from_repository`.
- `toEnvelopeMap()` writes `ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT_FINGERPRINT`, then `base_ref`, `head_ref`, and `working_tree_owned_paths` when present.
- `FeatureTaskRuntimeHandoffProjectionInputs` carries `resolvedCheckpoint` and `expectedCheckpoint`, both nullable and defaulting to null. The validator and envelope wire read `resolvedCheckpoint`. `expectedCheckpoint` stays unused on that path.
- `../../../runtime-kotlin/ARCHITECTURE.md` records the same rule: both checkpoint-aware policies require and carry a freshly resolved checkpoint. `must_match` stays a legacy wire value. Both `must_match` and `refresh_from_repository` accept repository movement and re-derive consumer scope. The domain stays git-agnostic.

Tests to add: none. `test_obligations`: empty. The four tests below already lock this contract. A new test would re-cover the same branches.

Tests the validate phase runs as criterion proof. This plan runs none of them:

- `FeatureTaskRuntimeHandoffProjectionValidatorTest`: `refresh_from_repository requires a freshly resolved checkpoint` fails with `CHECKPOINT_POLICY_VIOLATION` when `resolvedCheckpoint` is omitted. With `FeatureTaskRuntimeRepositoryCheckpoint(fingerprint = "head-abc", baseRef = "main", headRef = "feat/x", workingTreeOwnedPaths = listOf("src/Main.kt"))`, the envelope’s `workingTreeOwnedPaths` is `listOf("src/Main.kt")`.
- `must_match refreshes instead of rejecting repository movement` keeps fingerprint `head-abc` when the resolved checkpoint is `head-abc` and the expected checkpoint is `head-def`.
- `must_match does not require a recorded checkpoint` expects `head-abc`.
- `must_match accepts identical runtime checkpoints` expects `head-abc`.

### Constraints

- Violation strings and the policy `wireValue`s `not_required`, `refresh_from_repository`, and `must_match` stay as written. They are a durable handoff contract.
- `must_match` keeps accepting repository movement. A resolved checkpoint of fingerprint `head-abc` satisfies the policy when `expectedCheckpoint` is `head-def`. Implement adds no comparison of `expectedCheckpoint` to `resolvedCheckpoint`.
- A null `resolvedCheckpoint` under `REFRESH_FROM_REPOSITORY` or `MUST_MATCH` stays `CHECKPOINT_POLICY_VIOLATION`. A new policy that returns the original fields from that null-checkpoint branch is out of scope.
- `base_ref`, `head_ref`, and `working_tree_owned_paths` already exist on `toEnvelopeMap()`. This plan adds no wire key. Any later key on that map belongs in an owning keys object.
- The change boundary stays domain handoff projection. Checkpoint resolution stays on the run loop. The projection validator stays git-agnostic.
- Rollout stays inside the decomposition manifest: `same_branch_commit_per_subtask`, `base_branch` `main`, `feature_branch` `feat/SKILL-406-freshly-resolved-repository-checkpoint`, subtask 1 only. This feature uses that manifest branch. AGENTS.md sends fixes to `base/SKILL-380-phase-slot-strategies` until that branch merges; that rule does not retarget this feature.
- No `./install.sh`, `./uninstall.sh`, `skill-bill install`, or `skill-bill install apply`.
- History headings `.github/workflows/agent/history.md#845bdcbb598a`, `#763032f57126`, and `#94fb082fbfbb` are spotless-ratchet and extension-release entries. No catalog `heading_id` applies. Applicable decisions are the checkpoint type and the architecture paragraph above.
- Validate owns the repository’s required checks. `tests_executed` stays empty. Review, audit, history, commit, and the pull request stay with their owning phases.
