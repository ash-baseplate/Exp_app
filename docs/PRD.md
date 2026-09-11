# Product Requirements Document (PRD)

## 1. Purpose

This product is a manual, DAG-based personal finance tracker designed to model how income is allocated and spent across accounts and custom categories.

It is not a bank integration tool. It does not connect to live financial institutions, execute real transfers, or automate external banking activities. Instead, it is a deterministic, single-user ledger for tracking logical money movement and category behavior over time.

---

## 2. Core Concept

The system is built around a directed acyclic graph of financial nodes. The graph models flows of money as a controlled hierarchy rather than as real-world transfers between actual bank accounts.

The core value proposition is:
- deterministic allocation tracking
- manual ledger entry for income, spending, and reallocation
- explicit boundary rules for reset-vs-carry-forward behavior
- month-end state transitions without data loss
- a visual graph that is easy to understand and audit

---

## 3. DAG Topology

All monetary flows are modeled as a Directed Acyclic Graph $G = (V, E)$, where $V$ is the set of financial nodes and $E$ is the set of directional allocation paths.

```text
                              [ Salary ]
                                   |
                     +-------------+-------------+
                     |                           |
                   [ Bank 1 ]                  [ Bank 2 ]
                     |                           |
             +-----------+-----------+      +-----------+-----------+
             |                       |      |                       |
        [ Commitments ]        [ Variable ]  [ Fixed Saving ]      [ Sinking ]
             |                       |      |                       |
      +------+------+        +------+----+    +----+----+          +-----------+
      |             |               |                   |               |           |
  [Rent] [Family] [Friend] [Mobile] [Ebill] [Grooming] [Laundry] [Office Food] [Saved] [Buffer] [Medication] [Personal Care] [Short Term] [Upskilling]
```

### Node hierarchy and classification

1. Source node: Salary (root input node)
2. Account nodes: Bank 1, Bank 2
3. Category nodes: Commitments, Variable, Fixed Saving, Sinking
4. Subcategory nodes: Rent, Mobile Recharge, Family Provision, Friend Provision, Ebill, Grooming, Laundry, Office Food, Saved, Buffer, Medication, Personal Care, Short Term, Upskilling

---

## 4. Budget Tag Model

The implementation uses two explicit budget tags instead of a larger boundary/rollover taxonomy.

| Tag | Purpose | Operational behavior |
| --- | --- | --- |
| `SURPLUS_TO_BUFFER` | Surplus-harvesting tag for Bank 1 and Variable subcategories | At month-end, positive remaining balances are swept into Buffer |
| `DEFICIT_FROM_BUFFER` | Deficit-funding tag for reserve and target categories | If a spend causes a deficit, the app attempts to pull the amount from Buffer automatically |

A node may carry either one tag or both tags at the same time. This is intentional for dual-role Variable nodes, which need to both collect surplus at month-end and absorb a deficit from Buffer when required. Buffer itself remains a reserve node under Sinking and is not treated as a spend category.

### Default budget allocation model

The initial nominal monthly allocation is:

- Salary: 26,400
- Family Provision: 2,000
- Friend Provision: 1,000
- Mobile Recharge: 350
- Rent: 11,000
- Ebill: 400
- Grooming: 400
- Laundry: 400
- Office Food: 400
- Saved: 5,000
- Medication: 800
- Personal Care: 700
- Short Term: 2,000
- Upskilling: 1,000
- Buffer: remaining balance after explicit allocations from salary

The buffer remainder is therefore 950 for the current default budget.

---

## 5. Functional Requirements

### 5.1 Graph integrity checks

The system shall prevent cyclic connections during node creation and ensure a valid hierarchy:
- Salary -> Accounts -> Categories -> Subcategories
- no cycles such as $A \rightarrow B \rightarrow C \rightarrow A$
- parent `allocatedAmount`, `spentAmount`, and `currentBalance` values shall reflect the sum of all direct child values

### 5.2 Visual delta indicators

If `spentAmount` exceeds `allocatedAmount` on a node tagged `DEFICIT_FROM_BUFFER`, the system shall calculate delta $\Delta = \text{Spent} - \text{Allocated}$ and display an over-budget warning.

The system shall attempt to resolve the deficit by drawing the required amount from Buffer if the available balance is sufficient. If the buffer balance is insufficient, it shall raise an explicit exception and require the user to intervene.

### 5.3 Manual entry operations

The interface shall support:
- assigning salary income to the root budget model
- assigning monthly baselines to subcategories
- logging daily spending against subcategories
- withdrawing from Buffer to another category by transfer
- adding or updating nodes and their budget tags

### 5.4 Month-end state machine

At the end of a cycle, the system executes a buffer sweep only for leaf/subcategory nodes tagged `SURPLUS_TO_BUFFER`.

```text
[ Month-End Triggered ]
            |
            v
[Find positive remaining balances on leaf SURPLUS_TO_BUFFER nodes]
            |
            v
[Move surplus into Buffer]
            |
            v
[Prepare for next cycle without silently replacing carried balances]
```

Implementation contract note: `addAllocation(...)` sets the monthly baseline for a node. It must not implicitly mutate the node’s live balance. The live carry-forward balance is updated only during the explicit monthly-cycle credit step that runs after the month-end sweep.

