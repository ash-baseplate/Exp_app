# AGENTS.md

Instructions for AI coding agents working in this repository. Read this file first, then the docs in the order under **Read order**.

## Project

Offline-first, single-user budgeting PWA for an Android phone. It replaces a Java CLI that stored data in SQLite. All data lives on the device (IndexedDB). Currency is INR. There is no server, no account system and no sync.

The budget logic (hierarchical nodes, a Salary root, a Buffer that absorbs surpluses and deficits, manual month-end cycles) is being ported from the Java CLI. **Behaviour must match `docs/DOMAIN_RULES.md` and the golden fixtures in `fixtures/`**, except for the deviations listed in DOMAIN_RULES section 7.

## Read order

1. `docs/PRODUCT_SPEC.md` - scope and screens
2. `docs/DOMAIN_RULES.md` - the engine specification (most important)
3. `docs/DATA_MODEL.md` - types, stores, invariants
4. `docs/IMPORT_SPEC.md` - one-time import from the CLI database
5. `docs/TEST_PLAN.md` - how correctness is proven
6. `docs/BUILD_PLAN.md` - milestones; work in this order
7. `docs/DECISIONS.md` - decisions made and questions still open

## Stack (fixed)

- Vite + React + TypeScript with `strict` on
- Dexie for IndexedDB
- vite-plugin-pwa with `registerType: 'autoUpdate'`
- Vitest for tests
- Hosting: Cloudflare Workers static assets (`wrangler.jsonc`, `assets.directory` = `./dist`)

Extra dependencies need approval, except `fake-indexeddb` and `fast-check` as dev dependencies for tests. Do not add frameworks, state libraries, UI kits, routers or CSS frameworks without asking.

## Commands

```bash
npm install
npm run dev          # local dev server
npm run build        # production build (type-checks first)
npm run preview      # serves the production build; the service worker only exists here
npm run test         # vitest run (add this script if the template lacks it)
npm run lint
```

The human deploys. **Never run `wrangler login` or `wrangler deploy`.**

## Hard rules

1. **Money is integer paise.** Never floats. Parse typed amounts from strings; never `parseFloat(x) * 100`. Reject more than 2 decimal places. Display with `Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' })`.
2. **The engine is pure.** Code in `src/engine/` must not import React, Dexie or browser APIs, and must not call `Date.now()`, `new Date()` without arguments, `Math.random()` or `crypto`. Inject `now`, `todayLocal` and `newId` through a `ctx` parameter.
3. **One operation, one transaction.** The engine returns the new state plus the rows to write; `src/db/` writes them inside a single Dexie transaction. A failing operation must leave the database unchanged.
4. **Dates.** Spend dates are local calendar dates (`YYYY-MM-DD`). Instants are ISO-8601 UTC strings ending in `Z`. Never mix them.
5. **No personal data in the repo or the bundle.** No seed budget in the app. Fixtures are synthetic. Never commit database exports or backups.
6. **Never destroy data.** No hard-deleting nodes, spends or ledger rows. Nodes are archived.
7. **IndexedDB only** for financial data. No localStorage or sessionStorage for budget data.
8. **Fully offline at runtime.** No network calls, CDNs, remote fonts or analytics.
9. **Do not guess domain behaviour.** If a rule is missing or ambiguous, add it to "Open questions" in `docs/DECISIONS.md` and ask the human. Do not edit `docs/DOMAIN_RULES.md` or a fixture to make a failing test pass without explicit approval.
10. **`fixtures/golden-fixtures.json` is read-only.**

## Layout

```
src/
  engine/      pure logic: types, money, compute, operations, invariants
  db/          Dexie schema, transaction wrapper, importer, backup/restore
  ui/          React screens and components
tests/
  golden/      adapter and golden-fixture tests
  engine/  db/  import/
fixtures/
  golden-fixtures.json
docs/
wrangler.jsonc
```

## Working agreement

- Work milestone by milestone from `docs/BUILD_PLAN.md`. Finish and verify one before starting the next.
- Before finishing a milestone run `npm run build`, `npm run test` and `npm run lint`, and report the results honestly. Never claim something was tested, deployed or verified unless it was.
- Keep changes small and reviewable. No unrelated refactors, no unrequested features.
- When a decision changes, update `docs/DECISIONS.md` in the same change.
- State assumptions explicitly in your summary.

## Stop and ask the human when

- A rule in `docs/DOMAIN_RULES.md` conflicts with a golden fixture
- You need a dependency that is not listed above
- The fixture file's structure is unclear (see `docs/TEST_PLAN.md`)
- Anything involves real data, deployment, credentials or the live database

## Conventions

- TypeScript strict; no `any` for money or domain rows; prefer discriminated unions for results.
- Money type is `Paise` (a safe integer `number`). Use the helpers in `src/engine/money.ts`.
- Engine operations have the shape `(state, input, ctx) => Result`, where `Result` is `{ ok: true, state, writes, message? } | { ok: false, error }`. Error messages use the exact text in `docs/DOMAIN_RULES.md` section 8.
- The UI contains no budget logic; it calls engine operations through `src/db/`.
- Mobile first: tap targets of at least 44px and `inputmode="decimal"` for amount fields.
