## [2026-11-02] Hide the Goal run card for standalone starts and release
Context: Standalone starts and release requests still showed Start goal run, which would launch the wrong kind of work.
Decision: goalOfferStandsAside hides the card when the latest reply offers a standalone start or the latest operator message matches the Release preview. Keep it when a prefix choice or goal problem is pending.
Reason: Only the card can show those pending problems. Release has no start button, so the card would start a goal instead.
Revisit when: release matching should cover wording that does not name a release.

## [2026-11-02] Release a standalone reservation when its process exits
Context: A settled standalone reservation cleared only when the board showed a new row, so a run that blocked before creating one held the key until restart.
Decision: When a standalone start returns Running, RuntimeWorkControlRepository calls LaunchReservations.releaseOnExit, which releases that handle when the process exits.
Reason: A run still in progress keeps the refusal, and a late exit from an old run cannot clear a newer reservation.

## [2026-11-02] Retry NeedsPrefix from the execution composer
Context: NeedsPrefix in an execution chat left a notice with no way to pick a prefix and retry the start.
Decision: Keep the notice, store the action in ChatSession.prefixChoice, and show IssuePrefixPicker in ChatComposer. choosePrefix retries the same start with the chosen keyChoice.
Reason: New Task chats already had the picker, so extracting it keeps one prefix UI. The pending choice clears on a new start, /clear, /resume or a new message, and the retry still refuses a running execution.

## [2026-11-02] Refuse blank verify at the engine, not the New Task draft
Context: Verify requires an intake, but New Task clicks must still call startNewTask so the operator sees the existing start-problem path.
Decision: Desktop always forwards Verify, including a blank target. startOperation returns Refused with a reason that verify needs an intake, before launcher.start. pr-review-fix may omit target.
Reason: A draft-level early return would hide the engine refusal the spec requires.

## [2026-11-02] Launch execution-chat starts without send or pause-and-act
Context: A suggested start in an execution chat used to ignore action.start and always send or pause-and-act on the instruction.
Decision: executeAction branches on action.start. A running or Active execution gets a Notice and no launch. A stopped execution launches startNewTask in that item's repositoryRoot, with no Operator turn.
Reason: Pause-and-act lasts one turn, so holding an execution paused for a whole review, monitor or verify is wrong. Sending the instruction would skip reservation and the phase or operation argv path.

## [2026-11-02] Reuse the StandalonePhase reservation for verify and pr-review-fix, then release
Context: Operations reserve like phases, but FEATURE_VERIFY board rows are WorkKind.Operation with a null phaseId, so AwaitingBoard never matches a StandalonePhase select.
Decision: Reserve with StandalonePhase and the repository-identity plus id key. Release the handle once the operation starts and do not settle. Monitor and other phases still settle.
Reason: With no matching board row, settling would leave the key held and refuse the next verify. A new StandaloneOperation target would have required LaunchReservations changes that subtask 2 ruled out.
Revisit when: an operation should refuse a second launch while one runs. releaseOnExit now also ends settled reservations.

## [2026-11-02] Keep Verify findings distinct from start verify
Context: Execution chats already offer Verify findings for review findings, which is a different command from operation verify.
Decision: Keep the existing Fix findings and Verify findings paragraphs and add a line that those actions must not set start verify.
Reason: Collapsing them would send review-finding verify down the skill-bill operation path.

## [2026-11-02] Decode is the strict target seam; persist stays lenient
Context: monitor needs a PR URL or issue key and verify needs intake, while chats saved before this field have no target key.
Decision: decodeActions treats a missing target as null, stores a trimmed string, and drops the action when target is present but not a string. ChatStore writes target only when non-blank and reads a missing key as null, not malformed.
Reason: A numeric target in a live reply must not become a button. Older session files must still load.

## [2026-11-02] Share one start-guidance block across both chat prompts
Context: New-task chats listed six start wires without phrase mapping. Execution chats had no phase vocabulary, so the two prompts could teach different start rules.
Decision: Extract SHARED_START_ACTION_GUIDANCE and include it in both NEW_TASK_ACTION_GUIDANCE and CHAT_ACTION_GUIDANCE.
Reason: One constant keeps both scopes on the same start list, phrase mapping, labels and target rules.
