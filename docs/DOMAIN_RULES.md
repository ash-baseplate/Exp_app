# DOMAIN_RULES — engine specification (source of truth for behaviour)

Status: DRAFT v0.1 (2026-10-14). Derived from the audit of the legacy Java CLI (BudgetCliApp, NodeRepository,
LedgerEventRepository, SpendRepository), 15 golden scenarios, and owner decisions. Where this document deliberately
differs from the legacy CLI, the difference is listed in section 7 (D-numbers). Section 8 holds the exact user-facing error text.

## 1. Conventions

- Amount: integer paise, a safe-integer `number`. 1 INR = 100. Never fractional.
- Period: `YYYY-MM`. `monthOf(date) = date.slice(0, 7)`. `nextPeriod('2026-12') = '2027-01'`.
- Calendar date: `YYYY-MM-DD`, local. Instant: ISO-8601 UTC ending in `Z`.
- `ctx = { now: string /* instant */, todayLocal: string /* YYYY-MM-DD */, newId: () => string }`. Nothing else is non-deterministic.
- Engine operation signature: `(state, input, ctx) => Result`.
  `Result = { ok: true; state: EngineState; writes: Writes; message?: string } | { ok: false; error: EngineError }`.
  `EngineError = { code: ErrorCode; message: string; details?: Record<string, unknown> }`. Types live in DATA_MODEL.
- Operations never mutate their inputs. On `ok: false` nothing is written.
- `MAX_ENTRY_PAISE = 1_000_000_000` (INR 1 crore) is a typo guard for single entries. The owner can change it (DECISIONS Q6).
- Emitted rows are listed in the order they must be created.
- All amounts passed to engine operations are re-validated by the engine even if the UI already validated them.

## 2. Entities and roles

- Node fields: `id, name, parentId, tags[], allocated, balance, archivedAt` (see DATA_MODEL).
- Reserved ids: `salary` (the single root) and `buffer`. They cannot be archived, deleted, or re-identified.
- Tags: `SURPLUS_TO_BUFFER`, `DEFICIT_FROM_BUFFER`. Only these two exist. Tags on non-leaf nodes are inert but preserved.
- `children(n)` = non-archived nodes whose `parentId` is `n.id`. `isLeaf(n)` = `children(n)` is empty. Archived nodes are excluded from the tree and from every calculation below.
- Stored vs derived: only leaf `allocated` (except Buffer), leaf `balance`, and `salary.allocated` are meaningful stored values.
  For non-leaf nodes and for Buffer's `allocated`, stored values are ignored by the engine and kept at 0 (D12).

## 3. Derived values (`computeTree`)

For every non-archived node:

- Leaf (not Buffer): `allocated` and `balance` as stored.
- Buffer: `allocated = bufferAllocation(state)`; `balance` as stored.
- Non-leaf: `balance = sum(children.balance)`; `spent = sum(children.spent)`;
  `allocated = salary.allocated` if the node is Salary, else `sum(children.allocated)`.
- Leaf `spent` = sum of spends of that node whose `occurredOn` is inside the open period (D1).
- `deficit = max(0, spent - allocated)`.

`bufferAllocation(state) = salary.allocated - sum(allocated of every non-archived leaf that is a descendant of Salary and is not Buffer)`.
If the result is negative the state is invalid (STATE_INVALID) and no operation may produce it (BUFFER_NEGATIVE).
Buffer's own allocation is included in the sums of its ancestors. There is no rounding anywhere in the engine.

## 4. Operations

Checks run in the listed order; the first failure wins.

### 4.1 setAllocation `{ nodeId, amount }`
Replaces (does not add to) the allocation of Salary or a leaf. `amount >= 0` (zero allowed).
1. `amount` is a safe integer, `0 <= amount <= MAX_ENTRY_PAISE`, else AMOUNT_INVALID.
2. Node exists, else NODE_NOT_FOUND; not archived, else NODE_ARCHIVED.
3. `nodeId !== 'buffer'`, else BUFFER_NOT_ALLOWED.
4. Node is Salary or a leaf, else ALLOCATION_NOT_ALLOWED.
5. `delta = amount - current`. `projectedBuffer = bufferAllocation + delta` if Salary, else `bufferAllocation - delta`. If `projectedBuffer < 0`, BUFFER_NEGATIVE (details: `available = bufferAllocation`).
Effects: store the new allocation. If `delta === 0` the operation succeeds with message "No change" and emits nothing.
Emit: `ALLOCATION` (section 5), then `BUFFER_RESIDUAL_ADJUSTMENT` if Buffer's derived allocation changed.
Balances are not touched. The new allocation only affects the next close period credit.

