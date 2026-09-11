import java.math.BigDecimal;

public class AppSmokeTest {
    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static BudgetCliApp newApp() {
        BudgetCliApp app = new BudgetCliApp();
        app.seedDemoData();
        return app;
    }

    public static void main(String[] args) {
        BudgetCliApp app = newApp();

        String status = app.renderStatus("Buffer");
        expect(status != null && !status.isBlank(), "Expected a rendered status for Buffer");

        BudgetCliApp.Node ebill = app.findNodeById("ebill");
        expect(ebill != null && ebill.hasTag(BudgetCliApp.BudgetTag.SURPLUS_TO_BUFFER) && ebill.hasTag(BudgetCliApp.BudgetTag.DEFICIT_FROM_BUFFER),
                "Expected variable subcategories to carry both surplus and deficit Buffer tags");

        BudgetCliApp.Node variable = app.findNodeById("variable");
        expect(variable != null && variable.getAllocatedAmount().compareTo(new BigDecimal("1600")) == 0,
                "Expected Variable parent allocation to aggregate all child allocations to 1600");

        BudgetCliApp.Node carryNode = app.createNode("carry_node", "Carry Node", "commitments", null);
        app.addAllocation("carry_node", new BigDecimal("500"));
        carryNode.transferIn(new BigDecimal("300"));
        app.creditAllocatedAmountToBalance("carry_node");
        expect(carryNode.getCurrentBalance().compareTo(new BigDecimal("800")) == 0,
                "Expected monthly credit to roll forward the prior balance and add the new allocation: 300 + 500 = 800");

        String monthlyCycle = app.runMonthEndAndCreditAllocations();
        expect(monthlyCycle != null && monthlyCycle.contains("Monthly allocations credited"),
                "Expected the monthly cycle to run month-end before crediting allocations to leaf balances");

        BudgetCliApp.Node dualTagNode = app.updateNodeTags("ebill", null, BudgetCliApp.parseBudgetTags("SURPLUS_TO_BUFFER,DEFICIT_FROM_BUFFER"), null);
        expect(dualTagNode != null && dualTagNode.hasTag(BudgetCliApp.BudgetTag.SURPLUS_TO_BUFFER) && dualTagNode.hasTag(BudgetCliApp.BudgetTag.DEFICIT_FROM_BUFFER),
                "Expected a node to support carrying both surplus and deficit tags at the same time");

        BudgetCliApp.Node created = app.createNode("new_child", "New Child", "Bank 1", null);
        expect(created != null && "New Child".equals(created.getName()),
                "Expected a newly created child node to resolve its parent by name");

        try {
            app.createNode("broken_parent", "Broken Parent", "Not A Real Parent", null);
            throw new IllegalStateException("Expected invalid parent name resolution to fail");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        String tree = app.renderAllNodes();
        expect(tree != null && !tree.isBlank(), "Expected a rendered hierarchy tree");
        expect(tree.contains("Salary") && tree.contains("Bank 1") && tree.contains("\tBank 1") && tree.contains("\t\tCommitments"),
                "Expected a nested tree hierarchy with tab-indented children");
        expect(tree.contains("Buffer") && tree.contains("Saved") && !tree.contains("Carryover"),
                "Expected Buffer under Sinking and only Saved under Fixed Saving without Carryover");

        String monthEnd = app.runMonthEnd();
        expect(!monthEnd.contains("Variable") && !monthEnd.contains("Commitments") && !monthEnd.contains("Bank 1") && !monthEnd.contains("Salary"),
                "Month-end surplus sweep must only target leaf child nodes, not parent category or account nodes");

        BudgetCliApp app2 = newApp();
        BudgetCliApp.Node surplusLeaf = app2.findNodeById("ebill");
        surplusLeaf.transferIn(new BigDecimal("250"));
        BudgetCliApp.Node bufferBefore = app2.findNodeById("buffer");
        BigDecimal before = bufferBefore.getCurrentBalance();
        String surplusSummary = app2.runMonthEnd();
        expect(surplusSummary.contains("Surplus moved from Ebill to Buffer"),
                "Expected month-end surrogate logic to move positive leaf balance into Buffer");
        expect(bufferBefore.getCurrentBalance().compareTo(before.add(new BigDecimal("250"))) == 0,
                "Expected Buffer to receive the positive surplus transferred from a leaf node");

        BudgetCliApp app3 = newApp();
        BudgetCliApp.Node buffer = app3.findNodeById("buffer");
        buffer.transferOut(buffer.getCurrentBalance());
        BudgetCliApp.Node deficitNode = app3.createNode("deficit_case", "Deficit Case", "variable", BudgetCliApp.BudgetTag.DEFICIT_FROM_BUFFER);
        deficitNode.addAllocation(new BigDecimal("100"));
        try {
            app3.addSpend("deficit_case", new BigDecimal("200"));
            throw new IllegalStateException("Expected deficit shortfall to be rejected when Buffer is insufficient");
        } catch (IllegalStateException expected) {
            expect(expected.getMessage().contains("Insufficient buffer balance"),
                    "Expected a clear insufficient-buffer error message");
        }

        BudgetCliApp app4 = newApp();
        expect(BudgetCliApp.parseBudgetTags("SURPLUS_TO_BUFFER,DEFICIT_FROM_BUFFER").size() == 2,
                "Expected parseBudgetTags to accept both budget tags in one comma-separated list");
        expect(BudgetCliApp.parseBudgetTags("SURPLUS_TO_BUFFER").contains(BudgetCliApp.BudgetTag.SURPLUS_TO_BUFFER),
                "Expected single-tag parsing to work");

        System.out.println("SMOKE_OK");
        System.out.println("SCENARIOS=8");
    }
}
