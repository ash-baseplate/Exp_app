# IMPORT_SPEC — one-time import from the legacy CLI database

Status: DRAFT v0.1 (2026-10-14). Facts below come from audits of the legacy schema and the owner's confirmations.

## 1. Facts about the legacy database (from audits)

- SQLite file, default path `data/budget.db`, override with env var `BUDGET_DB_PATH`.
- Tables: `schema_version`, `nodes`, `spend_transactions`, `cycle_locks`, `ledger_events`.
- Money columns are integer minor units (paise): `allocated_amount_minor`, `spent_amount_minor`, `current_balance_minor`, `amount_minor`.
- `nodes.budget_tags` is a comma-separated string of enum names in declaration order, e.g. `SURPLUS_TO_BUFFER,DEFICIT_FROM_BUFFER`. Empty string means no tags.
- Two timestamp formats exist:
  - ISO-8601 UTC with `Z`: `spend_transactions.occurred_at`, `ledger_events.occurred_at`, `cycle_locks.closed_at`.
  - SQLite `CURRENT_TIMESTAMP` text `YYYY-MM-DD HH:MM:SS` (UTC, no `Z`): all `created_at`, `updated_at`, `applied_at`.
- `ledger_events.year_month` is always NULL. `cycle_locks.year_month` is free text.
- Foreign keys were NOT enforced in practice, so orphan references may exist.
- Live database: 20 nodes at last audit, 14 spends, 20 ledger rows, 1 cycle lock.
- The single cycle lock (`2026-10`) was a practice run, confirmed by the owner. It is dropped. The practice run also swept surpluses and credited one month of allocations into balances, and the credit left no ledger row.
- Legacy ids `salary` and `buffer` are hard-coded in the legacy engine and must survive import unchanged.

## 2. Producing the input files (owner, on a COPY of the database)

Requires the `sqlite3` shell, version 3.33 or newer (for `-json`).

```bash
cp data/budget.db /tmp/export.db
sqlite3 -json /tmp/export.db "SELECT * FROM nodes ORDER BY rowid;" > nodes.json
sqlite3 -json /tmp/export.db "SELECT * FROM spend_transactions ORDER BY occurred_at, created_at, id;" > spend_transactions.json
sqlite3 -json /tmp/export.db "SELECT * FROM ledger_events ORDER BY occurred_at, created_at, id;" > ledger_events.json
sqlite3 -json /tmp/export.db "SELECT * FROM cycle_locks;" > cycle_locks.json
```

An empty result may produce an empty file: the importer treats an empty file as `[]`. These files contain real financial data: never commit them.
The user transfers them to the phone however they like (cloud drive, USB) and picks all four with a multi-file picker in the wizard.

Pre-import audit (owner, optional but recommended), read-only:

```sql
PRAGMA foreign_key_check;   -- no rows = no orphans
SELECT n.id, n.spent_amount_minor AS stored, COALESCE(SUM(s.amount_minor),0) AS from_rows
FROM nodes n LEFT JOIN spend_transactions s ON s.node_id = n.id
WHERE n.id NOT IN (SELECT parent_id FROM nodes WHERE parent_id IS NOT NULL)
GROUP BY n.id
HAVING n.spent_amount_minor <> COALESCE(SUM(s.amount_minor),0);   -- no rows = consistent
```

## 3. Pipeline

`parse` then `validate` then `transform` then `review` (UI) then `commit`. The first three are pure functions in `src/db/` (or `src/import/`) with tests; `commit` is one Dexie transaction.

1. **Parse.** Each file: empty means `[]`; otherwise JSON array of objects. Required columns per table must exist, else a clear error naming file and column.
2. **Precondition.** The app database must be empty (no nodes). Otherwise refuse and point to Restore. Never merge.
3. **Validate** (collect all problems, do not stop at the first):
   - Integers in money columns; `amount_minor > 0` for spends and ledger rows.
   - `salary` and `buffer` exist; `salary.parent_id` is null; exactly one root.
   - Parent chain has no cycles; every `parent_id` exists.
   - Tags are valid enum names.
   - Timestamps match one of the two known formats.
   - Orphans: spends or ledger rows referencing unknown node ids (see 5).
   - Derived Buffer allocation: `salary.allocated - sum(allocated of leaf descendants of salary, excluding buffer)` should equal the stored buffer allocation; mismatch is a warning, not an error.