### 4.2 addBalance `{ nodeId, amount }`
1. `1 <= amount <= MAX_ENTRY_PAISE`, else AMOUNT_INVALID.
2. Node exists and is not archived.
3. Node is Buffer or a leaf, else NOT_A_LEAF.
Effects: `balance += amount`. Emit `BALANCE_ADDITION`.

### 4.3 addSpend `{ nodeId, amount, occurredOn, note? }`
1. `1 <= amount <= MAX_ENTRY_PAISE`, else AMOUNT_INVALID.
2. Node exists, not archived.
3. `nodeId !== 'buffer'`, else BUFFER_NOT_ALLOWED. Node is a leaf, else NOT_A_LEAF.
4. `occurredOn` is a real calendar date `YYYY-MM-DD`, else DATE_INVALID; `occurredOn <= todayLocal`, else DATE_FUTURE;
   `monthOf(occurredOn) === openPeriod`, else DATE_OUT_OF_PERIOD (D8).
5. `projected = node.balance - amount`. If `projected < 0` and the node has `DEFICIT_FROM_BUFFER`: `shortfall = -projected`; if `buffer.balance < shortfall`, INSUFFICIENT_BUFFER.
Effects:
- Create a spend row `{ id: newId(), nodeId, amount, occurredOn, note: trimmed or null, createdAt: now }`.
- `node.balance -= amount`.
- If the node has `DEFICIT_FROM_BUFFER` and `node.balance < 0`: `shortfall = -node.balance; buffer.balance -= shortfall; node.balance = 0`.
- A node without `DEFICIT_FROM_BUFFER` may go negative. This matches legacy and is intentional.
Emit: `SPEND`; and, if `shortfall > 0`, `DEFICIT_FROM_BUFFER`. Both carry `spendId`.

### 4.4 bufferWithdrawal `{ destinationId, amount }`
Moves money from Buffer to a leaf.
1. `1 <= amount <= MAX_ENTRY_PAISE`.
2. Destination exists, not archived; is not Buffer (BUFFER_NOT_ALLOWED); is a leaf (NOT_A_LEAF, D5).
3. `buffer.balance >= amount`, else INSUFFICIENT_BUFFER.
Effects: `buffer.balance -= amount; destination.balance += amount`. Emit `BUFFER_WITHDRAWAL`.

### 4.5 previewClosePeriod / closePeriod `{ period }`
`closePeriod` applies exactly what `previewClosePeriod` computes (one shared code path). The preview mutates nothing.
Precondition: `period === openPeriod` and `period` is not in `closedPeriods`, else PERIOD_NOT_OPEN (D3).
Let `next = nextPeriod(period)`. `leaves` = non-archived leaves in stable order (createdAt, then id), including Buffer.
Algorithm (single transaction):
1. Snapshot, per leaf: `allocationAtClose = allocated`, `spent` (for `period`), `balanceBeforeSweep = balance`.
2. Sweep: for each leaf with `id !== 'buffer'`, tag `SURPLUS_TO_BUFFER`, and `balance > 0`:
   `swept = balance; balance = 0; buffer.balance += swept`. Emit `SURPLUS_TO_BUFFER` (period = `period`).
3. Credit: for every leaf including Buffer: `c = allocated` (Buffer uses `bufferAllocation`, which the sweep does not change);
   `balance += c`; if `c > 0` emit `MONTHLY_CREDIT` (period = `next`). Tags do not matter for credit.
4. Stats: per leaf write `PeriodStat { period, nodeId, allocationAtClose, spent, balanceBeforeSweep, sweptToBuffer, creditedForNext, closingBalance }`.
   `sweptToBuffer` is the amount swept out of a leaf; for Buffer it is the total swept in. `closingBalance` is after the credit.
   Also write a row for every archived leaf that has spends in `period` (all amounts zero except `spent`).
5. Write a cycle row `{ period, closedAt: now }`; set `meta.openPeriod = next`.
Order of the legacy cycle was: sweep, then credit, then lock. Keep it.

### 4.6 Node operations

**createNode `{ name, parentId, tags }`**
1. Name trimmed, 1 to 60 chars, else NAME_INVALID. Tags subset of the two valid tags, else TAG_INVALID.
2. Parent exists, is not archived, is not Buffer, else PARENT_INVALID.
3. If the parent is a leaf, it must be empty: stored `allocated = 0`, `balance = 0`, and `id` not in `state.historyNodeIds`; else PARENT_NOT_EMPTY. (After creation it becomes a non-leaf.)
4. Create `{ id: newId(), allocated: 0, balance: 0, archivedAt: null, ... }`. No ledger rows.

