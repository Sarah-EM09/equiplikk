package src.ui.account;

import src.ui.UiSupport;
import src.ui.AppTheme;
import src.controller.AuthController;
import src.controller.BorrowRequestController;
import src.model.BorrowRequest;
import src.model.DepartmentStatus;
import src.model.EquipmentRequestItem;
import src.model.RequestHistoryEvent;
import src.model.RequestOccurrence;
import src.model.RequestStatus;
import src.model.User;
import src.ui.components.LucideIcon;
import src.ui.components.PrimaryButton;
import src.ui.components.StatusBadge;
import src.ui.components.SurfacePanel;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Dedicated ICT System Admin view of one account: identity, account status, and
 * the complete ICT/Facility request history loaded from PostgreSQL by user id.
 * The two departments share the existing borrow-request tables; a request that
 * contains both kinds of equipment appears once in each category with only that
 * department's own approval, release, and return details.
 */
public class AccountDetailsPanel extends JPanel {

    private static final String ALL_REQUESTS = "All Requests";
    private static final String ICT_EQUIPMENT = "ICT Equipment";
    private static final String FACILITY_VENUE = "Facility-Venue";
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("MMM d, yyyy  h:mm a");
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("MMM d, yyyy");

    private final User actor;
    private final User account;
    private final AuthController authController;
    private final BorrowRequestController requestController;
    private final Runnable backAction;

    private final JComboBox<String> categoryFilter =
            new JComboBox<>(new String[] { ALL_REQUESTS, ICT_EQUIPMENT, FACILITY_VENUE });
    private final JComboBox<String> statusFilter =
            new JComboBox<>(RequestStatus.Category.labels());
    private final JComboBox<String> sortOrder =
            new JComboBox<>(new String[] { "Newest first", "Oldest first" });
    private final JLabel countLabel = new JLabel();
    private final JPanel historyList = new JPanel();

    private List<BorrowRequest> requests = List.of();
    private Map<Long, List<RequestHistoryEvent>> events = Map.of();

    public AccountDetailsPanel(
            User actor,
            User account,
            AuthController authController,
            BorrowRequestController requestController,
            Runnable backAction) {

        this.actor = actor;
        this.account = account;
        this.authController = authController;
        this.requestController = requestController;
        this.backAction = backAction;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(AppTheme.background());
        setBorder(new EmptyBorder(2, 2, 22, 2));

        add(header());
        add(Box.createVerticalStrut(12));
        add(accountSection());
        add(Box.createVerticalStrut(16));
        add(historyControls());
        add(Box.createVerticalStrut(12));
        add(historyList);

        loadHistory();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        // The viewport can still be moved by Swing's deferred layout/focus
        // passes after this view is attached, so reset once those passes finish.
        UiSupport.scrollToTopAfterLayout(this);
    }

