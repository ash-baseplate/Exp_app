# DATA_MODEL

Status: DRAFT v0.1 (2026-10-14). Companion to DOMAIN_RULES (behaviour) and IMPORT_SPEC (legacy mapping).

## 1. Primitive types and formats

```ts
export type Paise = number;          // safe integer, never fractional. Negative only for leaf balances.
export type IsoInstant = string;     // 2026-10-14T09:30:00.000Z  (UTC, always ends in Z)
export type LocalDate = string;      // 2026-10-14  (user's local calendar date)
export type Period = string;         // 2026-10     (calendar month)
export type Tag = 'SURPLUS_TO_BUFFER' | 'DEFICIT_FROM_BUFFER';
```

- Dates never go through `toISOString()` to get a local date. `todayLocal()` uses `getFullYear/getMonth/getDate`.
- IDs for new rows: `crypto.randomUUID()` (secure context only, which an HTTPS PWA is). Legacy ids are kept verbatim on import.

## 2. Row types (IndexedDB records)

```ts
export interface NodeRow {
  id: string;                 // 'salary' and 'buffer' are reserved; legacy slugs kept; new = uuid
  name: string;
  parentId: string | null;    // null only for salary
  tags: Tag[];                // no duplicates
  allocated: Paise;           // meaningful only for leaves (not buffer) and for salary; else 0
  balance: Paise;             // meaningful only for leaves (incl. buffer); else 0
  archivedAt: IsoInstant | null;
  createdAt: IsoInstant;
  updatedAt: IsoInstant;
}

export interface SpendRow {
  id: string;
  nodeId: string;
  amount: Paise;              // > 0
  occurredOn: LocalDate;      // the date the owner chose
  note: string | null;        // trimmed; null if blank
  createdAt: IsoInstant;      // when it was entered
}

export type LedgerType =
  | 'ALLOCATION' | 'BALANCE_ADDITION' | 'SPEND' | 'BUFFER_RESIDUAL_ADJUSTMENT'
  | 'SURPLUS_TO_BUFFER' | 'DEFICIT_FROM_BUFFER' | 'BUFFER_WITHDRAWAL'   // legacy types
  | 'MONTHLY_CREDIT' | 'ARCHIVE_SWEEP';                                  // new types

export interface LedgerRow {
  id: string;
  type: LedgerType;
  actor: 'USER' | 'SYSTEM';
  nodeId: string | null;
  sourceNodeId: string | null;
  destinationNodeId: string | null;
  amount: Paise;              // > 0
  direction: 'CREDIT' | 'DEBIT';   // from the point of view of nodeId
  period: Period | null;      // null for imported legacy rows
  occurredAt: IsoInstant;
  spendId: string | null;     // set on SPEND and DEFICIT_FROM_BUFFER rows created by this app
  note: string | null;
  createdAt: IsoInstant;
}

export interface CycleRow { period: Period; closedAt: IsoInstant; }   // one per closed period

export interface PeriodStatRow {
  period: Period;
  nodeId: string;
  allocationAtClose: Paise;   // allocation in force when the period was closed (it funds the NEXT period)
  spent: Paise;               // spends dated in `period`
  balanceBeforeSweep: Paise;
  sweptToBuffer: Paise;       // leaf: amount swept out. buffer: total swept in.
  creditedForNext: Paise;
  closingBalance: Paise;      // after sweep and credit
}

export interface MetaRow { key: string; value: unknown; }
// Keys: 'openPeriod' (Period), 'schemaVersion' (number), 'importReport' (ImportReport),
//       'lastExportAt' (IsoInstant), 'settings' (object, reserved)
```

Budget for period P (for future reports) = `creditedForNext` of the stat row for the previous period; this is undefined for the first period after import.

## 3. Engine state and writes

