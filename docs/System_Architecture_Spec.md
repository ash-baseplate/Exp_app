# System Architecture & Technical Specification: Personal Ledger & Budget Tracker

## 1. Project Overview & Core Concept

The system is a manual, DAG-based personal finance tracker designed to monitor how income is allocated and spent across accounts and custom categories.

- No automated banking integrations
- No live financial data feeds
- Manual ledger entry and manual reallocations only
- Deterministic graph-based allocation tracking

---

## 2. Directed Acyclic Graph (DAG) Topology

All flows are modeled as a directed acyclic graph $G = (V, E)$.

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

### Node hierarchy and classifications

1. Source node: Salary
2. Account nodes: Bank 1, Bank 2
3. Category nodes: Commitments, Variable, Fixed Saving, Sinking
4. Subcategory nodes: Rent, Mobile Recharge, Family Provision, Friend Provision, Ebill, Grooming, Laundry, Office Food, Saved, Buffer, Medication, Personal Care, Short Term, Upskilling

---

## 3. Budget Tag Model

The implementation uses two explicit budget tags rather than broader boundary and rollover metadata.

| Tag | Meaning | Runtime behavior |
| --- | --- | --- |
| `SURPLUS_TO_BUFFER` | Surplus-collection tag for monthly spending categories | Positive balances are swept into Buffer at month-end |
| `DEFICIT_FROM_BUFFER` | Deficit-suppression tag for reserve and target categories | Negative balances trigger a Buffer fill when available |

A node may carry either tag individually or both tags together. This is intentional for Variable subcategories, which must both contribute surplus into Buffer and absorb negative shortfalls from Buffer. Buffer sits under Sinking and acts as the balancing reserve for the month.

---

## 4. State Management and Data Schema

### 4.1 Node entity interface

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

The tag collection is a set rather than a single enum field. A node may therefore carry an empty collection, a single tag, or both tags simultaneously.

### 4.2 Inter-category reallocation interface

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

## 5. Month-End Buffer Sweep and Deficit Resolution

The application executes a buffer-friendly state transfer at cycle boundary based on the two active budget tags.

```text
[ Month-End Triggered ]
            |
            v
[Find positive balances on SURPLUS_TO_BUFFER nodes]
            |
            v
[Move those surpluses into Buffer]
            |
            v
[Check whether any DEFICIT_FROM_BUFFER node is below zero]
            |
            v
[Pull required amount from Buffer if available]
```

### Transition logic rules

1. Surplus nodes
   - only leaf/subcategory nodes tagged `SURPLUS_TO_BUFFER` may contribute surplus to Buffer
   - parent account and category nodes are never swept directly; they aggregate their children
   - this applies primarily to Bank 1 and Variable leaf nodes such as Rent, Family Provision, Ebill, and Grooming

2. Deficit nodes
   - if a node tagged `DEFICIT_FROM_BUFFER` goes negative due to spending, the app attempts to cover the shortfall from Buffer
   - if Buffer lacks funds, the operation raises an error instead of silently drawing past zero

---

## 6. Functional Requirements and Developer Guidance

### 6.1 Graph integrity checks

- prevent cyclic connections
- enforce direct hierarchy limits
- validate node creation before persisting
- parent allocated/spent/balance values must be the aggregate of all direct child values

### 6.2 Visual delta indicators

If `spentAmount` > `allocatedAmount` on a node tagged `DEFICIT_FROM_BUFFER`:
- calculate $\Delta = \text{Spent} - \text{Allocated}$
- display a visual over-budget warning
- prompt the user to withdraw the shortfall from Buffer if funds are available
- raise a clear exception if Buffer does not have enough funds

### 6.3 Manual entry operations

The interface must allow:
- assigning top-level income allocations to BANK_1 and BANK_2
- assigning budget baselines to leaf subcategories
- logging daily spending against subcategories
- withdrawing funds from Buffer to cover deficits or reallocate surpluses into Buffer at month-end
- assigning either one tag or both tags to a node through the CLI or future UI, e.g. `SURPLUS_TO_BUFFER,DEFICIT_FROM_BUFFER`

### 6.4 Monthly allocation credit workflow

The app shall support a monthly cycle operation that runs in this sequence:

1. execute the month-end buffer sweep
2. move any positive leaf surplus into Buffer
3. carry forward the previous leaf balance and add this month’s allocation to it
4. leave parent totals derived from child aggregates

The credit is additive, not replacement-based. If a leaf had a prior balance of 300 and the monthly allocation is 500, the next-cycle balance must become 800.

Implementation contract: `addAllocation(...)` updates the allocated baseline only. It does not auto-credit the live balance field. The balance field changes only when the explicit monthly-cycle function runs, and that function must preserve prior balance by applying `previousBalance + allocatedAmount` rather than replacing it with the new allocation value.

This operation is intentionally not a silent mutation of historical data; it is a boundary reset for the next cycle after surplus has been swept and buffered.

---

## 7. Frontend UI Specification: Interactive Horizontal Canvas Graph

### 7.1 Layout and canvas behavior

- horizontal DAG orientation
- root nodes -> accounts -> categories -> subcategories
- pan/zoom canvas
- drag and drop node editing
- snap-to-grid alignment

### 7.2 Connection mechanics

- visual anchor handles on left and right sides of nodes
- edge drawing from output handle to input handle
- client-side DAG validation to prevent cycles
- Blue Outer, Brown Outer, and Dual nodes styled distinctly

### 7.3 Minimal toolbar

Allowed:
- Add Node
- Connect Line
- Node Properties Panel
- Realign Canvas

Excluded:
- freehand drawing
- sticky notes
- arbitrary text boxes
- custom non-finance shapes

### 7.4 Recommended stack

- React / Next.js with React Flow or xyflow
- Vue Flow for Vue
- D3-hierarchy or Dagre for auto-layout

---

## 8. Implementation Constraints

The system shall remain:
- manual, local-first, and privacy-first
- deterministic in budget logic
- non-destructive in financial history
- audit-friendly across ledger edges and category transitions

The architecture should prioritize explicit rules, graph integrity, and clear UI affordances over generic whiteboard features.
