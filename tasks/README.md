# BaoTime work-package documentation

Planning and audit docs only. No application code lives here.

| Document | Role |
| --- | --- |
| [`plan.md`](plan.md) | Source-backed ingest plan: pins, licence, public vs Private API, call lifecycle (same-uuid vs NLS URL-only **separate** identities), FGS/push, WebView risk, milestones |
| [`todo.md`](todo.md) | Dependency-ordered tasks with acceptance, verification, files, gates |
| [`audit-resolution.md`](audit-resolution.md) | Code Auditor finding → resolution → document location |

**Authoritative workstream planning:** see [workstream adoption and precedence](workstreams/README.md), including the unchanged accepted overlay and its exact baseline manifest. Historical plan/todo text is read with that overlay, not as an alternative weaker rule.

Entry from the repository root: [README.md — Documentation index](../README.md#documentation-index).

**Local checkout hygiene:** When syncing standalone planning packets into `<repo-root>/tasks/`, copy `tasks/*.md` only and do not overwrite the repository root `README.md` or an unrelated in-flight plan.

Retrieval date for live pins in these docs: **2026-11-08**.
