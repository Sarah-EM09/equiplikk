package src.ui.equipment;

import src.ui.AppTheme;
import src.ui.UiSupport;
import src.controller.EquipmentController;
import src.model.Equipment;
import src.model.EquipmentUsageRecord;
import src.model.User;
import src.ui.components.PrimaryButton;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

/** Read-only list of borrowers currently holding units of one equipment type. */
public final class EquipmentUsageDialog extends JDialog {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH);
    private final EquipmentController equipmentController;
    private final User actor;
    private final Equipment equipment;
    private final JTextField search = new JTextField(24);
    private final JLabel status = new JLabel("Loading active borrowers…");
    private final DefaultTableModel model = new DefaultTableModel(new Object[] {
            "Request ID", "Borrower", "Department", "Room/Venue", "Reservation", "Time",
            "Status", "Released", "In use"
    }, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private List<EquipmentUsageRecord> records = List.of();

    public EquipmentUsageDialog(
            Component parent, Equipment equipment, User actor,
            EquipmentController equipmentController) {
        super(JOptionPaneHolder.parentWindow(parent), "Equipment usage",
                java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        this.equipment = equipment;
        this.actor = actor;
        this.equipmentController = equipmentController;
        setBackground(AppTheme.surface());
        setSize(new Dimension(1000, 540));
        setLocationRelativeTo(parent);

        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(AppTheme.surface());
        root.setBorder(new javax.swing.border.EmptyBorder(22, 24, 22, 24));
        setContentPane(root);

        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Active borrowers · " + equipment.getEquipmentName());
        title.setFont(AppTheme.semibold(18));
        title.setForeground(AppTheme.foreground());
        JLabel subtitle = new JLabel(equipment.getEquipmentCode() + "  ·  "
                + equipment.getCategory() + "  ·  "
                + equipment.getInUseQuantity() + " unit(s) released and not yet returned");
        subtitle.setFont(AppTheme.regular(11));
        subtitle.setForeground(AppTheme.muted());
        heading.add(title);
        heading.add(Box.createVerticalStrut(4));
        heading.add(subtitle);
        root.add(heading, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.setOpaque(false);
        JPanel tools = new JPanel(new BorderLayout(10, 0));
        tools.setOpaque(false);
        UiSupport.styleInput(search);
        search.putClientProperty("JTextField.placeholderText",
                "Search borrower, department, room/venue, or request ID");
        search.putClientProperty("JTextField.showClearButton", true);
        search.getDocument().addDocumentListener(UiSupport.documentListener(this::applyFilter));
        tools.add(search, BorderLayout.CENTER);
        PrimaryButton close = new PrimaryButton("Close", PrimaryButton.Style.SECONDARY);
        close.addActionListener(event -> dispose());
        tools.add(close, BorderLayout.EAST);
        center.add(tools, BorderLayout.NORTH);

        JTable table = new JTable(model);
        table.setFont(AppTheme.regular(11));
        table.setRowHeight(48);
        table.setGridColor(AppTheme.border());
        table.setBackground(AppTheme.surface());
        table.setForeground(AppTheme.foreground());
        table.setSelectionBackground(AppTheme.primarySoft());
        table.setSelectionForeground(AppTheme.foreground());
        table.getTableHeader().setFont(AppTheme.semibold(11));
        table.getTableHeader().setBackground(AppTheme.tableHeaderBackground());
        table.getTableHeader().setForeground(AppTheme.muted());
        table.getTableHeader().setReorderingAllowed(false);
        int[] widths = { 130, 150, 170, 150, 126, 120, 110, 92, 82 };
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setCellRenderer(new WrappingRenderer());
        }
        JScrollPane scroll = new JScrollPane(table);
        scroll.getViewport().setBackground(AppTheme.surface());
        center.add(scroll, BorderLayout.CENTER);

        status.setFont(AppTheme.regular(11));
        status.setForeground(AppTheme.muted());
        center.add(status, BorderLayout.SOUTH);
        root.add(center, BorderLayout.CENTER);
        load();
    }

    private void load() {
        new SwingWorker<List<EquipmentUsageRecord>, Void>() {
            @Override
            protected List<EquipmentUsageRecord> doInBackground() throws Exception {
                return equipmentController.findActiveBorrowers(actor, equipment.getEquipmentId());
            }

            @Override
            protected void done() {
                try {
                    records = get();
                    applyFilter();
                } catch (Exception exception) {
                    Throwable cause = exception instanceof ExecutionException
                            && exception.getCause() != null
                                    ? exception.getCause() : exception;
                    records = List.of();
                    model.setRowCount(0);
                    status.setText("Unable to load usage: " + cause.getMessage());
                    status.setForeground(AppTheme.danger());
                }
            }
        }.execute();
    }

    private void applyFilter() {
        String query = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        model.setRowCount(0);
        int shown = 0;
        for (EquipmentUsageRecord record : records) {
            if (!matches(record, query)) {
                continue;
            }
            model.addRow(new Object[] {
                    requestIdText(record),
                    blank(record.getBorrowerName()),
                    departmentText(record),
                    blank(record.getVenue()),
                    record.getEventDate() == null ? "—" : record.getEventDate().format(DATE),
                    timeRange(record),
                    blank(record.getGroupStatus()),
                    Integer.toString(record.getReleasedQuantity()),
                    Integer.toString(record.getInUseQuantity())
            });
            shown++;
        }
        status.setText(shown == 0 && !records.isEmpty()
                ? "No active borrowers match the current search."
                : records.isEmpty() ? "No active borrowers for this equipment."
                : shown + " active hand-over(s) shown");
        status.setForeground(AppTheme.muted());
    }

    private static boolean matches(EquipmentUsageRecord record, String query) {
        if (query.isEmpty()) {
            return true;
        }
        return text(record).contains(query);
    }

    private static String text(EquipmentUsageRecord record) {
        StringBuilder value = new StringBuilder()
                .append('#').append(record.getRequestId()).append(' ')
                .append(blank(record.getRequestCode())).append(' ')
                .append(blank(record.getBorrowerName())).append(' ')
                .append(blank(record.getBorrowerType())).append(' ')
                .append(blank(record.getDepartment())).append(' ')
                .append(blank(record.getVenue())).append(' ')
                .append(blank(record.getGroupStatus())).append(' ')
                .append(record.getEventDate()).append(' ')
                .append(record.getStartTime()).append(' ')
                .append(record.getEndTime()).append(' ')
                .append(record.getReleasedAt()).append(' ')
                .append(record.getInUseQuantity());
        return value.toString().toLowerCase(Locale.ROOT);
    }

    private static String requestIdText(EquipmentUsageRecord record) {
        String id = "#" + record.getRequestId();
        return record.getRequestCode() == null || record.getRequestCode().isBlank()
                ? id : record.getRequestCode() + "\n" + id;
    }

    private static String departmentText(EquipmentUsageRecord record) {
        String type = blank(record.getBorrowerType());
        String department = blank(record.getDepartment());
        return "—".equals(type) ? department
                : "—".equals(department) ? type : type + " · " + department;
    }

    private static String timeRange(EquipmentUsageRecord record) {
        if (record.getStartTime() == null || record.getEndTime() == null) {
            return "—";
        }
        return record.getStartTime().format(TIME) + " – " + record.getEndTime().format(TIME);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static final class WrappingRenderer extends javax.swing.JTextArea
            implements javax.swing.table.TableCellRenderer {

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

    /** Small helper so the dialog can accept any Swing parent component. */
    private static final class JOptionPaneHolder {
        static java.awt.Window parentWindow(Component component) {
            if (component == null) {
                return null;
            }
            java.awt.Window window = javax.swing.SwingUtilities.getWindowAncestor(component);
            return window == null ? null : window;
        }
    }
}
