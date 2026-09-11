# AGENTS.md

## Project role

This repository is a documentation-first, architecture-driven project for a local-first virtual envelope budgeting app. It is not a large application codebase yet; it is a specification and implementation-planning workspace.

## Source of truth

- Active working documentation lives under `Docs/`.
- Legacy and archived material lives under `Docs/old/`.
- New working docs at the top level of `Docs/` are authoritative for implementation decisions.
- Files under `Docs/old/` are historical reference only and must not override active requirements.
- The live project contract is the current approved combination of active docs and the working prototype in `java-cli`; both are expected to evolve together and must stay synchronized.

## Working rules

1. Prefer the active top-level docs over archived docs.
2. Treat the PRD and System Architecture Spec as the binding product and technical contract.
3. Treat the Implementation Backlog as the execution order.
4. Preserve append-only ledger semantics; never use destructive history deletion as the normal path.
5. Follow the canonical waterfall order: Buffer -> Short Term Funds -> Sinking Funds.
6. Enforce month-end idempotence using `closed_cycle_YYYY_MM` metadata keys.
7. Use `BEGIN IMMEDIATE` and `ROLLBACK` for atomic lifecycle execution.
8. Keep raw transaction data immutable; closed periods should display summary nodes by default.
9. Use audit mode to expose full raw transaction data when the user explicitly requests it.
10. When a change is approved in the working prototype, the corresponding requirement, behavior, or contract in the active docs must be updated in the same change set.
11. Do not treat the Java CLI as a separate isolated experiment; it is part of the evolving project contract and must remain consistent with the docs.

## P0 execution order

Work must proceed in this order:

1. ARCH-001: Schema hardening and migration support
2. ARCH-002: Cycle lock metadata contract
3. ARCH-003: Atomic month-end lifecycle engine

## File conventions

- Use Markdown for product and architecture documents.
- Use `.sql` files for schema contracts and migration scripts.
- Keep implementation notes explicit and review-friendly.
- When adding new docs, prefer direct, descriptive names.

## Definition of done for implementation work

- Requirement is documented and traceable.
- Schema or logic reflects the approved architecture.
- The change is reflected in the relevant backlog item.
- The result is reviewed against the active docs, not legacy notes.
