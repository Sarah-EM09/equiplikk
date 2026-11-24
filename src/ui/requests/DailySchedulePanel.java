package src.ui.requests;

import src.ui.AppTheme;
import src.ui.UiSupport;
import src.model.BorrowRequest;
import src.model.DepartmentStatus;
import src.model.EquipmentRequestItem;
import src.model.RequestOccurrence;
import src.model.RequestStatus;
import src.ui.components.DateFilter;
import src.ui.components.PrimaryButton;
import src.ui.components.SurfacePanel;

import javax.swing.AbstractCellEditor;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Read-only list of current and future, fully approved reservations still awaiting this
 * staff department's equipment assignment or release. It never changes a request or a group status.
 */
public class DailySchedulePanel extends SurfacePanel {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH);

    private final LocalDate date;
    private final String scopeRole;
    private final Consumer<BorrowRequest> onViewDetails;

    DailySchedulePanel(
            LocalDate date,
            Collection<BorrowRequest> requests,
            String scopeRole,
            Consumer<BorrowRequest> onViewDetails,
            Runnable onClearDate) {
        this.date = date;
        this.scopeRole = scopeRole;
        this.onViewDetails = onViewDetails;
        setLayout(new BorderLayout(0, 12));

        JPanel header = new JPanel(new BorderLayout(10, 4));
        header.setOpaque(false);
        JLabel title = new JLabel("Reservations awaiting assignment or release · "
                + date.format(DATE));
        title.setFont(AppTheme.semibold(15));
        title.setForeground(AppTheme.foreground());
        JLabel subtitle = new JLabel(
                "Fully approved reservations for your department on the selected day, sorted by start time.");
        subtitle.setFont(AppTheme.regular(10.5f));
        subtitle.setForeground(AppTheme.muted());
        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        heading.add(title);
        heading.add(Box.createVerticalStrut(2));
        heading.add(subtitle);
        header.add(heading, BorderLayout.CENTER);

        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        tools.setOpaque(false);
        PrimaryButton allDates = new PrimaryButton("All dates", PrimaryButton.Style.SECONDARY);
        allDates.setToolTipText("Return to the full request list");
        allDates.addActionListener(event -> onClearDate.run());
        tools.add(allDates);
        header.add(tools, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        List<ReservationRow> rows = awaitingReleaseRows(requests, date, scopeRole);
        rows.sort(Comparator
                .comparing((ReservationRow row) -> row.occurrence.getStartTime() == null
                        ? LocalTime.MAX : row.occurrence.getStartTime())
                .thenComparing(row -> row.request.getRequestCode() == null
                        ? String.valueOf(row.request.getRequestId()) : row.request.getRequestCode()));

        if (rows.isEmpty()) {
            JLabel empty = new JLabel(
                    "No fully approved reservations awaiting assignment or release for this date.",
                    SwingConstants.CENTER);
            empty.setFont(AppTheme.semibold(12));
            empty.setForeground(AppTheme.muted());
            empty.setAlignmentX(CENTER_ALIGNMENT);
            JPanel emptyHost = new JPanel(new BorderLayout());
            emptyHost.setOpaque(false);
            emptyHost.add(empty, BorderLayout.CENTER);
            add(emptyHost, BorderLayout.CENTER);
        } else {
            add(createTable(rows), BorderLayout.CENTER);
        }
    }

    /** Dates used by the staff calendar's normal request-day highlight. */
    static Set<LocalDate> awaitingReleaseDates(
            Collection<BorrowRequest> requests, String scopeRole) {
        Set<LocalDate> dates = new HashSet<>();
        for (ReservationRow row : awaitingReleaseRows(requests, null, scopeRole)) {
            LocalDate day = row.occurrence.getEventDate();
            if (day != null && !day.isBefore(DateFilter.today())) {
                dates.add(day);
            }
        }
        return dates;
    }

    /**
     * One row per qualifying reservation. A mixed ICT/Facility request remains a single
     * room reservation; only the signed-in department's equipment group is listed.
     */
    static List<ReservationRow> awaitingReleaseRows(
            Collection<BorrowRequest> requests, LocalDate selectedDate, String scopeRole) {
        List<ReservationRow> rows = new ArrayList<>();
        if (requests == null || scopeRole == null || scopeRole.isBlank()) {
            return rows;
        }
        for (BorrowRequest request : requests) {
            if (!isAwaitingRelease(request, scopeRole)) {
                continue;
            }
            List<RequestOccurrence> occurrences = request.getOccurrences();
            if (occurrences == null || occurrences.isEmpty()) {
                continue;
            }
            for (RequestOccurrence occurrence : occurrences) {
                if (occurrence == null || occurrence.getEventDate() == null) {
                    continue;
                }
                if (selectedDate != null && !selectedDate.equals(occurrence.getEventDate())) {
                    continue;
                }
                if (occurrence.getEventDate().isBefore(DateFilter.today())) {
                    continue;
                }
                String groupStatus = actionableGroupStatusForOccurrence(
                        request, scopeRole, occurrence);
                if (groupStatus == null) {
                    continue;
                }
                rows.add(new ReservationRow(
                        request, occurrence,
                        ownEquipmentText(request, scopeRole, true),
                        ownQuantityText(request, scopeRole),
                        "PENDING".equals(groupStatus) ? "assignment" : "release"));
            }
        }
        rows.sort(Comparator
                .comparing((ReservationRow row) -> row.occurrence.getStartTime() == null
                        ? LocalTime.MAX : row.occurrence.getStartTime())
                .thenComparing(row -> row.request.getRequestCode() == null
                        ? String.valueOf(row.request.getRequestId()) : row.request.getRequestCode()));
        return rows;
    }

    private static boolean isAwaitingRelease(BorrowRequest request, String scopeRole) {
        if (request == null || !RequestStatus.isFinalApprovalGranted(request)) {
            return false;
        }
        String stage = request.getStatus();
        if (!"APPROVED".equals(stage) && !"RELEASED".equals(stage)) {
            return false;
        }
        for (DepartmentStatus group : request.getDepartments()) {
            if (sameRole(scopeRole, group.getCustodianRole())
                    && ("PENDING".equalsIgnoreCase(group.getStatus())
                            || "ASSIGNED".equalsIgnoreCase(group.getStatus()))) {
                return true;
            }
        }
        return false;
    }

    private static String actionableGroupStatusForOccurrence(
            BorrowRequest request, String scopeRole, RequestOccurrence occurrence) {
        for (DepartmentStatus group : request.getDepartments()) {
            if (!sameRole(scopeRole, group.getCustodianRole())) {
                continue;
            }
            String status = group.getStatus() == null ? "" : group.getStatus().trim();
            if (!"PENDING".equalsIgnoreCase(status) && !"ASSIGNED".equalsIgnoreCase(status)) {
                continue;
            }
            Long occurrenceId = group.getOccurrenceId();
            if (occurrence.getOccurrenceId() > 0 && occurrenceId != null && occurrenceId > 0) {
                if (occurrence.getOccurrenceId() == occurrenceId) {
                    return status.toUpperCase(Locale.ROOT);
                }
                continue;
            }
            if (matchesSchedule(group, occurrence)) {
                return status.toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static boolean matchesSchedule(
            DepartmentStatus group, RequestOccurrence occurrence) {
        if (group.getScheduleStart() == null || group.getScheduleEnd() == null
                || occurrence.getStartTime() == null || occurrence.getEndTime() == null) {
            return false;
        }
        return group.getScheduleStart().toLocalDate().equals(occurrence.getEventDate())
                && group.getScheduleStart().toLocalTime().equals(occurrence.getStartTime())
                && group.getScheduleEnd().toLocalTime().equals(occurrence.getEndTime());
    }

    private static String ownEquipmentText(BorrowRequest request, String scopeRole, boolean names) {
        List<String> values = new ArrayList<>();
        for (EquipmentRequestItem item : request.getEquipmentItems()) {
            if (!sameRole(scopeRole, item.getCustodianRole())) {
                continue;
            }
            values.add(names ? blank(item.getEquipmentName())
                    : String.valueOf(item.getRequestedQuantity()));
        }
        return values.isEmpty() ? "—" : String.join(", ", values);
    }

    private static String ownQuantityText(BorrowRequest request, String scopeRole) {
        List<String> values = new ArrayList<>();
        for (EquipmentRequestItem item : request.getEquipmentItems()) {
            if (!sameRole(scopeRole, item.getCustodianRole())) {
                continue;
            }
            values.add(item.getRequestedQuantity() + " / " + item.getAssignedQuantity());
        }
        return values.isEmpty() ? "—" : String.join(", ", values);
    }

    static final class ReservationRow {
        final BorrowRequest request;
        final RequestOccurrence occurrence;
        final String equipment;
        final String quantity;
        final String stage;

        ReservationRow(BorrowRequest request, RequestOccurrence occurrence,
                String equipment, String quantity, String stage) {
            this.request = request;
            this.occurrence = occurrence;
            this.equipment = equipment;
            this.quantity = quantity;
            this.stage = stage;
        }
    }

    private JComponent createTable(List<ReservationRow> rows) {
        String[] columns = {
                "Request ID", "Borrower", "Department", "Requested equipment",
                "Qty req / assigned", "Room/Venue", "Reservation date", "Start Time",
                "End Time", "Approval status", "Release status", "View Details"
        };
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == columns.length - 1;
            }
        };
        for (ReservationRow row : rows) {
            model.addRow(new Object[] {
                    requestIdText(row.request),
                    blank(row.request.getBorrowerName()),
                    departmentText(row.request),
                    row.equipment,
                    row.quantity,
                    blank(row.occurrence.getVenue()),
                    row.occurrence.getEventDate().format(DATE),
                    row.occurrence.getStartTime() == null
                            ? "" : row.occurrence.getStartTime().format(TIME),
                    row.occurrence.getEndTime() == null
                            ? "" : row.occurrence.getEndTime().format(TIME),
                    "Fully approved",
                    "Awaiting " + RequestStatus.departmentLabel(scopeRole) + " " + row.stage,
                    "View Details"
            });
        }
        JTable table = new JTable(model);
        table.setFont(AppTheme.regular(11));
        table.setRowHeight(58);
        table.setGridColor(AppTheme.border());
        table.setBackground(AppTheme.surface());
        table.setForeground(AppTheme.foreground());
        table.setSelectionBackground(AppTheme.primarySoft());
        table.setSelectionForeground(AppTheme.foreground());
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.getTableHeader().setFont(AppTheme.semibold(11));
        table.getTableHeader().setBackground(AppTheme.tableHeaderBackground());
        table.getTableHeader().setForeground(AppTheme.muted());
        table.getTableHeader().setReorderingAllowed(false);

        int[] widths = { 124, 132, 150, 250, 110, 128, 118, 92, 92, 120, 150, 116 };
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        for (int i = 0; i < columns.length - 1; i++) {
            table.getColumnModel().getColumn(i).setCellRenderer(new WrappingRenderer());
        }
        int details = columns.length - 1;
        table.getColumnModel().getColumn(details).setCellRenderer(new DetailsRenderer());
        table.getColumnModel().getColumn(details).setCellEditor(new DetailsEditor(rows, onViewDetails));

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(0, Math.min(340, 88 + rows.size() * 58)));
        scroll.getViewport().setBackground(AppTheme.surface());
        return scroll;
    }

    private static String requestIdText(BorrowRequest request) {
        String id = "#" + request.getRequestId();
        return request.getRequestCode() == null || request.getRequestCode().isBlank()
                ? id : request.getRequestCode() + "\n" + id;
    }

    private static String departmentText(BorrowRequest request) {
        String classification = UiSupport.classification(request);
        String department = blank(request.getBorrowerDepartment());
        return "—".equals(classification) ? department
                : "—".equals(department) ? classification : classification + " · " + department;
    }

    private static boolean sameRole(String left, String right) {
        return left != null && right != null
                && left.trim().equalsIgnoreCase(right.trim());
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static final class WrappingRenderer extends javax.swing.JTextArea
            implements TableCellRenderer {

        WrappingRenderer() {
            setLineWrap(true);
            setWrapStyleWord(true);
            setOpaque(true);
            setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 8, 6, 8));
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean selected, boolean focused, int row, int column) {
            setText(value == null ? "" : value.toString());
            setFont(AppTheme.regular(11));
            setBackground(selected ? AppTheme.primarySoft()
                    : row % 2 == 0 ? AppTheme.alternateRowBackground() : AppTheme.surface());
            setForeground(AppTheme.foreground());
            return this;
        }
    }

    private static final class DetailsRenderer extends PrimaryButton implements TableCellRenderer {

        DetailsRenderer() {
            super("View Details", Style.SECONDARY);
            setFont(AppTheme.regular(10.5f));
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean selected, boolean focused, int row, int column) {
            return this;
        }
    }

    private static final class DetailsEditor extends AbstractCellEditor implements TableCellEditor {

        private final PrimaryButton button = new PrimaryButton("View Details", PrimaryButton.Style.SECONDARY);
        private final List<ReservationRow> rows;
        private int editingRow = -1;

        DetailsEditor(List<ReservationRow> rows, Consumer<BorrowRequest> onViewDetails) {
            this.rows = rows;
            button.setFont(AppTheme.regular(10.5f));
            button.addActionListener(event -> {
                if (editingRow >= 0 && editingRow < rows.size()) {
                    onViewDetails.accept(rows.get(editingRow).request);
                }
                fireEditingStopped();
            });
        }

        @Override
        public Component getTableCellEditorComponent(
                JTable table, Object value, boolean selected, int row, int column) {
            editingRow = row;
            return button;
        }

        @Override
        public Object getCellEditorValue() {
            return "View Details";
        }
    }
}
