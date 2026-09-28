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

Use `[<ISSUE_KEY>] <descriptive title>` with the issue key in square brackets. Write a concise, title-case description that explains the user-visible outcome rather than copying a branch name or slug. Keep it under 70 characters when possible.

## Built-in Fallback Template

This template is a fallback. Use it ONLY when no repo-native PR template was found in the search above.

```markdown
# Summary

<1-3 sentences: what changed, why it matters, and the user-visible outcome. Reference the ticket/spec.>

<optional: bullet list of key changes if more than one logical change>

## Feature Flags

<flag name and description, or "N/A">

## Media

<screenshots or videos for UI changes, or "N/A">

# How Has This Been Tested?

<overview of tests performed — unit tests, manual verification, preview checks>

<reproducible test instructions:>
1. <step>
2. <step>
3. <expected result>
```

## Rules

- Summary should explain the **why**, not just list files changed.
- Test instructions should be concrete enough for a reviewer to reproduce.
- If the feature is behind a flag, mention how to enable it for testing.
- Include the Media section for UI changes, or write "N/A".
- Do not include a checklist in the generated description.
- Keep it concise — reviewers appreciate brevity.
- Always search for a repo-native PR template first — never skip the search step.
- If invoked from the feature workflow, check `.feature-specs/<ISSUE_KEY>-<feature-name>/spec.md` for additional context (this file exists only for prepared feature work).
