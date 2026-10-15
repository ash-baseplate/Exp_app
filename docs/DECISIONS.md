# DECISIONS

Status: DRAFT v0.1 (2026-10-14). Agents: add questions under "Open questions" and spec change requests under "Proposed spec changes". Do not edit DOMAIN_RULES or fixtures yourself to resolve them.

## 1. Decisions made

| Id | Decision | Why | Source |
|---|---|---|---|
| D-01 | Phone-only PWA for one user; the CLI is retired after a one-time import. No sync. | Owner uses the phone as the main tool; avoids sync complexity. | Owner |
| D-02 | Android Chrome, offline-first, data in IndexedDB via Dexie. | Offline requirement; data set is tiny; SQLite-WASM adds complexity for no gain. | Owner and design review |
| D-03 | Engine is pure TypeScript; storage is a thin transactional shell. | Testable against golden fixtures with no database. | Design |
| D-04 | Parent aggregates and Buffer allocation are derived on read, not stored. | Removes drift and the recompute-after-every-mutation step. | Design |
| D-05 | `spent` is derived per period and resets at close; history is kept in `periodStats`. | Owner: spent resets monthly and history is needed for reports. | Owner |
| D-06 | Cycle label means the month being closed; closing also funds the next month. Open period is stored explicitly and is the only closable period. | Owner clarified the meaning; removes free-text label errors. | Owner |
| D-07 | Spends carry a local `occurredOn`; the date must be inside the open period's month and not in the future. | Owner enters spends weekly and backdates within the month; blocks mis-tapped dates. | Owner and design |
| D-08 | Late spends affect current balances; the date is a record only (no retroactive correction of closed months). | Owner: the case never occurs; avoids a replay engine. | Owner |
| D-09 | Nodes are archived, never deleted. Archiving settles balance with Buffer and zeroes the allocation. | Legacy delete destroyed money and FKs were not enforced. | Audit |
| D-10 | Import has a mandatory review step with editable balances; the practice-run cycle lock is dropped and its sweep ledger rows are excluded. | Practice run altered balances; owner cross-checks by hand. | Owner |
| D-11 | The legacy seed tree is not shipped; fresh install is empty (import or start with Salary and Buffer). | The seed contains the owner's budget; the bundle is public. | Design |
| D-12 | Hosting: Cloudflare Workers static assets; the owner deploys with Wrangler. GitHub repo is private. | Root-path hosting avoids PWA base-path problems; free static requests. | Research |
| D-13 | No spend edit or delete in v1; a confirmation sheet guards entry instead. | Owner: fixes are rare and they are unsure. | Owner |
| D-14 | React is the UI library; no router, state library, UI kit or CSS framework without approval. | Largest ecosystem for a developer new to JS; the engine is framework-free. | Design |
| D-15 | Legacy ledger notes are kept verbatim for new rows so golden comparison stays exact. | Fidelity. | Design |

## 2. Open questions (agents must not resolve these alone)

| Id | Question | Default until answered | Who answers |
|---|---|---|---|
| Q1 | What should reports show: spend by category per month, budget versus actual per month, Buffer balance over time? | No reports in v1. `periodStats` keeps enough data for all three. | Owner |
| Q2 | What is the exact structure of `fixtures/golden-fixtures.json`? | Agent inspects the file in M3 and documents it in TEST_PLAN 2.1; if the file is absent, stop. | Owner supplies the file |
| Q3 | After the practice cycle, are the imported balances correct? | Treat them as possibly wrong; the review step makes the owner confirm or edit each. | Owner, at import time |
| Q4 | Timezone for converting legacy UTC instants to dates. | `Asia/Kolkata`, editable in the import wizard. | Owner |
| Q5 | Spend edit, delete or "undo last action" after v1? | Not built. Spends carry `spendId` links on ledger rows so reversal can be added cleanly. | Owner |
| Q6 | Is INR 1 crore (`MAX_ENTRY_PAISE = 1_000_000_000`) a suitable per-entry safety cap? | Yes. | Owner |
| Q7 | Restore and import replace data in bulk, which is an exception to "never destroy data". Confirm the safeguards (auto-export, typed confirmation). | Safeguards as specified in DATA_MODEL section 5. | Owner |
| Q8 | Is un-archive needed? | Not in v1. | Owner |
| Q9 | Backup reminder cadence. | 14 days since `lastExportAt`. | Owner |
| Q10 | The live database had 20 nodes while the old seed had 21. Which node differs? | Informational. Import uses the live data only. | Owner |
| Q11 | Does the live database contain orphan rows (see IMPORT_SPEC pre-import audit)? | Importer creates archived placeholder nodes and reports them. | Owner runs the audit |
| Q12 | Automatic deploys from Git (Cloudflare Workers Builds) later? | Manual `wrangler deploy` by the owner. | Owner |
| Q13 | Should the Buffer be allowed to move to a different parent? | Allowed if it remains a leaf and the new parent is valid. | Owner |

## 3. Proposed spec changes

(Empty. Agents append entries here as: date, section, current text, proposed text, reason. Wait for owner approval.)

## 4. Facts verified outside the code (as of 2026-10-14)

- Node.js LTS line is 24.x; Vite scaffolding command `npm create vite@latest -- <name> --template react-ts`.
- `vite-plugin-pwa` is added as a dev dependency and configured with `VitePWA({ registerType: 'autoUpdate' })`; a 512x512 icon is needed for installability.
- A static-only Cloudflare Workers deployment needs `wrangler.jsonc` with `name`, `compatibility_date`, and `assets.directory` (no `main`), deployed with `npx wrangler deploy`.
- These may change; re-verify against current docs before relying on them in a new session.
