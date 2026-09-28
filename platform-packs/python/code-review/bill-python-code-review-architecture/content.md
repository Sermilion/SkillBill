---
name: bill-python-code-review-architecture
description: Review Python architecture, package boundaries, dependency direction, configuration ownership, and application/library seams.
internal-for: skill-bill
---

# Python Architecture Review

Review concrete ownership, dependency, lifecycle, and module-boundary failures.

## Focus

- Package boundaries, dependency direction, transaction and configuration ownership, service lifetimes, and application/library seams

## Ignore

- Generic layering preferences that conflict with the repository's established architecture without creating a concrete risk
- Layering purity for simple CRUD where an added abstraction would not reduce coupling or protect an invariant

## Applicability

Use this specialist across Python packages, libraries, applications, services, framework adapters, CLIs, and worker entry points. Infer the repository's local architecture first and treat it as authoritative before applying generic patterns.

## Project-Specific Rules

### Python Architecture Rules

- Require imports in `domain`, `application`, and adapter packages to follow the repository's declared dependency direction; reverse imports risk cycles and make core behavior depend on framework state.
- Reject module-level database calls, client creation, environment reads, or worker startup during `importlib.import_module` in reusable or import-probed modules. Permit environment reads in a repository-owned settings module or composition root that explicitly owns configuration loading; misplaced import-time side effects break test discovery, CLI startup, and packaging tools.
- Verify `__init__.py`, `__all__`, and re-export changes preserve the documented public package boundary; accidental exports or removals break downstream imports.
- For PEP 420 namespace packages configured through `pyproject.toml`, require each concrete module or subpackage to have one distribution owner and reject overlapping wheel paths; stray `__init__.py` files or duplicate contributors can shadow modules and load incorrect code.
- Require `main()`, an ASGI/WSGI factory, or the repository's composition root to construct concrete dependencies; hidden singleton construction inside domain modules leaks lifecycle and configuration ownership.
- Keep detected `fastapi.Request`, Django model, Flask context, Celery task, Click, or Typer types at their owned adapter edge unless the local contract exposes them; framework leakage makes reusable code fail outside that runtime.
- Require optional imports guarded by declared extras such as `project[postgres]` in `pyproject.toml`; unconditional imports make the base installation crash when optional dependencies are absent.
- Require one owner for SQLAlchemy `Session`, `httpx.AsyncClient`, and transaction lifetime; capturing request-scoped resources in background tasks risks closed-resource failures and split commits.
- Verify `asyncio.create_task`, Celery dispatch, or framework background work receives immutable data or durable identifiers instead of live request/session objects; misplaced ownership causes races and detached-model failures.
- Require console scripts and plugin entry points under `[project.scripts]` or `[project.entry-points]` to target import-safe callables; stale modules break installed commands and plugin discovery.
- Verify build-backend package discovery and include rules in `pyproject.toml` contain every runtime module and data file; omitted ownership produces wheels that pass source tests but fail after installation.
- Reject duplicated orchestration that bypasses an existing repository extension point such as a dependency provider or service factory; competing paths risk divergent validation and inconsistent contract data.
- For Blocker or Major findings, describe the concrete dependency-cycle or ownership-boundary failure scenario.
