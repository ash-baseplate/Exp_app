# Executable Implementation Backlog

## 1. Scope

This backlog reflects the revised directions in the current draft and should be treated as the implementation baseline for the project.

The product is a manual, local-first personal finance tracker built around a DAG of financial nodes, boundary rules, and month-end allocation behavior. It is intentionally not a bank-integration, live-sync, or generic whiteboard tool.

---

## 2. Execution Principle

The active requirement source is the architecture draft in `Docs/fuu.md`.

All implementation work must be aligned to:
- DAG model integrity
- explicit node boundary rules
- manual ledger behavior
- month-end rollover semantics
- UI behavior suited to budgeting rather than freeform diagramming

---

## 3. P0 — Foundation

### Task ID: ARCH-001
Title: Define node model and persistence contract

Objective:
Lock the financial node model and storage structure used by the app.

Implementation steps:
- define account, category, and subcategory entities
- define parent-child graph relationships
- define the two active `budgetTag` values: `SURPLUS_TO_BUFFER` and `DEFICIT_FROM_BUFFER`
- define allocation, spend, transfer, and buffer-sweep persistence contracts

Acceptance criteria:
- the persisted model supports a DAG of financial nodes
- node hierarchy is explicit and enforceable
- budget-tag metadata is captured in the data model

---

### Task ID: ARCH-002
Title: Enforce graph integrity rules

Objective:
Prevent invalid graph structures.

Implementation steps:
- implement validation for root -> account -> category -> subcategory hierarchy
- prevent cycles during create/connect operations
- ensure node-level validation happens before persistence

Acceptance criteria:
- no graph loops are allowed
- invalid connections are rejected at the client or service layer
- hierarchy rules are explicit and testable

---

### Task ID: ARCH-003
Title: Define ledger transfer and allocation semantics

Objective:
Create the data model for tracking manual ledger movement.

Implementation steps:
- define `LedgerTransferTransaction`
- add commit semantics for allocation, spending, and reallocation
- ensure all financial events are append-only and auditable

Acceptance criteria:
- manual reallocation is represented in the ledger
- transfer flow is explicit and inspectable
- raw ledger data is preserved without destructive mutation

---

## 4. P1 — Business Logic

### Task ID: BUS-001
Title: Implement surplus flow and deficit fallback logic

Objective:
Apply month-end and shortfall behavior based on the two active budget tags.

Implementation steps:
- move positive remaining balances only from leaf/subcategory `SURPLUS_TO_BUFFER` nodes into Buffer at month-end
- leave parent account and category nodes untouched because their totals are aggregated from children
- detect negative balances on `DEFICIT_FROM_BUFFER` nodes after spending
- withdraw the required amount from Buffer if available
- raise a clear exception when Buffer is insufficient
- run the month-end sweep before the next-cycle allocation credit step
- preserve the prior leaf balance and add the new monthly allocation after the sweep

Acceptance criteria:
- surplus is swept into Buffer only from leaf nodes without silent loss
- deficit resolution pulls from Buffer only when funds exist
- parent balances remain derived from direct child values
- each new monthly cycle carries forward the prior leaf balance and adds the new allocation, not replace it
- edge cases are explicit and predictable

---

### Task ID: BUS-002
Title: Implement buffer withdrawal workflow

Objective:
Allow users to move funds from Buffer to a target category when needed.

Implementation steps:
- expose source = Buffer and destination = target category
- validate transfer amount against available Buffer balance
- create ledger transfer transactions
- show warnings when the requested withdrawal exceeds Buffer availability

Acceptance criteria:
- transfers update balances without breaking DAG invariants
- user can pull money from Buffer for deficit resolution
- ledger events are visible for inspection

---

### Task ID: BUS-003
Title: Add over-budget warning and buffer resolution path

Objective:
Support variance handling in the UX and ledger flow.

Implementation steps:
- compute $\Delta = \text{Spent} - \text{Allocated}$ on deficit-tagged nodes
- render warning state in the inspector or node panel
- guide users to move funds from Buffer when available
- raise an explicit exception when available Buffer is insufficient

Acceptance criteria:
- the user sees a visual warning when a node is over budget
- the defect is tied to a valid resolution path
- the warning is based on clear ledger data and available buffer balance

---

## 5. P2 — UI and Interaction

### Task ID: UI-001
Title: Build the horizontal DAG canvas

Objective:
Render a finance-focused graph layout with directional node flows.

Implementation steps:
- left-to-right layout
- drag and drop node editing
- pan/zoom support
- node anchor handles and connecting lines

Acceptance criteria:
- graph visually matches the product concept
- users can create and connect nodes without generic whiteboard tooling
- canvas focuses on financial edge relationships instead of arbitrary drawing

---

### Task ID: UI-002
Title: Add node property panel and budget-tag controls

Objective:
Enable full editing of financial node attributes.

Implementation steps:
- show name, parent, budgetTag, allocatedAmount, spentAmount, currentBalance
- support property edits and persistence
- show warnings for over-budget conditions

Acceptance criteria:
- every node is editable through the inspector
- budget-tag metadata is stored and displayed
- the user can resolve variance without leaving the card panel

---

### Task ID: UI-003
Title: Keep the tool minimal and finance-centric

Objective:
Avoid feature creep and preserve the intended app focus.

Implementation steps:
- restrict whiteboard utilities to needed finance operations
- remove unsupported drawing and generic diagram features
- keep the interface purpose-driven

Acceptance criteria:
- the tool remains aligned to budgeting, not general diagramming
- the UI is streamlined and easier to reason about

---

## 6. QA and Validation

### Task ID: QA-001
Title: Validate graph rules and month-end transitions

Objective:
Test the core financial rules against the draft.

Implementation steps:
- validate DAG invariants
- validate Blue Outer reset behavior
- validate Brown Outer carry-forward behavior
- validate manual reallocation and over-budget warnings

Acceptance criteria:
- all core rules are testable and reproducible
- state transitions match the draft requirements
- edge cases are documented before implementation proceeds

---

## 7. Definition of Done

A task is considered complete only when:
- it maps to the current requirement draft
- it preserves ledger integrity
- it remains consistent with the DAG model and boundary semantics
- it is validated against the relevant product rule
- it is documented in the active repository docs rather than legacy notes


### Phase 3: UI and query behavior
- UI-001
- UI-002
- UI-003

### Phase 4: Quality and release
- QA-001
- QA-002
- QA-003
- OPS-001
- OPS-002

---

## 5. Definition of Done

The feature is considered done when:

- all P0 and P1 tasks pass acceptance criteria
- month-end execution is idempotent and transaction-safe
- closed historical periods collapse to summary nodes by default
- full audit trail is available explicitly through UI toggle
- E-bill fallback behavior matches canonical waterfall order
- raw ledger history remains intact and auditable

---

## 6. Immediate Next Step

Begin with the following tasks in order:

1. ARCH-001 — Schema hardening and migration support
2. ARCH-002 — Cycle lock metadata contract
3. ARCH-003 — Atomic month-end lifecycle engine

This order creates the correct safety envelope for all downstream logic and prevents invalid implementation paths.
