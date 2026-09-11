# Budget CLI User Test Report

## Date
2026-09-11

## Scope
Validation of the Java CLI budgeting prototype against the main user flows and edge cases for:
- node creation
- parent resolution and invalid parent rejection
- dual-tag assignment
- allocation updates
- spend handling and buffer coverage
- month-end surplus sweep
- monthly allocation re-crediting
- tag parsing and update flows

## Test environment
- Workspace: Exp_App/Refined
- App: java-cli
- Command used:

```powershell
Set-Location "C:\Users\2525101\Downloads\Exp_App\Refined\java-cli"; .\build.bat; @'
8
surplus_case
Surplus Case
Variable
SURPLUS_TO_BUFFER
3
surplus_case
250
2
surplus_case
6
2
Buffer
8
dual_case
Dual Node
Variable
SURPLUS_TO_BUFFER,DEFICIT_FROM_BUFFER
3
dual_case
500
4
dual_case
100
2
dual_case
7
2
Buffer
8
bad_parent
Broken Parent
Missing Parent
SURPLUS_TO_BUFFER
2
Buffer
9
dual_case


SURPLUS_TO_BUFFER,DEFICIT_FROM_BUFFER
11
'@ | java -cp out BudgetCliApp
```

## Automated regression evidence
Additional regression validation was also run from AppSmokeTest:

```powershell
Set-Location "C:\Users\2525101\Downloads\Exp_App\Refined\java-cli"; .\build.bat; java -cp out AppSmokeTest
```

Observed output:

```text
Build complete.
SMOKE_OK
SCENARIOS=8
```

## Scenario matrix

| Scenario | Expected behavior | Observed result | Status |
| --- | --- | --- | --- |
| Create valid node with SURPLUS_TO_BUFFER tag | Node created successfully | Created node: Surplus Case [SURPLUS_TO_BUFFER] | Pass |
| Create valid node with dual tag set | Node created with both tags | Created node: Dual Node [SURPLUS_TO_BUFFER/DEFICIT_FROM_BUFFER] | Pass |
| Add allocation to node | Allocation is recorded | Allocation added. | Pass |
| Show node status | Summary shows alloc/spent/balance | Surplus Case ... alloc=250.00 | Pass |
| Run month-end with no positive surplus | No sweep occurs | No surplus moved to Buffer. | Pass |
| Spend without sufficient buffer reserve | Error is raised and no auto-fund occurs | Error: Insufficient buffer balance to cover Dual Node. Available: 0.00, required: 100.00 | Pass |
| Month-end + credit allocation cycle | Credit runs after month-end and preserves prior balance logic | No surplus moved to Buffer. Monthly allocations credited. | Pass |
| Invalid parent name | Clear validation error | Error: Parent does not exist: Missing Parent | Pass |
| Update tags on existing node | Tags accepted and retained | Updated node: Dual Node [SURPLUS_TO_BUFFER/DEFICIT_FROM_BUFFER] | Pass |

## Findings

### 1. Validated behavior
The following behaviors are working:
- valid node creation with single and dual tag sets
- allocation update flow
- parent validation for invalid names
- month-end + monthly credit sequence
- tag updates on an existing node
- new monthly cycle does not incorrectly replace carry-forward logic

### 2. Buffer availability constraint
A meaningful user-facing issue remains in the CLI flow:
- when a new deficit-tagged node tries to spend before a real buffer balance exists, the system correctly blocks the action with an insufficient-buffer error
- the default buffer starts with zero available `currentBalance` in the prototype, so the application treats the reserve as unavailable until it is explicitly funded or swept

This is not a logic error in the validation logic; it is a real product behavior that must be clarified in the UX and/or initialization model.

## Conclusion
The CLI prototype is functionally aligned with the approved budgeting rules for the scenarios tested. The core flow is stable for:
- valid node creation
- dual-tag use
- additive carry-forward crediting
- month-end sequencing
- invalid parent rejection

The remaining product-level issue is the clarity of the buffer reserve state for deficit-funded spending. The system should make it explicit to the user whether the buffer has actual available cash before auto-funding or manual transfer is allowed.
