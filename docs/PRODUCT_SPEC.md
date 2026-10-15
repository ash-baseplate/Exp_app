# PRODUCT_SPEC

Status: DRAFT v0.1 (2026-10-14). Derived from owner interviews and audits of the legacy Java CLI. Not yet validated by running code.

## 1. Purpose

A phone-first, offline-first budgeting app for one person. The owner tracks a monthly salary split into a tree of
categories ("nodes"), records spends, and closes each month manually. The legacy Java CLI (SQLite) is being retired;
its data is imported once. After import, the PWA is the only tool.

- Platform: Android Chrome, installed as a PWA. Must work fully offline.
- Currency: INR. Amounts have exactly two decimals (paise).
- Users: exactly one. No accounts, no sync, no server.
- The owner updates spends about weekly and cross-checks everything by hand, so correctness and transparency beat speed.

## 2. Glossary

| Term | Meaning |
|---|---|
| Node | A budget category in a tree. |
| Salary | Reserved root node, id `salary`. Its allocation is the monthly income to split. |
| Buffer | Reserved leaf node, id `buffer`. Holds the residual allocation, absorbs surpluses, funds deficits. |
| Leaf | A non-archived node with no non-archived children. Only leaves hold money and take spends. |
| Allocation | The planned amount per month for a leaf (or Salary). Buffer's allocation is derived. |
| Balance | Money currently available in a leaf. Can only change through the operations in DOMAIN_RULES. |
| Spent | Sum of a node's spends dated in the open period. Derived, never stored. |
| Deficit | `max(0, spent - allocated)`. Display only. |
| Tags | `SURPLUS_TO_BUFFER` (leftover balance moves to Buffer at close) and `DEFICIT_FROM_BUFFER` (overspend is covered from Buffer). |
| Period | A calendar month, `YYYY-MM`. |
| Open period | The month currently being tracked. Stored explicitly (`meta.openPeriod`). |
| Close period | The manual month-end action: sweep surpluses, credit next month's allocations, snapshot stats, open the next month. Legacy name: "monthly cycle". |
| Ledger | Append-only audit trail of balance-affecting events. Not the source of truth for balances. |

## 3. Scope

In v1: viewing the tree, add spend (with past date inside the open period), add balance, set allocations, Buffer withdrawal,
close period (with preview), spend history, node management (create, rename, move, tag, archive), one-time legacy import,
JSON backup export and restore, Verify screen, install and offline use.

Out of v1 (do not build): multi-device sync, accounts or auth, backend or API, recurring or scheduled transactions, currency
conversion, spend edit or delete (deferred, see DECISIONS), "clear all data", reports (content undecided, see DECISIONS Q1),
iOS support, notifications, themes beyond light and dark following the system.

## 4. Functional requirements

### FR-01 View the budget
Tree of non-archived nodes, parents collapsible, children sorted by name. Each row shows balance, allocation, spent (open
period) and a deficit badge when deficit > 0. Parent values are sums of children (Salary shows its own allocation).
- Given the imported data, when I open Overview, then totals equal the values computed by DOMAIN_RULES section 3.
- Given I am offline, when I open the app, then the tree renders from local data.

### FR-02 Add spend
Choose a leaf, amount, date, optional note. Date defaults to today (local). Allowed dates: inside the open period's month and not in the future.
A confirmation sheet shows node, amount, date and, if applicable, "Buffer will cover INR X".
- Given a tagged leaf with balance 100 and a spend of 150 and Buffer balance 100, then the leaf ends at 0, Buffer drops by 50, and two ledger rows are written.
- Given Buffer balance 30 and a shortfall of 50, then the spend is rejected with INSUFFICIENT_BUFFER and nothing changes.
- Given a date in another month than the open period, then it is rejected before confirmation.
- Given an untagged leaf and a spend above its balance, then the balance goes negative (legacy behaviour) and the UI shows it clearly.

### FR-03 Add balance
Add an amount to Buffer or a leaf. Ledger row `BALANCE_ADDITION`.

