import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;

public class BudgetCliApp {
    public enum BudgetTag {
        SURPLUS_TO_BUFFER,
        DEFICIT_FROM_BUFFER
    }

    public static class Node {
        private final String id;
        private String name;
        private String parentId;
        private final Set<BudgetTag> tags = EnumSet.noneOf(BudgetTag.class);
        private BigDecimal allocatedAmount;
        private BigDecimal spentAmount;
        private BigDecimal currentBalance;

        public Node(String id, String name, String parentId, BudgetTag tag) {
            this.id = id;
            this.name = name;
            this.parentId = parentId;
            this.allocatedAmount = BigDecimal.ZERO;
            this.spentAmount = BigDecimal.ZERO;
            this.currentBalance = BigDecimal.ZERO;
            if (tag != null) {
                this.tags.add(tag);
            }
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getParentId() {
            return parentId;
        }

        public void setParentId(String parentId) {
            this.parentId = parentId;
        }

        public BudgetTag getTag() {
            if (tags.isEmpty()) {
                return null;
            }
            return tags.iterator().next();
        }

        public Set<BudgetTag> getTags() {
            return EnumSet.copyOf(tags);
        }

        public boolean hasTag(BudgetTag tag) {
            return tag != null && tags.contains(tag);
        }

        public void setTag(BudgetTag tag) {
            tags.clear();
            if (tag != null) {
                tags.add(tag);
            }
        }

        public void setTags(Set<BudgetTag> newTags) {
            tags.clear();
            if (newTags != null) {
                tags.addAll(newTags);
            }
        }

        public void addTag(BudgetTag tag) {
            if (tag != null) {
                tags.add(tag);
            }
        }

        public BigDecimal getAllocatedAmount() {
            return allocatedAmount;
        }

        public BigDecimal getSpentAmount() {
            return spentAmount;
        }

        public BigDecimal getCurrentBalance() {
            return currentBalance;
        }

        public void addAllocation(BigDecimal amount) {
            this.allocatedAmount = this.allocatedAmount.add(amount);
        }

        public void addSpend(BigDecimal amount) {
            this.spentAmount = this.spentAmount.add(amount);
            this.currentBalance = this.currentBalance.subtract(amount);
        }

        public void transferOut(BigDecimal amount) {
            this.currentBalance = this.currentBalance.subtract(amount);
        }

        public void transferIn(BigDecimal amount) {
            this.currentBalance = this.currentBalance.add(amount);
        }

        public BigDecimal deficit() {
            return spentAmount.subtract(allocatedAmount).max(BigDecimal.ZERO);
        }

        public boolean canAutoFundFromBuffer() {
            return hasTag(BudgetTag.DEFICIT_FROM_BUFFER) && !"buffer".equalsIgnoreCase(id);
        }

        public String tagLabel() {
            if (tags.isEmpty()) {
                return "NO_TAG";
            }
            List<String> values = new ArrayList<>();
            for (BudgetTag tag : BudgetTag.values()) {
                if (tags.contains(tag)) {
                    values.add(tag.name());
                }
            }
            return String.join("/", values);
        }

        public String describe() {
            return name + " [" + tagLabel() + "]"
                    + " | alloc=" + fmt(allocatedAmount)
                    + " | spent=" + fmt(spentAmount)
                    + " | balance=" + fmt(currentBalance)
                    + " | deficit=" + fmt(deficit());
        }
    }

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<String> activityLog = new ArrayList<>();

    public BudgetCliApp() {
        resetToBaseHierarchy();
    }

    private void refreshHierarchyAggregateValues() {
        for (Node node : nodes.values()) {
            List<Node> children = new ArrayList<>();
            for (Node candidate : nodes.values()) {
                if (Objects.equals(candidate.getParentId(), node.getId())) {
                    children.add(candidate);
                }
            }

            if (children.isEmpty()) {
                continue;
            }

            BigDecimal totalAllocated = BigDecimal.ZERO;
            BigDecimal totalSpent = BigDecimal.ZERO;
            BigDecimal totalBalance = BigDecimal.ZERO;
            for (Node child : children) {
                totalAllocated = totalAllocated.add(child.getAllocatedAmount());
                totalSpent = totalSpent.add(child.getSpentAmount());
                totalBalance = totalBalance.add(child.getCurrentBalance());
            }

            node.allocatedAmount = totalAllocated;
            node.spentAmount = totalSpent;
            node.currentBalance = totalBalance;
        }
    }