**updateNode `{ nodeId, name?, tags?, parentId? }`**
- Node exists and is not archived. Salary may be renamed but not moved (ROLE_PROTECTED).
- Rename and tags: same validation as create. Buffer may be renamed; its tags are ignored.
- Move to `parentId`: parent exists, not archived, not the node itself, not a descendant of the node, not Buffer (PARENT_INVALID, details.reason);
  if the new parent is a leaf it must be empty (PARENT_NOT_EMPTY). After the move `bufferAllocation >= 0`, else BUFFER_NEGATIVE.
  If Buffer's derived allocation changes because of the move, emit `BUFFER_RESIDUAL_ADJUSTMENT` with `sourceNodeId = moved node`.
- Moving a node moves its balances and spends with it (they are derived).

**archiveNode `{ nodeId }`** (replaces legacy delete, D4)
1. Node is not Salary or Buffer (ROLE_PROTECTED) and is a leaf (NOT_A_LEAF).
2. If `allocated !== 0`: perform `setAllocation` to 0 (emits `ALLOCATION` and `BUFFER_RESIDUAL_ADJUSTMENT`).
3. If `balance > 0`: `buffer.balance += balance`, emit `ARCHIVE_SWEEP` (node to Buffer). If `balance < 0`: Buffer must hold `-balance`, else INSUFFICIENT_BUFFER; `buffer.balance -= -balance`, emit `ARCHIVE_SWEEP` (Buffer to node). Then `balance = 0`.
4. Set `archivedAt = now`. Rows are never deleted. There is no un-archive in v1.
Note: archiving removes the node's spends from parent totals of the open period (archived nodes are excluded from the tree). Closing the period still records them in `periodStats`.

## 5. Ledger emission

`direction` is expressed from the point of view of `nodeId`. `actor` is `USER` for user-initiated rows, `SYSTEM` for consequences.
All new rows: `occurredAt = ctx.now`, `createdAt = ctx.now`, `amount > 0`. `period` is the accounting period of the row (see table).
Legacy note strings are kept verbatim so golden comparison stays exact. `plain(x)` = integer paise formatted as `X.XX`, no grouping, no currency symbol (legacy `fmt`).

| Type | Actor | nodeId | sourceNodeId | destinationNodeId | direction | amount | period | spendId | note |
|---|---|---|---|---|---|---|---|---|---|
| ALLOCATION | USER | the node | null | null | CREDIT if delta > 0 else DEBIT | abs(delta) | openPeriod | null | `User allocation set to ${plain(new)}` |
| BUFFER_RESIDUAL_ADJUSTMENT | SYSTEM | buffer | the changed node | buffer | CREDIT if buffer delta > 0 else DEBIT | abs(buffer delta) | openPeriod | null | `Residual adjusted after allocation change` |
| BALANCE_ADDITION | USER | the node | null | the node | CREDIT | amount | openPeriod | null | `User-added available balance` |
| SPEND | USER | the node | the node | null | DEBIT | amount | openPeriod | spend id | the spend note (may be null) |
| DEFICIT_FROM_BUFFER | SYSTEM | the node | buffer | the node | CREDIT | shortfall | openPeriod | spend id | `Automatic Buffer funding` |
| BUFFER_WITHDRAWAL | USER | destination | buffer | destination | CREDIT | amount | openPeriod | null | `Manual Buffer withdrawal` |
| SURPLUS_TO_BUFFER | SYSTEM | the leaf | the leaf | buffer | DEBIT | swept | closing period | null | `Month-end surplus sweep` |
| MONTHLY_CREDIT (new, D2) | SYSTEM | the leaf | null | the leaf | CREDIT | allocated | next period | null | `Monthly allocation credit` |
| ARCHIVE_SWEEP (new) | USER | the node | node or buffer | buffer or node | DEBIT (to Buffer) / CREDIT (from Buffer) | abs(balance) | openPeriod | null | `Balance settled with Buffer on archive` |

Legacy rows imported from the CLI keep their original type names and have `period = null`.

## 6. Invariants (used by Verify and by property tests)

