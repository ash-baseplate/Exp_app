# TEST_PLAN

Status: DRAFT v0.1 (2026-10-14). Correctness of the engine is proven three ways: golden fixtures from the real Java code,
property tests of the invariants, and database-level tests. The UI is verified on a real phone.

## 1. Tooling

Vitest, `fake-indexeddb` (db tests), `fast-check` (property tests). Folders: `tests/golden/`, `tests/engine/`, `tests/db/`, `tests/import/`, `tests/property/`.
A `test` script in `package.json` runs everything once (`vitest run`). Engine and lib tests must not need a browser.

## 2. Golden fixtures

File: `fixtures/golden-fixtures.json` (read-only; produced by running the real Java classes). 15 scenarios, each against a fresh temp database.
Contents per scenario: input steps; failure messages; full before/after of `nodes`, `spend_transactions`, `ledger_events`, `cycle_locks`.
UUIDs are placeholders `<uuid-N>` and timestamps `<ts>`; all names and notes are synthetic.

### 2.1 Fixture structure (FILL IN)

The exact JSON structure has not been shared with the spec author. First task of M3: inspect the file, then replace this subsection with
the real structure (top-level shape, scenario keys, how steps and failures are encoded). Do not guess. If the file is missing, stop and ask the owner.
Owner TODO: provide the file and the output of `wc -c fixtures/golden-fixtures.json`.

### 2.2 Adapter rules (`tests/golden/adapter.ts`)

- Legacy rows use `*_minor` integers, which equal paise. Load `nodes` into `NodeRow`: for non-leaf nodes and Buffer's allocation, ignore the stored values (D12).
- Legacy spends have no business date. Give each loaded spend `occurredOn = '<openPeriod>-01'`, and set `ctx.todayLocal` to a date inside the period.
  For scenarios with two closes (12), spends made before the first close get a date in period 1 and spends after it get a date in period 2.
- Compare the engine's computed tree (DOMAIN_RULES section 3) to the legacy `allocated`, `spent`, `balance` of every node. Ignore `spent` after any close (D1).
- Compare emitted ledger rows after normalization: ignore `id`, timestamps, `period`, `spendId`; compare `type, actor, nodeId, sourceNodeId, destinationNodeId, amount, direction`, and compare `note` for rows whose note is a fixed string or the ALLOCATION note (DOMAIN_RULES section 5).
  Legacy rows `year_month` NULL versus `period` set is expected (D2). Ignore the new `MONTHLY_CREDIT` rows when matching legacy rows, but assert separately that they exist, one per leaf with a non-zero allocation.
- Compare failures by error code through the mapping at the end of DOMAIN_RULES section 8, not by text.
- A failing scenario step must leave state and writes unchanged.

### 2.3 Scenarios and expectations

The scenario tree is the fixture's own small synthetic tree (Salary with a few leaves plus Buffer); read it from the file.

