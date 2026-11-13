# PR Description Generator Content

## How It Works

1. **Determine the comparison base** — the branch this one was forked from (its *parent*), which is **not necessarily `main`**. Resolve it in this order:
   - If the caller or user passed an explicit base/merge-base, use that.
   - Otherwise detect the parent branch:
     ```
     git show-branch -a 2>/dev/null \
       | grep '\*' \
       | grep -v "$(git branch --show-current)" \
       | head -n1 \
       | sed 's/.*\[\([^]~^]*\).*/\1/'
     ```
   - If detection fails or returns nothing, fall back to `main`.
   - If the resolved base is ambiguous (e.g. detection and `main` disagree), use `main`. This `git show-branch` heuristic is fragile — it depends on sibling branches being present and can return the wrong parent — so treat its result as a best guess, not authoritative.
2. **Gather context** — read the git diff from that merge-base to `HEAD`, along with the commit log and branch name.
3. **Read project guidelines** — check `CLAUDE.md`, `AGENTS.md`, and the `skill-bill` section in `.agents/skill-overrides.md` when present.
4. **Search for a repo-native PR template** — this is mandatory before generating anything. Search ALL standard template locations listed below. Read any template file found. This step must produce either a found template or a confirmed absence.
5. **Generate** the title and description using the repo-native template if one was found, or the built-in fallback template only if no repo-native template exists.
6. **Return** the title and description as this step's value; the runtime opens the pull request.

## Repo-Native PR Template Search (mandatory)

You MUST search for a repo-native PR template before generating any description. Do not skip this step. Do not assume no template exists without checking.

Search these locations in order, using glob or file-read tools:

1. `.github/pull_request_template.md`
2. `.github/PULL_REQUEST_TEMPLATE.md`
3. `pull_request_template.md`
4. `PULL_REQUEST_TEMPLATE.md`
5. `.github/pull_request_template/*.md`
6. `.github/PULL_REQUEST_TEMPLATE/*.md`
7. `docs/pull_request_template.md`

When a repo-native template is found:

- **Use it as the output structure.** Preserve its headings, section order, and any non-checklist placeholder text exactly as authored.
- Do NOT reshape it into the built-in Skill Bill format.
- Fill the template sections with concise, reviewer-friendly content derived from the gathered git/spec context.
- Omit checklist sections and checklist items from the generated description.

When multiple templates are found and there is no obvious default, use the first one in the search order above.

Only when NO repo-native template is found at any of the above locations, fall back to the built-in Skill Bill template in the section below.

## PR Title

Use `[<ISSUE_KEY>] <descriptive title>` with the issue key in square brackets; drop the brackets when the work has no issue key. A reviewer scanning a list of pull requests should understand the change from the title alone:

- Start with an imperative verb that names the outcome: "Add", "Fix", "Stop", "Let", "Keep". Write "Fix duplicate tickets after offline sync", not "Ticket sync changes" or "Fixed a bug".
- Describe what changes for the user or the codebase, not how. Leave class names, file names, and branch slugs out unless the change is about them.
- Keep it under 70 characters, in sentence case, with no trailing period.

## Writing the Description

These rules apply to every section, whether it comes from the repo-native template or the built-in fallback.

- Open the summary with the problem or goal in one or two sentences, then say what this change does about it. A reviewer who reads only the first paragraph should know why the pull request exists.
- State the outcome a user or developer will notice. Reference the ticket or spec it delivers.
- Group the changes by concern, one bullet per logical change. Name the key modules or types a reviewer should open first. Do not list every file; the diff already does.
- Call out what needs a careful look: risky paths, trade-offs and the alternative you rejected, migrations, rollout or feature-flag steps, and anything deliberately left out of scope.
- Report the validation that actually ran: tests added or changed, checks, manual verification, and what each showed. Give numbered steps a reviewer can follow to reproduce, with the expected result. Never claim validation that did not run.
- If the change is behind a feature flag, name the flag and say how to enable it for testing.
- Write plain, direct sentences. Skip filler such as "This PR…", "successfully", or restating the title.
- Leave out a section that does not apply instead of writing "N/A", unless the repo-native template requires it.
- Do not include a checklist in the generated description.

## Built-in Fallback Template

This template is a fallback. Use it ONLY when no repo-native PR template was found in the search above.

```markdown
## Summary

<The problem or goal in one or two sentences, what this change does about it, and the outcome a user or developer will notice. Reference the ticket or spec.>

## Changes

- <One bullet per logical change, grouped by concern. Name the key modules or types.>

## Reviewer Notes

<Risks, trade-offs, migrations, rollout or feature-flag steps, and anything deliberately out of scope. Omit when there is nothing to call out.>

## Testing

<The validation that ran and what it showed.>

1. <Step a reviewer can follow>
2. <Expected result>

## Screenshots

<Before and after captures for UI changes. Omit for changes without UI.>
```

## Rules

- Keep it concise: a reviewer should take in the summary in under a minute.
- Always search for a repo-native PR template first — never skip the search step.
- Specs are no longer committed, so put a short spec summary in the description: the intended outcome and the acceptance criteria titles from the bundle's parent spec.
- If invoked from the feature workflow, check the project store spec `specs/<ISSUE_KEY>-<feature-name>/spec.md` (`sona project path` prints the store) for additional context; a legacy goal keeps it at `.feature-specs/<ISSUE_KEY>-<feature-name>/spec.md` (this file exists only for prepared feature work).