    private JPanel header() {
        JPanel header = new JPanel(new BorderLayout(12, 8));
        header.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel title = new JLabel("Account details");
        title.setFont(AppTheme.semibold(18));
        title.setForeground(AppTheme.foreground());
        JLabel subtitle = new JLabel(
                "Account information and complete ICT / Facility request history.");
        subtitle.setFont(AppTheme.regular(11));
        subtitle.setForeground(AppTheme.muted());
        titles.add(title);
        titles.add(Box.createVerticalStrut(4));
        titles.add(subtitle);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        PrimaryButton back = new PrimaryButton(
                "Back to User Management", PrimaryButton.Style.SECONDARY);
        back.setIcon(LucideIcon.forBack(14));
        back.setIconTextGap(6);
        back.addActionListener(event -> backAction.run());
        actions.add(back);

        header.add(titles, BorderLayout.WEST);
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    private JPanel accountSection() {
        SurfacePanel card = new SurfacePanel();
        card.setLayout(new GridBagLayout());
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 4, 5, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        addInfoRow(card, c, 0, "Full name", value(account.getFullName()));
        addInfoRow(card, c, 1, "Username", value(blankAsDash(account.getUsername())));
        addInfoRow(card, c, 2, "Email address", value(blankAsDash(account.getEmail())));
        addInfoRow(card, c, 3, "Role", value(roleWithClassification(account)));
        addInfoRow(card, c, 4, "Department", value(blankAsDash(account.getDepartment())));

        // Same label/value grid as every other row, so the status sits directly
        // beside its label instead of drifting to the far-right of the card.
        JLabel status = value(account.isDeleted() ? "Deleted" : "Active");
        status.setForeground(account.isDeleted() ? AppTheme.danger() : AppTheme.success());
        addInfoRow(card, c, 5, "Account status", status);
        return card;
    }

    private JPanel historyControls() {
        JPanel controls = new JPanel(new BorderLayout(10, 0));
        controls.setOpaque(false);
        controls.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel selects = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        selects.setOpaque(false);
        selects.add(filterLabel("Category"));
        UiSupport.styleInput(categoryFilter);
        categoryFilter.addActionListener(event -> showHistory());
        selects.add(categoryFilter);
        selects.add(filterLabel("Status"));
        UiSupport.styleInput(statusFilter);
        statusFilter.addActionListener(event -> showHistory());
        selects.add(statusFilter);
        selects.add(filterLabel("Sort"));
        UiSupport.styleInput(sortOrder);
        sortOrder.addActionListener(event -> showHistory());
        selects.add(sortOrder);

        countLabel.setFont(AppTheme.regular(11));
        countLabel.setForeground(AppTheme.muted());
        controls.add(selects, BorderLayout.WEST);
        controls.add(countLabel, BorderLayout.EAST);
        return controls;
    }

    private JLabel filterLabel(String text) {
        JLabel label = new JLabel(text + ":");
        label.setFont(AppTheme.semibold(11));
        label.setForeground(AppTheme.muted());
        return label;
    }

    private void loadHistory() {
        historyList.removeAll();
        historyList.setOpaque(false);
        historyList.setLayout(new BoxLayout(historyList, BoxLayout.Y_AXIS));
        historyList.setAlignmentX(Component.LEFT_ALIGNMENT);
        historyList.add(messagePanel("Loading request history…", false));
        historyList.revalidate();
        historyList.repaint();
        countLabel.setText("Loading…");

        new SwingWorker<AccountHistory, Void>() {
            @Override
            protected AccountHistory doInBackground() throws Exception {
                List<BorrowRequest> found =
                        requestController.findAllForAccount(actor, account.getUserId());
                List<Long> ids = new ArrayList<>();
                for (BorrowRequest request : found) {
                    if (!ids.contains(request.getRequestId())) {
                        ids.add(request.getRequestId());
                    }
                }
                List<RequestHistoryEvent> trail =
                        requestController.findHistoryForAccount(actor, account.getUserId(), ids);
                return new AccountHistory(found, trail);
            }

            @Override
            protected void done() {
                try {
                    AccountHistory result = get();
                    requests = result.requests;
                    events = new LinkedHashMap<>();
                    for (RequestHistoryEvent event : result.events) {
                        events.computeIfAbsent(event.getRequestId(), key -> new ArrayList<>())
                                .add(event);
                    }
                    showHistory();
                    // Loading history grows the page and triggers another deferred
                    // layout; reset afterwards so Account Details stays at the top.
                    UiSupport.scrollToTopAfterLayout(AccountDetailsPanel.this);
                } catch (Exception exception) {
                    requests = List.of();
                    events = Map.of();
                    countLabel.setText("");
                    historyList.removeAll();
                    historyList.add(messagePanel(
                            "Unable to load request history: " + messageOf(exception), true));
                    historyList.revalidate();
                    historyList.repaint();
                    UiSupport.scrollToTopAfterLayout(AccountDetailsPanel.this);
                }
            }
        }.execute();
    }

    private void showHistory() {
        historyList.removeAll();
        String selectedCategory = String.valueOf(categoryFilter.getSelectedItem());
        String selectedStatus = String.valueOf(statusFilter.getSelectedItem());
        RequestStatus.Category statusCategory = RequestStatus.Category.fromLabel(selectedStatus);
        boolean oldestFirst = "Oldest first".equals(sortOrder.getSelectedItem());

        List<Entry> entries = new ArrayList<>();
        for (BorrowRequest request : requests) {
            boolean ict = request.involvesDepartment(RequestStatus.ICT);
            boolean facility = request.involvesDepartment(RequestStatus.FACILITY);
            if (ict && matchesCategory(selectedCategory, RequestStatus.ICT)) {
                entries.add(new Entry(request, RequestStatus.ICT, ICT_EQUIPMENT));
            }
            if (facility && matchesCategory(selectedCategory, RequestStatus.FACILITY)) {
                entries.add(new Entry(request, RequestStatus.FACILITY, FACILITY_VENUE));
            }
            if (!ict && !facility && ALL_REQUESTS.equals(selectedCategory)) {
                entries.add(new Entry(request, null, "Request"));
            }
        }

        List<Entry> filtered = new ArrayList<>();
        for (Entry entry : entries) {
            if (statusCategory.matches(entry.request, entry.role)) {
                filtered.add(entry);
            }
        }
        filtered.sort(Comparator.comparing((Entry entry) -> entry.request.getSubmittedAt())
                .thenComparing(entry -> entry.request.getRequestId()));
        if (!oldestFirst) {
            java.util.Collections.reverse(filtered);
        }

        countLabel.setText(filtered.size() + (filtered.size() == 1 ? " record" : " records"));
        if (filtered.isEmpty()) {
            historyList.add(messagePanel("No requests match the selected filters.", false));
        } else {
            for (Entry entry : filtered) {
                historyList.add(requestCard(entry));
                historyList.add(Box.createVerticalStrut(10));
            }
        }
        historyList.revalidate();
        historyList.repaint();
    }

    private boolean matchesCategory(String selected, String role) {
        if (ALL_REQUESTS.equals(selected)) return true;
        if (ICT_EQUIPMENT.equals(selected)) return RequestStatus.ICT.equals(role);
        if (FACILITY_VENUE.equals(selected)) return RequestStatus.FACILITY.equals(role);
        return false;
    }

    private JPanel requestCard(Entry entry) {
        BorrowRequest request = entry.request;
        String status = request.getOverallStatus(entry.role);
        SurfacePanel card = new SurfacePanel();
        card.setLayout(new BorderLayout(12, 8));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel heading = new JPanel(new BorderLayout(8, 4));
        heading.setOpaque(false);
        JLabel code = new JLabel(request.getRequestCode()
                + "   ·   Request ID " + request.getRequestId());
        code.setFont(AppTheme.semibold(14));
        code.setForeground(AppTheme.foreground());
        JLabel submitted = new JLabel("Submitted " + STAMP.format(request.getSubmittedAt()));
        submitted.setFont(AppTheme.regular(10.5f));
        submitted.setForeground(AppTheme.muted());
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.add(code);
        titles.add(Box.createVerticalStrut(3));
        titles.add(submitted);

        JPanel badges = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        badges.setOpaque(false);
        badges.add(new StatusBadge(entry.category, AppTheme.primaryInk()));
        badges.add(new StatusBadge(UiSupport.displayStatus(status),
                UiSupport.statusColor(status)));
        if (request.getRemovedAt() != null) {
            badges.add(new StatusBadge("Removed from borrower list", AppTheme.muted()));
        }
        heading.add(titles, BorderLayout.WEST);
        heading.add(badges, BorderLayout.EAST);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setAlignmentX(Component.LEFT_ALIGNMENT);

        if (RequestStatus.FACILITY.equals(entry.role)) {
            addField(body, "Facility / venue", request.getVenue());
        }
        addField(body, "Equipment", equipmentText(request, entry.role));
        addField(body, "Quantity", quantityText(request, entry.role));
        addField(body, "Purpose", request.getPurpose());
        addField(body, "Borrowing schedule", scheduleText(request));
        addField(body, "Current status", UiSupport.displayStatus(status));
        addField(body, "Approval history", approvalText(request, entry.role));
        addField(body, "Release & return", releaseText(request, entry.role));
        if (RequestStatus.FACILITY.equals(entry.role)) {
            addField(body, "Facility usage", facilityUsageText(request));
        }

        card.add(heading, BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    private void addField(JPanel parent, String label, String value) {
        JPanel row = new JPanel(new BorderLayout(10, 2));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel key = new JLabel(label);
        key.setFont(AppTheme.semibold(11));
        key.setForeground(AppTheme.muted());
        key.setPreferredSize(new java.awt.Dimension(140, key.getPreferredSize().height));
        JTextArea text = new JTextArea(blankAsDash(value));
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setBorder(null);
        text.setFont(AppTheme.regular(11.5f));
        text.setForeground(AppTheme.foreground());
        row.add(key, BorderLayout.WEST);
        row.add(text, BorderLayout.CENTER);
        parent.add(row);
        parent.add(Box.createVerticalStrut(7));
    }

    private String equipmentText(BorrowRequest request, String role) {
        List<String> names = new ArrayList<>();
        for (EquipmentRequestItem item : request.getEquipmentItems()) {
            if (role == null || role.equalsIgnoreCase(item.getCustodianRole())) {
                String name = blankAsDash(item.getEquipmentName());
                if (item.getEquipmentCode() != null && !item.getEquipmentCode().isBlank()) {
                    name += " (" + item.getEquipmentCode() + ")";
                }
                names.add(name);
            }
        }
        return names.isEmpty() ? "No equipment recorded" : String.join("  ·  ", names);
    }

    private String quantityText(BorrowRequest request, String role) {
        int requested = 0, released = 0, returned = 0;
        for (EquipmentRequestItem item : request.getEquipmentItems()) {
            if (role == null || role.equalsIgnoreCase(item.getCustodianRole())) {
                requested += item.getRequestedQuantity();
                released += item.getReleasedQuantity();
                returned += item.getReturnedQuantity();
            }
        }
        if (requested == 0 && released == 0 && returned == 0) return "—";
        return "Requested " + requested + "  ·  Released " + released + "  ·  Returned " + returned;
    }

    private String scheduleText(BorrowRequest request) {
        List<String> parts = new ArrayList<>();
        if (request.getOccurrences().size() > 1) {
            int index = 1;
            for (RequestOccurrence occurrence : request.getOccurrences()) {
                parts.add(index++ + ". " + DAY.format(occurrence.getEventDate()) + "  ·  "
                        + UiSupport.timeRange(occurrence.getStartTime(), occurrence.getEndTime())
                        + "  ·  " + blankAsDash(occurrence.getVenue()));
            }
        } else {
            parts.add(DAY.format(request.getEventDate()) + "  ·  "
                    + UiSupport.timeRange(request.getStartTime(), request.getEndTime())
                    + "  ·  " + blankAsDash(request.getVenue()));
        }
        return String.join("\n", parts);
    }

    private String approvalText(BorrowRequest request, String role) {
        List<String> lines = new ArrayList<>();
        for (RequestHistoryEvent event : events.getOrDefault(request.getRequestId(), List.of())) {
            if (event.getCustodianRole() != null
                    && (role == null || !role.equalsIgnoreCase(event.getCustodianRole()))) {
                continue;
            }
            StringBuilder line = new StringBuilder(STAMP.format(event.getCreatedAt()))
                    .append("  ·  ").append(UiSupport.displayStatus(event.getEventType()));
            if (event.getActorName() != null && !event.getActorName().isBlank()) {
                line.append(" by ").append(event.getActorName());
            }
            if (event.getToStatus() != null
                    && !event.getToStatus().equals(event.getFromStatus())) {
                line.append("  →  ").append(UiSupport.displayStatus(event.getToStatus()));
            }
            if (event.getRemarks() != null && !event.getRemarks().isBlank()) {
                line.append(" — ").append(event.getRemarks());
            }
            lines.add(line.toString());
        }
        return lines.isEmpty() ? "No approval events recorded yet." : String.join("\n", lines);
    }

    private String releaseText(BorrowRequest request, String role) {
        List<String> lines = new ArrayList<>();
        List<DepartmentStatus> groups = new ArrayList<>();
        for (DepartmentStatus group : request.getDepartments()) {
            if (role == null || role.equalsIgnoreCase(group.getCustodianRole())) {
                groups.add(group);
            }
        }
        boolean multiple = groups.size() > 1;
        for (DepartmentStatus group : groups) {
            StringBuilder line = new StringBuilder(UiSupport.departmentStatus(group.getStatus()));
            if (multiple && group.getScheduleStart() != null) {
                line.append(" (").append(DAY.format(group.getScheduleStart().toLocalDate()))
                        .append(")");
            }
            if (group.getDecidedByName() != null) {
                line.append("  ·  decided by ").append(group.getDecidedByName());
                if (group.getDecidedAt() != null) {
                    line.append(" on ").append(STAMP.format(group.getDecidedAt()));
                }
            }
            if (group.getReleasedAt() != null) {
                line.append("  ·  released ").append(STAMP.format(group.getReleasedAt()));
                if (group.getReleasedByName() != null) {
                    line.append(" by ").append(group.getReleasedByName());
                }
            }
            if (group.getReceivedAt() != null) {
                line.append("  ·  received ").append(STAMP.format(group.getReceivedAt()));
                if (group.getReceivedByName() != null) {
                    line.append(" by ").append(group.getReceivedByName());
                }
            }
            if (group.getInspectedAt() != null) {
                line.append("  ·  returned ").append(STAMP.format(group.getInspectedAt()));
                if (group.getInspectedByName() != null) {
                    line.append(" by ").append(group.getInspectedByName());
                }
            }
            if (group.getRemarks() != null && !group.getRemarks().isBlank()) {
                line.append("  ·  remarks: ").append(group.getRemarks());
            }
            lines.add(line.toString());
        }
        for (EquipmentRequestItem item : request.getEquipmentItems()) {
            if (role != null && !role.equalsIgnoreCase(item.getCustodianRole())) continue;
            if (item.getConditionBefore() == null && item.getConditionAfter() == null) continue;
            lines.add(blankAsDash(item.getEquipmentName()) + " · before "
                    + blankAsDash(item.getConditionBefore()) + " · after "
                    + blankAsDash(item.getConditionAfter()));
        }
        return lines.isEmpty() ? "No release or return recorded yet."
                : String.join("\n", lines);
    }

    private String facilityUsageText(BorrowRequest request) {
        return "Venue " + blankAsDash(request.getVenue())
                + "  ·  Participants " + blankAsDash(request.getParticipants())
                + "  ·  Person in charge " + blankAsDash(request.getPersonInCharge())
                + " (" + blankAsDash(request.getPosition()) + ")";
    }

    /** "Borrower • Faculty" / "Borrower • Student" from the stored account fields. */
    private static String roleWithClassification(User account) {
        String role = UiSupport.displayStatus(account.getRole());
        String classification = account.getBorrowerType();
        if (classification == null || classification.isBlank()) {
            return role;
        }
        return role + " \u2022 " + UiSupport.displayStatus(classification);
    }

    private JLabel value(String text) {
        JLabel label = new JLabel(blankAsDash(text));
        label.setFont(AppTheme.regular(12));
        label.setForeground(AppTheme.foreground());
        return label;
    }

    private void addInfoRow(JPanel panel, GridBagConstraints c, int row,
            String label, JComponent field) {
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        JLabel key = new JLabel(label);
        key.setFont(AppTheme.semibold(12));
        key.setForeground(AppTheme.foreground());
        panel.add(key, c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(field, c);
    }

    private JPanel messagePanel(String text, boolean error) {
        SurfacePanel panel = new SurfacePanel();
        panel.setLayout(new BorderLayout());
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel label = new JLabel(text);
        label.setFont(AppTheme.regular(11));
        label.setForeground(error ? AppTheme.danger() : AppTheme.muted());
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    private static String blankAsDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private String messageOf(Exception exception) {
        Throwable cause = exception instanceof ExecutionException && exception.getCause() != null
                ? exception.getCause() : exception;
        return cause.getMessage();
    }

    private static final class Entry {
        final BorrowRequest request;
        final String role;
        final String category;

        Entry(BorrowRequest request, String role, String category) {
            this.request = request;
            this.role = role;
            this.category = category;
        }
    }

    private static final class AccountHistory {
        final List<BorrowRequest> requests;
        final List<RequestHistoryEvent> events;

        AccountHistory(List<BorrowRequest> requests, List<RequestHistoryEvent> events) {
            this.requests = requests;
            this.events = events;
        }
    }
}
