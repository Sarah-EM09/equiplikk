package src.ui.requests;

import src.ui.AppTheme;
import src.ui.UiSupport;
import src.model.BorrowRequest;
import src.model.EquipmentRequestItem;
import src.model.ItemInspection;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.EmptyBorder;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Collects per-item staff input for assignment, release, and return inspection. */
public final class ItemInspectionDialog {

    public enum Kind { ASSIGN, RELEASE, RETURN }

    static final String[] CONDITIONS = {
            "Good Condition", "Fair", "Minor wear", "Damaged",
            "Incomplete", "Not working"
    };

    private ItemInspectionDialog() {
    }

    /** Returns the collected input, or null when cancelled. */
    public static List<ItemInspection> show(
            Component parent, BorrowRequest request, String role, Kind kind) {
        return show(parent, request, role, kind, Map.of(), null);
    }

    public static List<ItemInspection> show(
            Component parent, BorrowRequest request, String role, Kind kind, Long groupId) {
        return show(parent, request, role, kind, Map.of(), groupId);
    }

    /**
     * @param availability schedule-aware maximum assignable units by equipment id.
     * @param groupId optional reservation group to restrict the form to one schedule.
     */
    public static List<ItemInspection> show(
            Component parent, BorrowRequest request, String role, Kind kind,
            Map<Long, Integer> availability) {
        return show(parent, request, role, kind, availability, null);
    }