### FR-04 Set allocation
Set (replace) the allocation of Salary or a leaf, with a live preview of the resulting Buffer allocation. Rejected if Buffer's allocation would go negative.

### FR-05 Buffer withdrawal
Move money from Buffer to a leaf. Rejected if Buffer lacks the balance or the destination is not a leaf.

### FR-06 Close period
Shows a preview (per leaf: surplus swept, credit, resulting balance) for the open period, then a confirm button reading
"Close YYYY-MM and fund YYYY-MM". One transaction. Afterwards the open period is the next month and Spent is zero everywhere.
- Given the open period 2026-10, when I close it, then 2026-11 becomes open and a `periodStats` row exists per leaf.
- Given I tap close twice, then the second attempt is rejected (PERIOD_NOT_OPEN) or does not exist because the screen already shows the new period.

### FR-07 Spend history
List spends by node and by period, newest first, with date, amount, note. Read-only in v1.

### FR-08 Node management
Create, rename, change tags, move, archive. Guard rules are in DOMAIN_RULES 4.6. Archiving never deletes history.

### FR-09 Import legacy data
One-time wizard described in IMPORT_SPEC, including an editable review of balances before anything is written.

### FR-10 Backup and restore
Export a single JSON file (Android share sheet where available, otherwise a download). Restore replaces all data after typed confirmation and an automatic export of the current data. A banner suggests a backup if none was made in 14 days (default, see DECISIONS).

### FR-11 Verify
Runs the invariants in DOMAIN_RULES section 6 and lists problems in plain language. Read-only.

### FR-12 Install and offline
Installable on Android Chrome; loads and operates in airplane mode; data persists across app restarts.

### FR-13 Storage durability
Request persistent storage (`navigator.storage.persist()`) and show its status in Settings with an explanation.

## 5. Screens

Navigation: in-memory screen state or hash routing (no path-based routes). Suggested bottom bar: Overview, Add spend, Buffer, More.

| Id | Screen | Contents |
|---|---|---|
| S1 | Overview | Open period label, tree (FR-01), button "Add spend". |
| S2 | Add spend | Leaf picker grouped by parent with search, amount (`inputmode="decimal"`), date input, note, confirmation sheet. |
| S3 | Node detail | Balance, allocation, spent, tags, spends of the open period, earlier periods grouped, actions (add balance, set allocation, edit node). |
| S4 | Allocations | Salary and leaf allocations editable, live Buffer residual, error if negative. |
| S5 | Buffer | Balance, derived allocation (read-only), withdrawal form, recent Buffer events. |
| S6 | Close period | Preview table, totals, confirm. |
| S7 | History | Spends filtered by node and period. Period stats view is a placeholder until reports are decided. |
| S8 | Structure | Tree editor: add, rename, tag, move, archive, with guard messages. |
| S9 | Data | Import (first run), export, restore, Verify. |
| S10 | Settings | Storage persistence status, last backup date, app version, install help. |

First run with empty data shows two choices: "Import from old app" (S9 import) or "Start empty" (creates `salary` and `buffer` with Buffer as child of Salary, open period = current local month).

## 6. Non-functional requirements

- Fully offline at runtime. No network calls, remote fonts or analytics.
- Touch targets at least 44px. Amount fields use a decimal keypad. Currency shown with `Intl.NumberFormat('en-IN')`.
- Every operation is atomic. A failure leaves data unchanged and shows the message from DOMAIN_RULES section 8.
- Cold start to interactive under 2 seconds on a mid-range Android phone (target, measure in M9).
- No personal data in the repository or in the deployed bundle.
- Accessibility basics: labels on all inputs, sufficient contrast, errors announced near the field.

## 7. Success criteria for v1

1. The owner's real data imports, after the review step, and every balance matches the owner's own cross-check.
2. All golden scenarios pass (except documented deviations) and all invariants hold under property tests.
3. The owner runs one real month, including a close, entirely on the phone, with no data loss and no unexplained numbers.