| # | Scenario | Legacy result | New expectation |
|---|---|---|---|
| 1 | Set Salary and leaf allocations | OK | Match, including Buffer residual rows |
| 2 | Leaf allocations exceed Salary | Fails: Buffer would go negative | BUFFER_NEGATIVE |
| 3 | Add balance to a leaf and to Buffer | OK | Match |
| 4 | Spend within balance | OK | Match |
| 5 | Overspend on a DEFICIT_FROM_BUFFER leaf | OK; Buffer funds the shortfall | Match, two ledger rows |
| 6 | Overspend with insufficient Buffer | Fails; nothing persisted | INSUFFICIENT_BUFFER; no spend, ledger or node change |
| 7 | Overspend on an untagged leaf | OK; balance negative | Match (balance negative) |
| 8 | Buffer withdrawal | OK | Match |
| 9 | Withdrawal above Buffer balance | Fails | INSUFFICIENT_BUFFER |
| 10 | Close with surplus on a tagged leaf | OK | Match balances and sweep rows; plus MONTHLY_CREDIT rows (D2) |
| 11 | Same cycle twice | Second is a no-op | Second `closePeriod(sameMonth)` returns PERIOD_NOT_OPEN; state unchanged (D3) |
| 12 | Two consecutive closes with spends between | OK; legacy `spent` is lifetime | Balances match; `spent` is per period (D1) |
| 13 | Move a node to a new parent | OK | Match aggregates (new guard rules must not block this fixture's move; if they do, stop and ask) |
| 14 | Delete a leaf with spend history | Succeeded and left orphan spend rows (FK not enforced) | Not compared. Replaced by archive tests (section 3) (D4) |
| 15 | Amount with 3 decimals | Fails: "Rounding necessary" | `parseRupees` rejects; engine rejects non-integers (D10) |

## 3. Engine unit tests

Every branch of DOMAIN_RULES section 4 and every error code has at least one test. Additional required tests:

- `computeTree`: leaf, parent, Salary, Buffer allocation, archived exclusion, deficit, spent per period.
- `setAllocation`: delta signs, zero delta no-op, Salary versus leaf effect on Buffer, rejection when Buffer would go negative.
- `addSpend`: tagged shortfall, untagged negative, date rules (invalid, future, other month, first and last day of the period), note trimming.
- `closePeriod`: sweep only tagged and positive balances, Buffer credited, Buffer excluded from sweep, preview equals apply, stats rows (including archived leaf with spends), `openPeriod` advances across a year boundary.
- Node operations: every guard in 4.6 (create under non-empty leaf, move into a descendant, move that makes Buffer negative, archive with positive and negative balance, Salary and Buffer protected).
- Archive: allocation set to zero with residual row, balance settled with Buffer, node hidden, spends and ledger preserved, history kept in `historyNodeIds`.
- `lib`: `parseRupees` (valid, empty, `1.`, `.5`, `1.234`, negative, exponent, commas, whitespace, over the cap), `formatINR` (lakh and crore grouping, negatives, zero), `todayLocal` near midnight with a mocked timezone, `nextPeriod` year rollover.

## 4. Property tests (`fast-check`)

Generate random valid operation sequences over a random valid tree. After every step assert I1 to I10 from DOMAIN_RULES section 6, plus:

- P1 A failed operation returns the same state it received (deep equal) and no writes.
- P2 Spend on a `DEFICIT_FROM_BUFFER` leaf never leaves that leaf negative; Buffer decreases by exactly the shortfall.
- P3 `closePeriod`: sum of leaf balances after = sum before + sum of leaf `allocated` (sweeps only move money between leaves), and Salary's computed balance equals the sum of leaf balances.
- P4 `previewClosePeriod` is pure (state deep-equals before and after) and equals what `closePeriod` writes.
- P5 `bufferAllocation >= 0` after every successful operation.
- P6 Sum over ledger deltas is consistent with balance changes for the operations that emit rows (SPEND, BALANCE_ADDITION, BUFFER_WITHDRAWAL, DEFICIT_FROM_BUFFER, SURPLUS_TO_BUFFER, MONTHLY_CREDIT, ARCHIVE_SWEEP).

## 5. Database tests (`fake-indexeddb`)

- Every operation applied through the transaction wrapper persists exactly its `Writes`.
- Forced failure inside the transaction (throw after the first write) leaves every store unchanged.
- Schema upgrade test: create a v1 database, open with the current schema, data intact (add when `version(2)` first appears).
- Backup round trip: export then restore into an empty database yields identical stores. Restore rejects a file with a broken invariant and leaves the database untouched.
- `historyNodeIds` includes ids referenced as `nodeId`, `sourceNodeId` or `destinationNodeId` in ledger rows, and `nodeId` of spends.

## 6. Import tests

See IMPORT_SPEC section 7. All inputs are synthetic.

## 7. Device checklist (owner, real Android phone, production URL)

1. Install from Chrome; open from the home screen; the app title and icon are correct.
2. Airplane mode on; force-close; reopen: loads, data intact.
3. Add a spend with a past date inside the month; confirm the confirmation sheet; check balances and Spent.
4. Try a date in another month and a future date: both rejected with the documented messages.
5. Overspend a tagged leaf: confirmation mentions Buffer cover; result matches DOMAIN_RULES 4.3.
6. Close period: preview matches a hand calculation; after confirm the new period is open and Spent is zero.
7. Export backup, restore it on a cleared browser profile or a second device, compare the Overview totals.
8. Run Verify: no problems.
9. Update flow: deploy a visible change, open the app online, reopen it: new version appears, data intact.
10. Storage: Settings shows whether persistent storage was granted.

## 8. Exit criteria per milestone

A milestone is done only when its tests are green, the production build is green, and the report lists the exact commands and output.
