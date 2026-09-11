# System Architecture & Technical Specification: Personal Ledger & Budget Tracker

## 1. Project Overview & Core Concept

The system is a manual, DAG-based personal finance tracker designed to monitor how income is allocated and spent across accounts and custom categories.

- No automated banking integrations
- No live financial feeds
- Manual ledger entry and manual reallocations only
- Deterministic, buffer-first budgeting logic

The system now uses two explicit budget tags instead of a larger boundary/rollover taxonomy:

- `SURPLUS_TO_BUFFER`: move positive remaining balances into Buffer at month-end
- `DEFICIT_FROM_BUFFER`: when a node goes negative, attempt to draw needed funds from Buffer

---

## 2. Directed Acyclic Graph (DAG) Topology

All monetary flows are modeled as a directed acyclic graph $G = (V, E)$.

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

The implementation uses two active budget tags.

| Tag | Meaning | Runtime behavior |
| --- | --- | --- |
| `SURPLUS_TO_BUFFER` | Surplus-collection tag for monthly spending categories | Positive balances are swept into Buffer at month-end |
| `DEFICIT_FROM_BUFFER` | Deficit-funding tag for reserve and target categories | Negative balances trigger a Buffer fill when available |

A node may carry either tag individually or both tags together. This is intentional for Variable subcategories, which must both contribute surplus into Buffer and absorb negative shortfalls from Buffer. Buffer sits under Sinking and acts as the balancing reserve for the month.

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

The current remaining balance is 950.

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

### 4.2 inter-category transfer interface

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

The application executes a buffer-friendly state transfer at cycle boundary based on the two active budget tags, but only for leaf/subcategory nodes.

```text
[ Month-End Triggered ]
            |
            v
[Find positive balances on leaf SURPLUS_TO_BUFFER nodes]
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
   - parent account and category nodes are never swept directly; their totals are derived from child aggregates
   - this applies primarily to Bank 1 and Variable leaf nodes such as Rent, Family Provision, Ebill, and Grooming

2. Deficit nodes
   - if a node tagged `DEFICIT_FROM_BUFFER` goes negative due to spending, the app attempts to cover the shortfall from Buffer
   - if Buffer lacks funds, the operation raises an error instead of silently drawing past zero

---

## 6. Functional Requirements and Developer Guidance

### 6.1 Graph integrity checks

- prevent cyclic connections
- enforce root -> account -> category -> subcategory hierarchy
- validate node creation before persistence
- parent allocated/spent/balance values must be the aggregate of all direct child values

### 6.2 Visual delta indicators

If `spentAmount` exceeds `allocatedAmount` on a node tagged `DEFICIT_FROM_BUFFER`:
- calculate $\Delta = \text{Spent} - \text{Allocated}$
- display a visual over-budget warning
- attempt to resolve the shortfall from Buffer when enough funds are available
- raise an explicit error if Buffer is insufficient

### 6.3 Manual entry operations

The interface must allow:
- assigning primary income to Salary
- assigning monthly baselines to subcategories
- logging daily spending against subcategories
- withdrawing from Buffer to another category when needed
- creating or updating nodes and their tags through the CLI or future UI, including dual-tag assignments like `SURPLUS_TO_BUFFER,DEFICIT_FROM_BUFFER`

---

## 7. Frontend UI Specification

### 7.1 Layout and canvas behavior

- horizontal DAG orientation
- root nodes -> accounts -> categories -> subcategories
- nested tree-style list in the CLI MVP
- future UI should preserve the same hierarchy and stack semantics

### 7.2 Connection mechanics

- node relationships remain directional and parent-driven
- Buffer remains a reserve child under Sinking
- the diagram stays finance-focused, not a generic drawing board

### 7.3 Minimal toolbar

Allowed:
- Add Node
- Update Node
- Delete Node
- Transfer from Buffer
- Run month-end

Excluded:
- freehand drawing
- sticky notes
- arbitrary text boxes
- generic whiteboard features unrelated to budgeting

---

## 8. Implementation Constraints

The system shall remain:
- manual, local-first, and privacy-first
- deterministic in budget logic
- non-destructive in financial history
- audit-friendly across ledger edges and category transitions

The architecture should prioritize explicit rules, graph integrity, and clear budget semantics over generic diagramming features.
