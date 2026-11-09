## [2026-10-31] Outside-commit reads fail on an unknown subtask range SHA
Context: outsideCommits subtracts every subtask range from the branch commits. A range SHA from the goal record may no longer exist in the repository.
Decision: When a range's fromSha or non-null toSha is not a commit, the read returns BaseUnavailable naming that SHA instead of skipping the range. The reader probes both range SHAs only after a rev-list call fails.
Reason: Skipping the range would report that subtask's own commits as outside commits, which looks like valid data and is wrong.
Revisit when: subtask ranges come from a source that guarantees reachable commits.

## [2026-10-31] Change list and patch reads capture raw git stdout
Context: runGitCommand and the other helpers route through invokeGitProcess, which merges stderr into stdout and trims the result. Trimming drops a trailing blank context line from a patch, and merged stderr such as CRLF warnings corrupts -z records.
Decision: The change list, untracked list and per-file patch reads go through captureGitStdout. It writes stdout to a temp file with stderr kept out, applies a byte cap and an accepted exit-code set, and returns the text untrimmed. Scalar probes (rev-parse, merge-base, cat-file -e) still use runGitCommand.
Reason: This follows the FileSystemDiffResolver.runQuery pattern already in the module. Single-token probe output is safe to trim, so those probes keep the shared helper.
Revisit when: the process runner can stream output without loading it fully, or can decode non-UTF-8 output.

## [2026-10-31] Change list and patch reads pin identical diff options
Context: The desktop shows list counts next to a patch it parses itself. User git config (diff.renames=copies, external diff, textconv, mnemonicPrefix, noprefix) can change statuses, rename pairing or patch headers.
Decision: Both reads pin -c diff.renames=true, -M, --relative, --no-ext-diff, --no-textconv and --no-color. The patch read also pins --unified=3 and a/ b/ prefixes. Statuses and counts come from one --raw --numstat -z call, so they share a snapshot. A C record that still appears maps to ADDED with no oldPath.
Reason: The same rename detection on both reads keeps a renamed file's patch matching its listed counts. --relative keeps tracked paths cwd-relative, which is how ls-files --others reports untracked paths.

## [2026-11-06] Interrupted removal belongs to one checkout directory
Context: A removal marker can survive successful deletion. A replacement checkout at the same path must not inherit permission to delete its new edits or unpublished commits.
Decision: Removal markers retain the directory's filesystem key and creation identity. Recovery requires an exact match. Missing, legacy or mismatched identity uses ordinary retention checks and emits a diagnostic. Creation clears the prior marker before invoking Git and fails if that cleanup cannot finish. Removal refuses when the filesystem cannot identify the directory.
Reason: A path identifies a location across checkout replacements. The directory identity binds recovery to the original partial deletion without changing when its children disappear.

## [2026-11-09] Chat comparisons include integrated upstream changes
Context: A turn snapshot treats a pull as chat work. Filtering paths cannot separate upstream and chat edits in the same file.
Decision: The snapshot port resolves the remote default branch's common ancestor with HEAD and merges it into the original baseline. A clean ancestor baseline advances directly. A dirty baseline uses merge-tree and an unreferenced comparison commit with fixed identity and dates. The desktop uses that comparison for turn attribution, totals and the Changes window, and excludes upstream commits from its timeline. Repositories without remote refs retain their original baseline. Conflicts and Git failures return an unavailable result, which the desktop records through its existing diagnostics.
Reason: Combining the baseline trees preserves prechat dirty edits without changing the working tree, index, refs or stored chat identity. Fixed commit metadata makes repeated comparisons reuse the same object. The persisted baseline stays unchanged, so older chat records remain readable without migration.