    public void seedDemoData() {
        resetToBaseHierarchy();
        activityLog.add("Base hierarchy restored with empty node history.");
    }

    private void resetToBaseHierarchy() {
        nodes.clear();
        activityLog.clear();

        Node salary = createNode("salary", "Salary", null, null);
        salary.addAllocation(new BigDecimal("26400"));

        Node bank1 = createNode("bank1", "Bank 1", salary.getId(), null);
        Node bank2 = createNode("bank2", "Bank 2", salary.getId(), null);

        createNode("commitments", "Commitments", bank1.getId(), BudgetTag.SURPLUS_TO_BUFFER);
        createNode("variable", "Variable", bank1.getId(), BudgetTag.SURPLUS_TO_BUFFER);

        createNode("sinking", "Sinking", bank2.getId(), null);
        createNode("fixed_saving", "Fixed Saving", bank2.getId(), BudgetTag.DEFICIT_FROM_BUFFER);

        Node rent = createNode("rent", "Rent", "commitments", BudgetTag.SURPLUS_TO_BUFFER);
        rent.addAllocation(new BigDecimal("11000"));

        Node mobileRecharge = createNode("mobile_recharge", "Mobile Recharge", "commitments", BudgetTag.SURPLUS_TO_BUFFER);
        mobileRecharge.addAllocation(new BigDecimal("350"));

        Node familyProvision = createNode("family_provision", "Family Provision", "commitments", BudgetTag.SURPLUS_TO_BUFFER);
        familyProvision.addAllocation(new BigDecimal("2000"));

        Node friendProvision = createNode("friend_provision", "Friend Provision", "commitments", BudgetTag.SURPLUS_TO_BUFFER);
        friendProvision.addAllocation(new BigDecimal("1000"));

        Node ebill = createNode("ebill", "Ebill", "variable", BudgetTag.SURPLUS_TO_BUFFER);
        ebill.addTag(BudgetTag.DEFICIT_FROM_BUFFER);
        ebill.addAllocation(new BigDecimal("400"));

        Node grooming = createNode("grooming", "Grooming", "variable", BudgetTag.SURPLUS_TO_BUFFER);
        grooming.addTag(BudgetTag.DEFICIT_FROM_BUFFER);
        grooming.addAllocation(new BigDecimal("400"));

        Node laundry = createNode("laundry", "Laundry", "variable", BudgetTag.SURPLUS_TO_BUFFER);
        laundry.addTag(BudgetTag.DEFICIT_FROM_BUFFER);
        laundry.addAllocation(new BigDecimal("400"));

        Node officeFood = createNode("office_food", "Office Food", "variable", BudgetTag.SURPLUS_TO_BUFFER);
        officeFood.addTag(BudgetTag.DEFICIT_FROM_BUFFER);
        officeFood.addAllocation(new BigDecimal("400"));

        Node buffer = createNode("buffer", "Buffer", "sinking", null);
        BigDecimal totalAllocated = new BigDecimal("11000").add(new BigDecimal("350")).add(new BigDecimal("2000")).add(new BigDecimal("1000")).add(new BigDecimal("400")).add(new BigDecimal("400")).add(new BigDecimal("400")).add(new BigDecimal("400")).add(new BigDecimal("5000")).add(new BigDecimal("800")).add(new BigDecimal("700")).add(new BigDecimal("2000")).add(new BigDecimal("1000"));
        BigDecimal remainingBuffer = new BigDecimal("26400").subtract(totalAllocated);
        buffer.addAllocation(remainingBuffer);

        Node saved = createNode("saved", "Saved", "fixed_saving", BudgetTag.DEFICIT_FROM_BUFFER);
        saved.addAllocation(new BigDecimal("5000"));

        Node medication = createNode("medication", "Medication", "sinking", BudgetTag.DEFICIT_FROM_BUFFER);
        medication.addAllocation(new BigDecimal("800"));

        Node personalCare = createNode("personal_care", "Personal Care", "sinking", BudgetTag.DEFICIT_FROM_BUFFER);
        personalCare.addAllocation(new BigDecimal("700"));

        Node shortTerm = createNode("short_term", "Short Term", "sinking", BudgetTag.DEFICIT_FROM_BUFFER);
        shortTerm.addAllocation(new BigDecimal("2000"));

        Node upskilling = createNode("upskilling", "Upskilling", "sinking", BudgetTag.DEFICIT_FROM_BUFFER);
        upskilling.addAllocation(new BigDecimal("1000"));

        refreshHierarchyAggregateValues();
        activityLog.add("Base hierarchy initialized with default monthly allocations and buffer remainder.");
    }