- I1 Exactly one root, `salary`; `buffer` exists, is not archived, is a leaf, and its `parentId` refers to a non-archived node.
- I2 Every non-archived node's parent exists and is not archived; the parent chain has no cycles.
- I3 `bufferAllocation >= 0`.
- I4 For non-leaf nodes and for Buffer, stored `allocated` is 0, and for non-leaf nodes stored `balance` is 0.
- I5 Every spend references an existing node (archived allowed), has `amount > 0`, a valid `occurredOn`, and a `createdAt` instant.
- I6 Every ledger row has `amount > 0`, a valid type and direction, and any node reference exists.
- I7 `openPeriod` matches `^\d{4}-(0[1-9]|1[0-2])$`, is not in `closedPeriods`, and equals `nextPeriod(max(closedPeriods))` when any period has been closed.
- I8 For every period closed by this app, `PeriodStat.spent` equals the sum of that node's spends dated in that period.
- I9 No node is hard-deleted: every node id referenced by a spend, ledger row or stat exists in the nodes table.
- I10 Tags only contain valid tag names, without duplicates.

## 7. Deviations from the legacy CLI (intended; golden tests must account for each)

| Id | Legacy behaviour | New behaviour |
|---|---|---|
| D1 | `spent` is a stored lifetime counter, never reset (so `deficit` compares lifetime spend with a monthly allocation). | `spent` is derived per period from dated spends and is zero after a close. Balances are unaffected. |
| D2 | Monthly allocation credit wrote no ledger row; `year_month` was always null. | `MONTHLY_CREDIT` rows are written; ledger rows carry `period`. |
| D3 | Cycle took a free-text label; a repeat returned a "no-op" message. | Only the open period can be closed; a repeat is PERIOD_NOT_OPEN. End state is identical. |
| D4 | Delete removed the node. Foreign keys were not actually enforced (golden scenario 14: orphan spend rows remained), and the node's balance silently vanished from totals. | Archive. History is kept; balance is settled with Buffer. |
| D5 | Buffer withdrawal accepted any destination, including parents, where the money vanished into overwritten aggregates. | Destination must be a leaf. |
| D6 | Adding a child to a leaf overwrote the leaf's own values; only Salary was protected from deletion. | Guard rules in 4.6. Salary and Buffer are protected. |
| D7 | Transfer, node create/update/delete and clear were not atomic. | Every operation is one transaction. |
| D8 | Spends were always stamped now; no date input. | Spends carry `occurredOn` with the date rules in 4.3. |
| D9 | Referential integrity was documented but not enforced. | Enforced by the engine and verified by I9. |
| D10 | A 3-decimal amount threw an arithmetic exception ("Rounding necessary"). | Rejected at input as AMOUNT_INVALID. |
| D11 | "Clear financial data" existed. | Removed. Restore from backup replaces it (with safeguards). |
| D12 | Parent aggregates and Buffer allocation were stored and recomputed after each mutation. | Derived on read; stored values for them are zero and ignored. |

## 8. Error catalog (exact user-facing text)

Placeholders in braces are filled by the engine; money is formatted with `formatINR`.

| Code | Message |
|---|---|
| AMOUNT_INVALID | Enter a valid amount in rupees, with at most 2 decimals. |
| AMOUNT_TOO_LARGE | That amount is above the safety limit of {max}. |
| NODE_NOT_FOUND | Category not found: {id}. |
| NODE_ARCHIVED | {name} is archived. |
| NOT_A_LEAF | {name} has sub-categories. Choose one of them instead. |
| BUFFER_NOT_ALLOWED | The Buffer can't be used for this. |
| ALLOCATION_NOT_ALLOWED | Allocations can only be set on Salary or on sub-categories. |
| BUFFER_NEGATIVE | This would make the Buffer allocation negative. Available Buffer allocation: {available}. |
| INSUFFICIENT_BUFFER | Not enough Buffer balance. Available: {available}, required: {required}. |
| DATE_INVALID | Enter a valid date. |
| DATE_FUTURE | The date can't be in the future. |
| DATE_OUT_OF_PERIOD | The date must be in {openPeriod}. To enter spends for a later month, close {openPeriod} first. |
| PERIOD_NOT_OPEN | Only the open period ({openPeriod}) can be closed. |
| ROLE_PROTECTED | {name} is required by the app and can't be changed that way. |
| PARENT_INVALID | That parent isn't allowed here ({reason}). |
| PARENT_NOT_EMPTY | {name} already has money, an allocation, or history. Settle and archive it first. |
| NAME_INVALID | Enter a name of 1 to 60 characters. |
| TAG_INVALID | Unknown tag. |
| STATE_INVALID | Stored data is inconsistent: {detail}. |

Mapping used by golden tests (legacy exception text to code): "Allocation would make Buffer negative" and "Leaf allocations exceed Salary" to BUFFER_NEGATIVE;
"Insufficient buffer balance" and "Available buffer balance is insufficient" to INSUFFICIENT_BUFFER; "Rounding necessary" to AMOUNT_INVALID.
Golden tests compare error codes, never message text.
