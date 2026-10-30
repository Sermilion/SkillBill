## [2026-10-30] SKILL-447 desktop detail
Areas: desktop/feature/detail, desktop/core/ui, desktop/app
- Detail renders its initial header from the route, observes runtime sections, pages each list by its runtime cursor and retains stale sections after refresh failure.
- Standalone run detail shows saved phase launch briefings in pipeline order when available.
- Detail section collapse state persists while paging between items, and goal finish links navigate to section headers.
- Controls use typed outcomes and reservations; incident recovery checks prerequisites and preview identity. Operator decisions also appear for goals without incidents.
- RepositoryWebPages is reusable and reads git configuration without launching git. URL insteadOf rewrites are not resolved.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