    public Node createNode(String id, String name, String parentId, BudgetTag tag) {
        if (id == null || id.isBlank()) {
            id = "node_" + (nodes.size() + 1);
        }
        if (nodes.containsKey(id)) {
            throw new IllegalArgumentException("Node id already exists: " + id);
        }
        if (parentId != null) {
            parentId = resolveExistingNodeId(parentId, "Parent");
        }
        Node node = new Node(id, name, parentId, tag);
        nodes.put(id, node);
        refreshHierarchyAggregateValues();
        activityLog.add("Created node " + name + " (" + id + ")");
        return node;
    }

    public Node createNodeWithTags(String id, String name, String parentId, Set<BudgetTag> tags) {
        Node node = createNode(id, name, parentId, (BudgetTag) null);
        if (tags != null && !tags.isEmpty()) {
            node.setTags(tags);
        }
        return node;
    }

    private String resolveExistingNodeId(String nameOrId, String label) {
        if (nameOrId == null || nameOrId.isBlank()) {
            return null;
        }

        String trimmed = nameOrId.trim();
        if (nodes.containsKey(trimmed)) {
            return trimmed;
        }

        Node node = findNodeByName(trimmed);
        if (node != null) {
            return node.getId();
        }

        throw new IllegalArgumentException(label + " does not exist: " + nameOrId);
    }

    public Node findNodeByName(String name) {
        for (Node node : nodes.values()) {
            if (node.getName().equalsIgnoreCase(name)) {
                return node;
            }
        }
        return null;
    }

    public Node findNodeById(String id) {
        return nodes.get(id);
    }

    public String renderStatus(String nameOrId) {
        Node node = findNodeByName(nameOrId);
        if (node == null) {
            node = findNodeById(nameOrId);
        }
        if (node == null) {
            return "Node not found: " + nameOrId;
        }

        return node.describe();
    }

    public void addAllocation(String nameOrId, BigDecimal amount) {
        Node node = resolveNode(nameOrId);
        node.addAllocation(amount);
        refreshHierarchyAggregateValues();
        activityLog.add("Allocation added to " + node.getName() + ": " + fmt(amount));
    }

    public void creditAllocatedAmountToBalance(String nameOrId) {
        Node node = resolveNode(nameOrId);
        creditAllocatedAmountToBalance(node);
    }

    private void creditAllocatedAmountToBalance(Node node) {
        if (isLeafNode(node)) {
            node.currentBalance = node.getCurrentBalance().add(node.getAllocatedAmount());
            return;
        }

        BigDecimal aggregatedAllocated = BigDecimal.ZERO;
        BigDecimal aggregatedSpent = BigDecimal.ZERO;
        BigDecimal aggregatedBalance = BigDecimal.ZERO;
        for (Node candidate : nodes.values()) {
            if (Objects.equals(candidate.getParentId(), node.getId())) {
                aggregatedAllocated = aggregatedAllocated.add(candidate.getAllocatedAmount());
                aggregatedSpent = aggregatedSpent.add(candidate.getSpentAmount());
                aggregatedBalance = aggregatedBalance.add(candidate.getCurrentBalance());
            }
        }

        node.allocatedAmount = aggregatedAllocated;
        node.spentAmount = aggregatedSpent;
        node.currentBalance = aggregatedBalance;
    }

    public void addSpend(String nameOrId, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Spend amount must be positive.");
        }
        Node node = resolveNode(nameOrId);
        if ("buffer".equalsIgnoreCase(node.getId())) {
            throw new IllegalArgumentException("Buffer cannot be spent directly. Use a transfer from buffer to a target category.");
        }