### 5.5 Surplus-to-buffer rule

For all leaf nodes tagged `SURPLUS_TO_BUFFER`:
- if the current balance is positive at month end, transfer the positive remainder into Buffer
- the leaf node balance is then reduced to zero for the next cycle
- parent categories and accounts are not swept directly; their totals are derived from child aggregates
- this applies primarily to Bank 1 and Variable leaf nodes such as Rent, Family Provision, Ebill, and Grooming

### 5.6 Deficit-from-buffer rule

For all nodes tagged `DEFICIT_FROM_BUFFER`:
- if the node goes negative after a spend, the system attempts to fund the shortfall from Buffer
- the transfer is only valid if Buffer has enough available balance
- if Buffer is insufficient, the system raises an error instead of silently overdrawn behavior

### 5.7 Monthly allocation credit cycle

At the start of each new month, the system shall run the month-end sweep before re-crediting allocated amounts to leaf/subcategory balances.

The credit is additive: the previous balance rolls forward and the new month’s allocation is added on top of it, rather than replacing the old remaining balance. The operation is explicit and not a hidden side effect of the allocation setter.

```text
[New Monthly Cycle]
        |
        v
[Run Month-End Sweep]
        |
        v
[Move any surplus into Buffer]
        |
        v
[Carry forward previous balance + add this month allocation]
```

Example:
- previous balance = 300
- monthly allocation = 500
- next cycle balance = 800

This ensures that residual carry-forward is preserved while the next cycle baseline is added.

> Required runtime behavior: month-end must run before the allocation credit step, and the credit step must preserve prior balance instead of overwriting it.

---

## 6. Data Model Requirements

### 6.1 Node entity interface

```typescript
type BudgetTag = 'SURPLUS_TO_BUFFER' | 'DEFICIT_FROM_BUFFER';

type BudgetTagSet = BudgetTag[];

interface FinancialNode {
  id: string;
  name: string;
  parentId: string | null;
  budgetTags: BudgetTagSet;
  allocatedAmount: number;
  spentAmount: number;
  currentBalance: number;
}
```

A node is valid with `budgetTags = []`, with a single tag, or with both tags present. Dual-tag nodes are expected for Variable envelopes that both sweep surplus and auto-fund deficits.

### 6.2 Inter-category reallocation interface

```typescript
interface LedgerTransferTransaction {
  id: string;
  timestamp: Date;
  sourceNodeId: string;
  destinationNodeId: string;
  amount: number;
  note?: string;
}
```

---

## 7. Frontend UI Requirements

### 7.1 Visual layout

The app shall render a horizontal DAG with the flow left-to-right:
- Salary
- Accounts
- Categories
- Subcategories

The UI should support infinite pan/zoom, drag and drop, and snap-to-grid alignment similar to a lightweight whiteboard canvas.

### 7.2 Node connection mechanics

- each node shall expose anchor handles on left and right sides
- users connect source to target by dragging a line between handles
- cyclic loop prevention must be enforced client-side
- boundary visual styling shall distinguish Blue Outer, Brown Outer, and Dual nodes

### 7.3 Toolbar constraints

The product shall keep the canvas minimal and finance-specific. Allowed operations:
- Add Node
- Connect Line
- Node Properties Panel
- Realign Canvas

Excluded features:
- freehand drawing
- arbitrary text boxes
- sticky notes
- custom graphic shapes unrelated to financial modeling

### 7.4 Recommended stack

- React / Next.js with React Flow or xyflow
- Vue Flow for Vue-based implementations
- D3-hierarchy or Dagre for auto-layout

---

## 8. Success Criteria

The product is successful when it lets a user:
- model finances as a DAG of logical envelopes
- assign income and spending across categories
- see over-budget warnings and apply manual reallocation
- safely handle month-end boundary transitions
- inspect node relationships without losing ledger integrity
- work with a visual interface tailored to budgeting rather than diagramming

### 8.2 Performance

- Standard views must avoid loading massive historical transaction sets for closed periods
- Historical month summaries must render quickly even with years of underlying data

### 8.3 Privacy and local-first storage

- All data stays local to the user environment
- No cloud dependency is required for core operation

---

## 9. Acceptance Criteria

### 9.1 Soft summary acceptance

The system shall support clear summary-based historical views without deleting raw event history.

### 9.2 Buffer fallback acceptance

Given an Ebill deficit of 200 and the following balances:

- Buffer = 90
- Fixed Saving / reserve balances = 110

Then the system shall resolve the shortfall in this exact order:

1. draw up to 90 from Buffer
2. if additional amount remains, return a clear insufficient-buffer error instead of silently exceeding the available reserve
3. require user confirmation or a manual adjustment before continuing

### 9.3 Tag acceptance

The system shall apply month-end surplus sweeps and deficit fallback semantics exactly according to the active budget tags:

- `SURPLUS_TO_BUFFER` pushes positive balances from the relevant category into Buffer at month-end
- `DEFICIT_FROM_BUFFER` draws from Buffer when the category goes into deficit
- if Buffer lacks enough balance, the system raises a clear validation error

---

## 10. Final Product Positioning

The app should feel like a clean, high-trust budgeting ledger: current-month operations are visible and actionable, while historical periods remain auditable without becoming visually overwhelming.