4. **Transform** (section 4), producing `NodeRow[]`, `SpendRow[]`, `LedgerRow[]` and a draft `ImportReport`.
5. **Review** (UI): see 6. Nothing is written before the owner confirms.
6. **Commit**: one transaction writing nodes, spends, ledger, `meta.openPeriod`, `meta.schemaVersion`, `meta.importReport`. No `cycles`, no `periodStats`. Then run Verify and show the result.

## 4. Mapping

**Timestamps.** `^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$` becomes `YYYY-MM-DDTHH:MM:SS.000Z` (replace the space with `T`, append `.000Z`; it is UTC). Strings already ISO with `Z` pass through. Anything else is a validation error.

**nodes.** `id` kept. `name` as is. `parentId = parent_id`. `tags = budget_tags.split(',')` trimmed, empty removed.
`allocated` and `balance`:
- Leaf non-Buffer: from `allocated_amount_minor` and `current_balance_minor`.
- Buffer: `balance` from `current_balance_minor`; `allocated = 0` (derived by the engine).
- Non-leaf: `balance = 0`; `allocated = 0`, except Salary which keeps `allocated_amount_minor`.
`spent_amount_minor` is discarded (spent is derived, D1). `archivedAt = null`. `createdAt` and `updatedAt` normalized from the legacy columns.

**spends.** `id`, `nodeId`, `amount`, `note`, `createdAt` (normalized `created_at`).
`occurredOn` = the calendar date of `occurred_at` in timezone `Asia/Kolkata` (default, owner can override in the wizard, DECISIONS Q4).
Use `Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' })`, which yields `YYYY-MM-DD`.

**ledger.** `type`, `actor`, `nodeId`, `sourceNodeId`, `destinationNodeId`, `amount`, `direction`, `note` map directly; `period = null`; `spendId = null`;
`occurredAt` is the legacy ISO instant; `createdAt` normalized.
Default exclusion: every row of type `SURPLUS_TO_BUFFER` is excluded, because only one cycle ever ran and it was a practice run. The review screen lists them with a toggle. The excluded ids and the reason go into the report.

**cycle_locks.** Dropped and recorded in `droppedCycleLocks`. `cycles` starts empty.

**openPeriod.** Defaults to the current local month; editable in the review step.

## 5. Orphans

If a spend or ledger row references a node id that does not exist, create an archived placeholder node (`name: "(deleted category)"`, `parentId: 'salary'`, zero values, `archivedAt = import time`) so history keeps integrity. List placeholders in the report and on the review screen. Do not silently drop rows.

## 6. Review screen (required)

Because the practice cycle changed balances, imported balances may not match reality, and a real close at month-end would then credit allocations on top. The review screen therefore shows:

- Every leaf and Buffer with the imported balance in an editable field and the original beside it.
- Allocations (read-only), the derived Buffer allocation, computed totals, and the open period.
- Counts: nodes, spends, ledger rows imported, ledger rows excluded, placeholders, warnings.
- A short warning explaining the practice-run effect and asking the owner to enter the real balances.
- Confirm button disabled while validation errors exist. Edits are stored in `balanceEdits` (original and final).

## 7. Tests (synthetic data only)

Create `tests/fixtures/legacy-sample/` with small synthetic JSON files using realistic formats (both timestamp formats, comma tags, an orphan spend, an empty `cycle_locks.json`).
Test: each timestamp format, tag splitting, IST date conversion near midnight (for example `2026-09-30T19:30:00.000Z` becomes `2026-10-01`),
leaf versus parent value handling, Buffer allocation warning, orphan placeholder, SURPLUS_TO_BUFFER exclusion, refusal on non-empty database,
atomic commit (forced failure leaves the database empty), and that the resulting state passes all invariants.
