# BUILD_PLAN

Status: DRAFT v0.1 (2026-10-14). Work one milestone at a time, in order. Each milestone ends with: tests green, production build green, a report with real command output, and any open questions added to DECISIONS.

## Milestone overview

| Id | Name | Result |
|---|---|---|
| M0 | Walking skeleton | Installable offline PWA on the owner's phone (owner-led) |
| M1 | Foundations | Tooling, folders, `money`, `dates`, `period`, `ids` helpers with tests |
| M2 | Engine core | Types, `computeTree`, `bufferAllocation`, invariants, errors |
| M3 | Engine operations | All operations plus golden, unit and property tests |
| M4 | Persistence | Dexie schema, transaction wrapper, backup and restore |
| M5 | Importer | Parse, validate, transform, commit, with tests; review model |
| M6 | UI core | Overview, add spend, node detail, add balance, allocations, Buffer, history |
| M7 | Close period UI | Preview and confirm |
| M8 | Structure UI | Create, rename, tag, move, archive |
| M9 | Hardening | Verify screen, backup banner, storage persistence, device checklist |
| M10 | Later (not scheduled) | Reports, spend undo or edit, un-archive |

## M0 — Walking skeleton (owner-led)

Already described to the owner: Vite + React + TS project, `vite-plugin-pwa`, a throwaway Dexie "tap counter", `wrangler.jsonc`, deployed to a `*.workers.dev` URL, installed on Android, tested offline.
Done when: install works, airplane-mode reopen works, taps persist. The throwaway counter and its store are deleted in M1.

## M1 — Foundations

Tasks:
- Add `vitest`, `fake-indexeddb`, `fast-check`; `test` script; strict TypeScript; ESLint if the template has it.
- Create the folder layout from AGENTS.md. Remove the template demo code and the M0 smoke test.
- `src/engine/money.ts`: `parseRupees(input: string): Result<Paise>`, `formatINR(p: Paise)`, `plain(p: Paise)` (legacy `fmt` style), `MAX_ENTRY_PAISE`.
- `src/engine/dates.ts`: `todayLocal(now: Date)`, `isValidLocalDate`, `monthOf`, `isIsoInstant`. Also `src/engine/period.ts`: `nextPeriod`, `isPeriod`.
- IDs: a thin wrapper around `crypto.randomUUID()` that is injected, never called from engine code.
- Tests per TEST_PLAN section 3 (`lib` list).
Done when: all helper tests pass; no float arithmetic on money anywhere; build green.

## M2 — Engine core

Tasks: types from DATA_MODEL (`NodeRow` ... `EngineState`, `Writes`, `EngineError`), error constructors using the exact text in DOMAIN_RULES section 8,
`computeTree`, `bufferAllocation`, `verify(state, extra)` implementing I1 to I10 with readable issue messages.
Done when: unit tests cover derived values (DOMAIN_RULES section 3) and each invariant has a passing and a failing case.

## M3 — Engine operations

Tasks: `setAllocation`, `addBalance`, `addSpend`, `bufferWithdrawal`, `previewClosePeriod`, `closePeriod`, `createNode`, `updateNode`, `archiveNode`, per DOMAIN_RULES section 4 and 5.
Then the golden adapter and runner (TEST_PLAN section 2; first inspect and document the fixture structure), unit tests, and property tests.
Done when: all golden scenarios pass except the documented deviations; property tests P1 to P6 pass with a fixed seed and 200+ runs; every error code is tested.
Stop and ask if a golden scenario contradicts DOMAIN_RULES.

## M4 — Persistence

Tasks: `BudgetDB` per DATA_MODEL, `runOperation(op)` transaction wrapper (build `EngineState`, call engine, apply `Writes` or abort), `loadState`, queries for history by node and period, export, restore with safeguards, `meta` helpers, `navigator.storage.persist()` helper.
Done when: db tests in TEST_PLAN section 5 pass, including rollback and backup round trip.

## M5 — Importer

Tasks: pure `parse`, `validate`, `transform` functions and the review data model (editable balances, exclusion toggles), `commit` transaction, synthetic fixtures under `tests/fixtures/legacy-sample/`. UI wizard comes with M6 screens (S9).
Done when: import tests in IMPORT_SPEC section 7 pass and the committed state passes `verify`.

## M6 — UI core

Tasks: app shell and navigation (no path routes), S1 Overview, S2 Add spend (with confirmation sheet and Buffer cover note), S3 Node detail, add balance, S4 Allocations, S5 Buffer, S7 History, first-run screen (import or start empty), S9 import wizard UI.
UI rules: no budget logic in components; call `runOperation`; show errors with the catalog text next to the field; amount inputs use `inputmode="decimal"`; tap targets at least 44px.
Done when: the owner can import real data on the phone, review balances, add a past-dated spend offline, and see correct totals. Component tests for forms and error display (Vitest + DOM testing is optional; if added, record it in DECISIONS first).

## M7 — Close period UI

Tasks: S6 with the preview table (per leaf: surplus swept, credit, resulting balance), totals, confirmation reading "Close YYYY-MM and fund YYYY-MM", success summary, disabled while running to prevent double taps.
Done when: preview equals a hand calculation for a sample month; closing writes stats rows; the next period is open.

## M8 — Structure UI

Tasks: S8 tree editor with create, rename, tags, move, archive, and the guard messages from DOMAIN_RULES section 8; archive confirmation shows what will be settled with Buffer.
Done when: every guard from DOMAIN_RULES 4.6 is reachable and tested through the UI layer or its handlers.

## M9 — Hardening

Tasks: S9 Verify screen, S10 Settings (persistence status, last backup, version), backup banner after 14 days, empty and error states, performance check on a mid-range phone, accessibility pass, owner README (how to import, back up, update), TEST_PLAN device checklist executed by the owner.
Done when: the device checklist passes on the owner's phone and the owner has completed one real month including a close.

## M10 — Later (requires owner decision, see DECISIONS Q1, Q5, Q8)

Reports from `periodStats`, "undo last action" or spend edit/delete, un-archive.