        node.addSpend(amount);
        if (node.getCurrentBalance().compareTo(BigDecimal.ZERO) < 0 && node.canAutoFundFromBuffer()) {
            Node buffer = findNodeById("buffer");
            if (buffer == null) {
                throw new IllegalStateException("Buffer node is missing.");
            }
            BigDecimal shortfall = node.getCurrentBalance().abs();
            if (buffer.getCurrentBalance().compareTo(shortfall) < 0) {
                throw new IllegalStateException("Insufficient buffer balance to cover " + node.getName() + ". Available: " + fmt(buffer.getCurrentBalance()) + ", required: " + fmt(shortfall));
            }
            buffer.transferOut(shortfall);
            node.transferIn(shortfall);
            activityLog.add("Auto-funded " + node.getName() + " from Buffer: " + fmt(shortfall));
        }
        refreshHierarchyAggregateValues();
        activityLog.add("Spend logged to " + node.getName() + ": " + fmt(amount));
    }

    public void transfer(String sourceNameOrId, String destinationNameOrId, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive.");
        }
        Node source = resolveNode(sourceNameOrId);
        Node destination = resolveNode(destinationNameOrId);

        if (!"buffer".equalsIgnoreCase(source.getId())) {
            throw new IllegalArgumentException("Transfer funds is now buffer withdrawal only. Source must be Buffer.");
        }
        if ("buffer".equalsIgnoreCase(destination.getId())) {
            throw new IllegalArgumentException("Buffer cannot transfer to itself.");
        }
        if (source.getCurrentBalance().compareTo(amount) < 0) {
            throw new IllegalStateException("Available buffer balance is insufficient. Available: " + fmt(source.getCurrentBalance()) + ", requested: " + fmt(amount));
        }

