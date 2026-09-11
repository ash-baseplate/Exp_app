ExpApp MVP

This workspace now contains a Java CLI MVP for the budgeting and DAG workflow described in the active architecture docs.

How to run

1. Open a terminal in the workspace root.
2. Run: `cd java-cli`
3. Run: `build.bat`
4. Run: `run.bat`

The CLI supports:
- listing nodes
- showing node status
- adding allocations and spending
- transferring funds between nodes
- running a basic month-end rollover pass
- seeding demo budget data

The domain model reflects the current DAG-based rules:
- source -> accounts -> categories -> subcategories
- boundary types such as Blue Outer, Brown Outer, Dual Outer, and Reserve
- rollover rules such as Reset-to-Zero and Carry-Forward

This MVP is intentionally local-first and terminal-driven so it can run cleanly without a browser or external dependency manager.