```ts
export interface EngineState {
  nodes: NodeRow[];             // all, including archived
  spends: SpendRow[];           // only spends with occurredOn inside openPeriod
  openPeriod: Period;
  closedPeriods: Period[];
  historyNodeIds: string[];     // node ids referenced by ANY spend or ledger row (any period); used by node-guard rules
}

export interface Writes {
  nodes: NodeRow[];             // upsert by id
  spends: SpendRow[];           // add
  ledger: LedgerRow[];          // add
  cycles: CycleRow[];           // add
  periodStats: PeriodStatRow[]; // add
  meta: MetaRow[];              // upsert by key
}

export type ErrorCode =
  | 'AMOUNT_INVALID' | 'AMOUNT_TOO_LARGE' | 'NODE_NOT_FOUND' | 'NODE_ARCHIVED' | 'NOT_A_LEAF'
  | 'BUFFER_NOT_ALLOWED' | 'ALLOCATION_NOT_ALLOWED' | 'BUFFER_NEGATIVE' | 'INSUFFICIENT_BUFFER'
  | 'DATE_INVALID' | 'DATE_FUTURE' | 'DATE_OUT_OF_PERIOD' | 'PERIOD_NOT_OPEN' | 'ROLE_PROTECTED'
  | 'PARENT_INVALID' | 'PARENT_NOT_EMPTY' | 'NAME_INVALID' | 'TAG_INVALID' | 'STATE_INVALID';

export interface EngineError { code: ErrorCode; message: string; details?: Record<string, unknown>; }
```

The db layer builds `EngineState` inside the same Dexie transaction that applies `Writes`:
load all nodes, the spends in `[openPeriod-01, openPeriod-31]` (string range on `occurredOn`), cycles, meta, and `historyNodeIds`.

## 4. Dexie schema

```ts
export class BudgetDB extends Dexie {
  nodes!: Table<NodeRow, string>;
  spends!: Table<SpendRow, string>;
  ledger!: Table<LedgerRow, string>;
  cycles!: Table<CycleRow, string>;
  periodStats!: Table<PeriodStatRow, [string, string]>;
  meta!: Table<MetaRow, string>;
  constructor() {
    super('budget');
    this.version(1).stores({
      nodes: 'id, parentId',
      spends: 'id, nodeId, occurredOn, [nodeId+occurredOn]',
      ledger: 'id, type, nodeId, spendId, period, occurredAt',
      cycles: 'period',
      periodStats: '[period+nodeId], period, nodeId',
      meta: 'key',
    });
  }
}
```

Rules:
- Never edit an existing `version(n)` block. Schema changes add `version(n+1)` with an `upgrade` function and a test that opens a database created at the old version.
- IndexedDB cannot index `null` or booleans. Nullable fields (`parentId`, `archivedAt`, `spendId`) are filtered in memory or by non-null range queries; the nodes table is tiny.
- Every user operation runs as `db.transaction('rw', db.nodes, db.spends, db.ledger, db.cycles, db.periodStats, db.meta, ...)`:
  load state, call the engine, if `ok` apply `Writes`, else abort by throwing a private sentinel and return the `EngineError`. No non-Dexie awaits inside.

## 5. Backup file format

```json
{
  "app": "budget-pwa",
  "formatVersion": 1,
  "exportedAt": "2026-10-14T09:30:00.000Z",
  "data": {
    "nodes": [], "spends": [], "ledger": [], "cycles": [], "periodStats": [],
    "meta": [{ "key": "openPeriod", "value": "2026-10" }]
  }
}
```

- Export reads all stores in one read transaction and sets `meta.lastExportAt` afterwards.
- Restore validates `app`, `formatVersion`, and runs the invariants from DOMAIN_RULES section 6 on the incoming data BEFORE touching the database.
  It then exports the current data automatically, requires typed confirmation, and replaces all stores in one transaction.
- Backup files contain personal financial data. They must never be committed (see `.gitignore` rules in AGENTS.md).

## 6. Import report (stored in `meta.importReport`)

```ts
export interface ImportReport {
  importedAt: IsoInstant;
  sourceCounts: { nodes: number; spends: number; ledger: number; cycleLocks: number };
  excludedLedgerIds: string[];                 // rows left out and why: see `excludedReason`
  excludedReason: string;
  droppedCycleLocks: { yearMonth: string; closedAt: string }[];
  balanceEdits: { nodeId: string; original: Paise; final: Paise }[];
  placeholderNodeIds: string[];                // archived stand-ins created for orphan references
  openPeriod: Period;
  timezoneUsedForLegacyDates: string;          // default 'Asia/Kolkata'
}
```