        source.transferOut(amount);
        destination.transferIn(amount);
        refreshHierarchyAggregateValues();
        activityLog.add("Buffer withdrawal: " + fmt(amount) + " from Buffer to " + destination.getName());
    }

    public String runMonthEnd() {
        StringBuilder output = new StringBuilder();
        Node buffer = findNodeById("buffer");
        if (buffer == null) {
            throw new IllegalStateException("Buffer node is missing.");
        }

        for (Node node : nodes.values()) {
            if (node == null || "buffer".equalsIgnoreCase(node.getId()) || !isLeafNode(node)) {
                continue;
            }
            if (node.hasTag(BudgetTag.SURPLUS_TO_BUFFER) && node.getCurrentBalance().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal surplus = node.getCurrentBalance();
                node.currentBalance = BigDecimal.ZERO;
                buffer.currentBalance = buffer.currentBalance.add(surplus);
                output.append("Surplus moved from ").append(node.getName()).append(" to Buffer: ").append(fmt(surplus)).append(System.lineSeparator());
            }
        }

        refreshHierarchyAggregateValues();
        activityLog.add("Month-end run executed.");
        return output.length() == 0 ? "No surplus moved to Buffer." : output.toString().trim();
    }

    public String runMonthEndAndCreditAllocations() {
        String monthEndSummary = runMonthEnd();

        for (Node node : nodes.values()) {
            if (node == null || !isLeafNode(node)) {
                continue;
            }
            node.currentBalance = node.getCurrentBalance().add(node.getAllocatedAmount());
        }

        refreshHierarchyAggregateValues();
        activityLog.add("Monthly allocations credited to leaf balances after month-end sweep.");

        StringBuilder summary = new StringBuilder();
        if (monthEndSummary != null && !monthEndSummary.isBlank()) {
            summary.append(monthEndSummary).append(System.lineSeparator());
        }
        summary.append("Monthly allocations credited.");
        return summary.toString().trim();
    }

    private boolean isLeafNode(Node node) {
        for (Node candidate : nodes.values()) {
            if (Objects.equals(candidate.getParentId(), node.getId())) {
                return false;
            }
        }
        return true;
    }

    public String renderAllNodes() {
        StringBuilder sb = new StringBuilder();
        List<Node> roots = new ArrayList<>();
        for (Node node : nodes.values()) {
            boolean isRoot = node.getParentId() == null || !nodes.containsKey(node.getParentId());
            if (isRoot) {
                roots.add(node);
            }
        }
        roots.sort(Comparator.comparing(Node::getName));

        for (Node root : roots) {
            appendNodeTree(root, 0, sb);
        }
        return sb.toString().trim();
    }

    private void appendNodeTree(Node node, int depth, StringBuilder sb) {
        sb.append("\t".repeat(Math.max(0, depth)))
          .append(node.getName())
          .append(" [")
          .append(node.tagLabel())
          .append("] | alloc=")
          .append(fmt(node.getAllocatedAmount()))
          .append(" | spent=")
          .append(fmt(node.getSpentAmount()))
          .append(" | balance=")
          .append(fmt(node.getCurrentBalance()))
          .append(System.lineSeparator());

        List<Node> children = new ArrayList<>();
        for (Node candidate : nodes.values()) {
            if (Objects.equals(candidate.getParentId(), node.getId())) {
                children.add(candidate);
            }
        }
        children.sort(Comparator.comparing(Node::getName));

        for (Node child : children) {
            appendNodeTree(child, depth + 1, sb);
        }
    }

    public String renderActivity() {
        StringBuilder sb = new StringBuilder();
        for (String entry : activityLog) {
            sb.append(entry).append(System.lineSeparator());
        }
        return sb.toString().trim();
    }

    public Node updateNode(String nameOrId, String newName, BudgetTag tag, String parentNameOrId) {
        Node node = resolveNode(nameOrId);
        if (newName != null && !newName.isBlank()) {
            node.name = newName;
        }
        if (tag != null) {
            node.setTag(tag);
        }
        if (parentNameOrId != null && !parentNameOrId.isBlank()) {
            String parentId = resolveExistingNodeId(parentNameOrId, "Parent");
            node.parentId = parentId;
        }
        refreshHierarchyAggregateValues();
        activityLog.add("Updated node " + node.getName());
        return node;
    }

    public Node updateNodeTags(String nameOrId, String newName, Set<BudgetTag> tags, String parentNameOrId) {
        Node node = resolveNode(nameOrId);
        if (newName != null && !newName.isBlank()) {
            node.name = newName;
        }
        if (tags != null) {
            node.setTags(tags);
        }
        if (parentNameOrId != null && !parentNameOrId.isBlank()) {
            String parentId = resolveExistingNodeId(parentNameOrId, "Parent");
            node.parentId = parentId;
        }
        refreshHierarchyAggregateValues();
        activityLog.add("Updated node " + node.getName() + " with tags " + node.tagLabel());
        return node;
    }

    public boolean deleteNode(String nameOrId) {
        Node node = resolveNode(nameOrId);
        if (node.getId().equals("salary")) {
            throw new IllegalArgumentException("Salary root cannot be deleted.");
        }

        List<String> childIds = new ArrayList<>();
        for (Node candidate : nodes.values()) {
            if (node.getId().equals(candidate.getParentId())) {
                childIds.add(candidate.getId());
            }
        }
        if (!childIds.isEmpty()) {
            throw new IllegalArgumentException("Cannot delete a node with child nodes. Reassign or delete children first.");
        }

        nodes.remove(node.getId());
        refreshHierarchyAggregateValues();
        activityLog.add("Deleted node " + node.getName());
        return true;
    }

    private Node resolveNode(String nameOrId) {
        Node node = findNodeByName(nameOrId);
        if (node == null) {
            node = findNodeById(nameOrId);
        }
        if (node == null) {
            throw new IllegalArgumentException("Node not found: " + nameOrId);
        }
        return node;
    }

    public static Set<BudgetTag> parseBudgetTags(String tagText) {
        Set<BudgetTag> tags = EnumSet.noneOf(BudgetTag.class);
        if (tagText == null || tagText.isBlank()) {
            return tags;
        }

        String[] values = tagText.split(",");
        for (String value : values) {
            String normalized = value.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            try {
                tags.add(BudgetTag.valueOf(normalized.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Invalid budget tag: " + normalized + ". Use SURPLUS_TO_BUFFER and/or DEFICIT_FROM_BUFFER.");
            }
        }
        return tags;
    }

    public static String fmt(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public static void main(String[] args) {
        BudgetCliApp app = new BudgetCliApp();
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.println();
                System.out.println("=== Budget CLI MVP ===");
                System.out.println("1) List nodes");
                System.out.println("2) Show node status");
                System.out.println("3) Add allocation");
                System.out.println("4) Add spend");
                System.out.println("5) Transfer funds (buffer withdrawal)");
                System.out.println("6) Run month-end");
                System.out.println("7) Run month-end + credit monthly allocations");
                System.out.println("8) Create node");
                System.out.println("9) Update node");
                System.out.println("10) Delete node");
                System.out.println("11) Exit");
                System.out.print("Choose an option: ");

                String choice;
                try {
                    choice = scanner.nextLine();
                } catch (Exception ex) {
                    System.out.println("Input stream closed. Exiting budget CLI.");
                    return;
                }

                try {
                    switch (choice.trim()) {
                        case "1" -> System.out.println(app.renderAllNodes());
                        case "2" -> {
                            System.out.print("Node name: ");
                            String name = scanner.nextLine();
                            System.out.println(app.renderStatus(name));
                        }
                        case "3" -> {
                            System.out.print("Node name: ");
                            String name = scanner.nextLine();
                            System.out.print("Amount: ");
                            BigDecimal value = new BigDecimal(scanner.nextLine());
                            app.addAllocation(name, value);
                            System.out.println("Allocation added.");
                        }
                        case "4" -> {
                            System.out.print("Node name: ");
                            String name = scanner.nextLine();
                            System.out.print("Amount: ");
                            BigDecimal value = new BigDecimal(scanner.nextLine());
                            app.addSpend(name, value);
                            System.out.println("Spend recorded.");
                        }
                        case "5" -> {
                            System.out.print("Source node (must be Buffer): ");
                            String source = scanner.nextLine();
                            System.out.print("Target node: ");
                            String target = scanner.nextLine();
                            System.out.print("Amount: ");
                            BigDecimal value = new BigDecimal(scanner.nextLine());
                            app.transfer(source, target, value);
                            System.out.println("Buffer withdrawal complete.");
                        }
                        case "6" -> System.out.println(app.runMonthEnd());
                        case "7" -> System.out.println(app.runMonthEndAndCreditAllocations());
                        case "8" -> {
                            System.out.print("Node id: ");
                            String id = scanner.nextLine();
                            System.out.print("Node name: ");
                            String name = scanner.nextLine();
                            System.out.print("Parent node (blank for root): ");
                            String parent = scanner.nextLine();
                            System.out.print("Budget tags [comma-separated: SURPLUS_TO_BUFFER,DEFICIT_FROM_BUFFER] (blank for none): ");
                            String tagText = scanner.nextLine();

                            Set<BudgetTag> parsedTags = tagText.isBlank() ? EnumSet.noneOf(BudgetTag.class) : app.parseBudgetTags(tagText);
                            Node created = app.createNodeWithTags(
                                id,
                                name,
                                parent.isBlank() ? null : parent,
                                parsedTags
                            );
                            System.out.println("Created node: " + created.getName() + " [" + created.tagLabel() + "]");
                        }
                        case "9" -> {
                            System.out.print("Node to update: ");
                            String target = scanner.nextLine();
                            System.out.print("New name (blank to keep): ");
                            String newName = scanner.nextLine();
                            System.out.print("New parent node (blank to keep): ");
                            String parent = scanner.nextLine();
                            System.out.print("New budget tags [comma-separated: SURPLUS_TO_BUFFER,DEFICIT_FROM_BUFFER] (blank to keep): ");
                            String tagText = scanner.nextLine();

                            Set<BudgetTag> parsedTags = tagText.isBlank() ? null : app.parseBudgetTags(tagText);
                            Node updated = app.updateNodeTags(
                                target,
                                newName.isBlank() ? null : newName,
                                parsedTags,
                                parent.isBlank() ? null : parent
                            );
                            System.out.println("Updated node: " + updated.getName() + " [" + updated.tagLabel() + "]");
                        }
                        case "10" -> {
                            System.out.print("Node to delete: ");
                            String target = scanner.nextLine();
                            app.deleteNode(target);
                            System.out.println("Deleted node.");
                        }
                        case "11" -> {
                            System.out.println("Exiting budget CLI.");
                            return;
                        }
                        default -> System.out.println("Invalid option.");
                    }
                } catch (Exception ex) {
                    System.out.println("Error: " + ex.getMessage());
                }
            }
        }
    }
}

