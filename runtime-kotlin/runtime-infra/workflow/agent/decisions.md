## [2026-10-31] Change list and patch reads capture raw git stdout
Context: runGitCommand and the other helpers route through invokeGitProcess, which merges stderr into stdout and trims the result. Trimming drops a trailing blank context line from a patch, and merged stderr such as CRLF warnings corrupts -z records.
Decision: The change list, untracked list and per-file patch reads go through captureGitStdout. It writes stdout to a temp file with stderr kept out, applies a byte cap and an accepted exit-code set, and returns the text untrimmed. Scalar probes (rev-parse, merge-base, cat-file -e) still use runGitCommand.
Reason: This follows the FileSystemDiffResolver.runQuery pattern already in the module. Single-token probe output is safe to trim, so those probes keep the shared helper.
Revisit when: the process runner can stream output without loading it fully, or can decode non-UTF-8 output.

## [2026-10-31] Change list and patch reads pin identical diff options
Context: The desktop shows list counts next to a patch it parses itself. User git config (diff.renames=copies, external diff, textconv, mnemonicPrefix, noprefix) can change statuses, rename pairing or patch headers.
Decision: Both reads pin -c diff.renames=true, -M, --relative, --no-ext-diff, --no-textconv and --no-color. The patch read also pins --unified=3 and a/ b/ prefixes. Statuses and counts come from one --raw --numstat -z call, so they share a snapshot. A C record that still appears maps to ADDED with no oldPath.
Reason: The same rename detection on both reads keeps a renamed file's patch matching its listed counts. --relative keeps tracked paths cwd-relative, which is how ls-files --others reports untracked paths.