    public static List<ItemInspection> show(
            Component parent, BorrowRequest request, String role, Kind kind,
            Map<Long, Integer> availability, Long groupId) {
        Map<Long, Integer> limits = availability == null ? Map.of() : availability;
        List<EquipmentRequestItem> grouped = request.getGroupEquipmentItems();
        List<EquipmentRequestItem> source = grouped;
        if (groupId == null && kind != Kind.ASSIGN) {
            source = request.getEquipmentItems();
        }
        if (source == null || source.isEmpty()) {
            source = request.getEquipmentItems();
        }
        boolean perGroupAssignment = kind == Kind.ASSIGN && groupId == null
                && source == grouped && grouped != null && !grouped.isEmpty();
        List<EquipmentRequestItem> items = new ArrayList<>();
        for (EquipmentRequestItem item : source) {
            if (!role.equalsIgnoreCase(item.getCustodianRole())) {
                continue;
            }
            if (perGroupAssignment && !"PENDING".equalsIgnoreCase(item.getGroupStatus())) {
                continue;
            }
            if (groupId != null && item.getGroupId() != null
                    && !item.getGroupId().equals(groupId)) {
                continue;
            }
            if (kind == Kind.RELEASE && item.getAssignedQuantity() == 0) {
                continue;
            }
            if (kind == Kind.RETURN && item.getReleasedQuantity() == 0) {
                continue;
            }
            items.add(item);
        }
        if (items.isEmpty()) {
            UiSupport.showMessageDialog(parent, "There are no items to process.",
                    "Nothing to process", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(6, 2, 6, 2));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        String[] headers = switch (kind) {
            case ASSIGN -> new String[] { "Equipment", "Req / avail", "Assign", "Note" };
            case RELEASE -> new String[] { "Equipment", "Qty", "Condition before", "Remarks" };
            case RETURN -> new String[] {
                    "Equipment", "Released", "Condition after", "Damaged", "Missing", "Remarks" };
        };
        c.gridy = 0;
        for (int i = 0; i < headers.length; i++) {
            c.gridx = i;
            JLabel header = new JLabel(headers[i]);
            header.setFont(AppTheme.semibold(11));
            form.add(header, c);
        }

        Map<Long, Integer> rowsPerEquipment = new HashMap<>();
        for (EquipmentRequestItem item : items) {
            rowsPerEquipment.merge(item.getEquipmentId(), 1, Integer::sum);
        }
        Map<Long, src.model.RequestOccurrence> occurrencesById = new HashMap<>();
        if (request.getOccurrences() != null) {
            for (src.model.RequestOccurrence occurrence : request.getOccurrences()) {
                occurrencesById.put(occurrence.getOccurrenceId(), occurrence);
            }
        }

        List<JSpinner> quantities = new ArrayList<>();
        List<JComboBox<String>> conditions = new ArrayList<>();
        List<JSpinner> damaged = new ArrayList<>();
        List<JSpinner> missing = new ArrayList<>();
        List<JTextField> remarks = new ArrayList<>();

        for (EquipmentRequestItem item : items) {
            c.gridy++;
            c.gridx = 0;
            String label = item.getEquipmentName()
                    + " (" + item.getEquipmentCode() + ")";
            if (rowsPerEquipment.getOrDefault(item.getEquipmentId(), 0) > 1) {
                src.model.RequestOccurrence occurrence =
                        occurrencesById.get(item.getOccurrenceId());
                if (occurrence != null) {
                    label += " — "
                            + occurrence.getEventDate().format(
                                    java.time.format.DateTimeFormatter.ofPattern("MMM d"));
                }
            }
            form.add(new JLabel(label), c);
            c.gridx = 1;
            int base = switch (kind) {
                case ASSIGN -> item.getRequestedQuantity();
                case RELEASE -> item.getAssignedQuantity();
                case RETURN -> item.getReleasedQuantity();
            };
            form.add(new JLabel(kind == Kind.ASSIGN
                    ? base + " / " + limits.getOrDefault(item.getEquipmentId(), base)
                    : String.valueOf(base)), c);
            c.gridx = 2;
            if (kind == Kind.ASSIGN) {
                int max = Math.min(base, limits.getOrDefault(item.getEquipmentId(), base));
                JSpinner quantity = spinner(Math.min(base, max), max);
                quantities.add(quantity);
                form.add(quantity, c);
            } else {
                JComboBox<String> condition = new JComboBox<>(CONDITIONS);
                condition.setEditable(true);
                conditions.add(condition);
                form.add(condition, c);
            }
            if (kind == Kind.RETURN) {
                c.gridx = 3;
                JSpinner d = spinner(0, base);
                damaged.add(d);
                form.add(d, c);
                c.gridx = 4;
                JSpinner m = spinner(0, base);
                missing.add(m);
                form.add(m, c);
            }
            c.gridx = headers.length - 1;
            JTextField note = new JTextField(14);
            remarks.add(note);
            form.add(note, c);
        }

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = headers.length;
        JLabel hint = new JLabel(switch (kind) {
            case ASSIGN -> "Assign up to the available units; set 0 if unable to fulfill. A partial assignment requires a note.";
            case RELEASE -> "Record each item's condition before handing it over.";
            case RETURN -> "Damaged items stay in stock but are excluded from availability.";
        });
        hint.setFont(AppTheme.regular(10));
        hint.setForeground(AppTheme.muted());
        form.add(hint, c);

        JComponent content = form;
        if (items.size() > 6) {
            JScrollPane scroll = new JScrollPane(form);
            scroll.setPreferredSize(new Dimension(760, 360));
            content = scroll;
        }

        String title = switch (kind) {
            case ASSIGN -> "Assign equipment";
            case RELEASE -> "Confirm release (deducts inventory)";
            case RETURN -> "Return inspection";
        };
        while (true) {
            int answer = UiSupport.showConfirmDialog(parent, content, title,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) {
                return null;
            }
            List<ItemInspection> result = new ArrayList<>();
            String error = null;
            for (int i = 0; i < items.size(); i++) {
                EquipmentRequestItem item = items.get(i);
                String note = remarks.get(i).getText();
                if (kind == Kind.ASSIGN) {
                    int quantity = (Integer) quantities.get(i).getValue();
                    if (quantity > 0 && quantity < item.getRequestedQuantity()
                            && (note == null || note.isBlank())) {
                        error = "Enter a staff remark explaining the partial assignment for "
                                + item.getEquipmentName() + ".";
                        break;
                    }
                    result.add(ItemInspection.assignment(
                            item.getEquipmentId(), quantity, note, item.getGroupId()));
                    continue;
                }
                Object selected = conditions.get(i).getSelectedItem();
                String condition = selected == null ? "" : selected.toString().trim();
                if (condition.isEmpty()) {
                    error = "Enter a condition for " + item.getEquipmentName() + ".";
                    break;
                }
                if (kind == Kind.RELEASE) {
                    result.add(ItemInspection.release(item.getEquipmentId(), condition, note));
                } else {
                    int d = (Integer) damaged.get(i).getValue();
                    int m = (Integer) missing.get(i).getValue();
                    if (d + m > item.getReleasedQuantity()) {
                        error = "Damaged + missing for " + item.getEquipmentName()
                                + " cannot exceed " + item.getReleasedQuantity() + ".";
                        break;
                    }
                    result.add(ItemInspection.returned(
                            item.getEquipmentId(), condition, d, m, note));
                }
            }
            if (error == null) {
                return result;
            }
            UiSupport.showMessageDialog(parent, error, "Check the details",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private static JSpinner spinner(int value, int max) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, 0, max, 1));
        spinner.setPreferredSize(new Dimension(70, spinner.getPreferredSize().height));
        return spinner;
    }
}
