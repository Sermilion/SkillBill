---
name: bill-go-code-review-ui
description: Use when reviewing Go-owned HTML template, component, form, fragment, CLI, TUI, progress, layout, and rendering correctness.
internal-for: skill-bill
---

# Go UI Review Specialist

Own rendering and interactive state correctness. Security owns escaping and authorization; UX/accessibility owns semantics and successful task completion.

## Focus

- Go-owned template, component, form, fragment, CLI, TUI, progress, layout, and render-state correctness
- Observable rendering, interaction, redirect, redraw, and terminal-capability failures

## Ignore

- Accessibility semantics and task-completion findings owned by ux-accessibility
- Escaping, authorization, and sensitive-data findings owned by security

## Applicability

Apply to Go-rendered web surfaces and interactive terminal programs. Apply templ, htmx, Bubble Tea, or other ecosystem guidance only when repository evidence confirms its use.

## Project-Specific Rules

### Go UI Correctness Rules

Every rule below identifies a concrete rendering or interaction failure.

- Require `html/template.ExecuteTemplate` errors to reach the response or fallback before bytes are committed; ignored failures produce truncated pages with a success status.
- Verify template data uses a purpose-built `struct` view model rather than persistence rows or request objects; leaking internal state risks invalid rendering and accidental data exposure.
- Ensure template lookups use `template.Must` only during controlled startup; request-time panic on a missing template can crash active work.
- Require form repopulation from an allowlisted view model containing only safe fields selected from validated input; clearing safe input causes task failure, while reflecting passwords, tokens, or payment data back into the DOM creates sensitive-data exposure.
- Require `r.ParseForm` errors to be handled before reading controls, then inspect `r.PostForm` membership and complete value slices for checkbox, radio, and multi-select fields; `r.FormValue` cannot distinguish absent from present-empty controls and returns only the first value, which can mutate unintended data.
- Ensure POST success follows `http.StatusSeeOther` redirect when duplicate refresh submission is unsafe; rendering success directly risks repeated writes.
- Verify fragment handlers interpret the incoming `HX-Target` request header as context only, and use the `HX-Retarget` response header solely when intentionally overriding the client target; confusing the headers replaces the wrong region and corrupts interaction state.
- Require applicable `HX-Trigger` response headers to name deliberate client events without assuming they restore focus or announce status; missing client handlers leave controls unfocused and feedback silent after a swap.
- Verify applicable `templ.Component` values receive immutable shaped parameters and propagate render errors; hidden shared state can race and produce incorrect output.
- Require stable element IDs only when targeting, label association, focus preservation, DOM morphing, or framework reconciliation depends on identity; unstable identity in those flows can bind controls or state to the wrong item, while ordinary template rendering does not need invented IDs.
- Verify the repository's actual view model represents every reachable empty, pending, success, and error branch needed by that surface; inventing a universal `RenderState` hides real state transitions while omitted branches produce a blank or misleading screen.
- Verify links and form actions use the active router or `url.URL` rather than concatenated strings; stale paths break navigation after route changes.
- Ensure interactive CLI output detects `term.IsTerminal` or an equivalent capability before cursor control; ANSI sequences corrupt redirected logs and files.
- Require TUI update logic such as Bubble Tea `Update` to own state transitions and return commands separately; mutation from background goroutines risks races and redraw corruption.
- Verify terminal layout uses measured cell width for Unicode rather than `len(string)`; byte counts can break columns and hide status text.
- Ensure progress renderers writing to `io.Writer` stop and restore the cursor on cancellation or error; abandoned terminal state creates an operational failure by making later output unreadable.
- Require CLI and TUI failures to retain an actionable `error` after redraw; transient messages can disappear and leave an invalid success impression.
- For Blocker or Major findings, describe the concrete user-visible interaction or rendering failure scenario.
