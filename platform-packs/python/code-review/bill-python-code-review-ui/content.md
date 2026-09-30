---
name: bill-python-code-review-ui
description: Review Python-rendered UI, admin flows, templates, notebooks, dashboards, forms, and generated reports.
internal-for: skill-bill
---

# Python UI Review

Review user-visible behavior authored, rendered, or orchestrated by Python.

## Focus

- Templates, forms, admin flows, HTMX fragments, notebooks, dashboards, reports, state, validation, navigation, and rendering behavior

## Ignore

- Defer accessibility-only findings to the `ux-accessibility` specialist.
- Defer escaping, injection, and other trust-boundary findings to the `security` specialist.
- Pure visual taste without a user-visible interaction, rendering, state, or comprehension failure

## Applicability

Use this specialist for Django or Jinja templates, forms, admin pages, HTMX-style fragments, email and report templates, Streamlit, Dash, Panel, notebooks, generated HTML/PDF, and operations dashboards.

## Project-Specific Rules

### Python-Owned UI Rules

- When Django templates are detected, require each context key and `{% include %}` fragment to have one view or component owner; duplicated state risks stale or contradictory rendering.
- Verify Django admin `ModelAdmin`, actions, and readonly fields respect object permissions and durable outcomes; optimistic success messages can mislead users after failed mutations.
- When Flask or Jinja rendering is detected, require `render_template` paths to handle absent, empty, partial, and invalid context data; unchecked values cause broken pages instead of recoverable states.
- Require Django `Form`, `ModelForm`, or WTForms submissions to preserve safe textual and selection values and display field plus non-field errors; never repopulate passwords, secrets, or browser file inputs, and instead provide clear re-entry guidance, because clearing safe input blocks recovery while echoing sensitive or unsupported controls creates exposure or misleading state.
- Require explicit loading, empty, error, and partial-data rendering in Streamlit, Dash, or Panel callbacks through detected primitives such as `st.status`, `dcc.Loading`, `pn.indicators.LoadingSpinner`, `pn.pane.Alert`, and dedicated empty or partial panes, protected by semantic state tests; silent waits and blank output make failures indistinguishable from no data.
- Verify Streamlit `st.session_state` keys and Dash callback inputs have one lifecycle owner; reruns or callback races can reset user selections and show invalid state.
- Require Panel, Dash, or notebook data access to batch repeated requests; cache only repeatable reads with explicit user or tenant scope keys, a freshness policy, and owned invalidation, while mutations, volatile reads, and sensitive datasets remain uncached unless their contract proves safety, because unconditional caching can leak cross-user data or serve stale results.
- Require notebook widgets and cells to make execution prerequisites and stale results visible through `ipywidgets` state or equivalent metadata; out-of-order execution can present incorrect analysis as current.
- Verify generated HTML, PDF, or dataframe reports render page breaks, missing values, long labels, and large tables without clipping contract data; layout failures can hide decisions from users.
- When PySide, PyQt, Tkinter, or Textual is detected, require background work to marshal updates through `Signal`, `after`, or the framework event loop; cross-thread widget mutation causes races and crashes.
- Require synchronous action success only after database commit. For asynchronous work, report accepted or pending only after durable queue acceptance, expose a separate worker-completion acknowledgement before claiming completion, and provide error and retry paths for delivery or execution failure; conflating acceptance with completion creates user-visible contract failure.
- Verify `gettext`, Babel, timezone conversion, and locale-aware number formatting occur at the presentation boundary; server defaults can display incorrect dates, amounts, and ordering.
- Require server-rendered navigation and forms to retain a functional non-JavaScript path when the detected product promises progressive enhancement; optional script failure must not block core tasks.
- Verify Django template fragments preserve `request`, CSRF, and permission context through `{% include %}` or component calls; lost context can render invalid actions and broken forms.
- Require desktop teardown to disconnect Qt `Signal` handlers or cancel Tk `after` callbacks before widgets close; late callbacks otherwise race destroyed UI state and crash the application.
- Reject unbounded rows or images passed to Streamlit `st.dataframe`, Dash tables, or notebook display; uncontrolled rendering consumes memory resources and causes performance failure.
- For Blocker or Major findings, describe the concrete user-visible interaction or rendering failure scenario.
