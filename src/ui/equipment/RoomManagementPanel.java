package src.ui.equipment;

import src.ui.AppTheme;
import src.ui.UiSupport;
import src.model.User;
import src.ui.components.PrimaryButton;
import src.ui.components.SurfacePanel;
import src.ui.components.VenueOptions;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** ICT-only page that keeps the request-form Venue dropdown current. */
public class RoomManagementPanel extends JPanel {

    /** Rooms such as "Room 2" stay in numeric order rather than alphabetical order. */
    private static final Comparator<String> ROOM_ORDER = (left, right) -> {
        String[] first = left.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
        String[] second = right.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
        int shared = Math.min(first.length, second.length);
        for (int i = 0; i < shared; i++) {
            boolean firstNumber = first[i].chars().allMatch(Character::isDigit);
            boolean secondNumber = second[i].chars().allMatch(Character::isDigit);
            if (firstNumber && secondNumber) {
                try {
                    int byNumber = Long.compare(
                            Long.parseLong(first[i]), Long.parseLong(second[i]));
                    if (byNumber != 0) {
                        return byNumber;
                    }
                    continue;
                } catch (NumberFormatException ignored) {
                    // Very long identifiers fall through to a text comparison.
                }
            }
            int byText = first[i].compareToIgnoreCase(second[i]);
            if (byText != 0) {
                return byText;
            }
        }
        return Integer.compare(first.length, second.length);
    };

    private final User actor;
    private final JTextField roomInput = new JTextField(24);
    private final JPanel roomGrid = new VenueGrid(new GridBagLayout());
    private final JLabel roomCount = new JLabel("0 venues");

    public RoomManagementPanel(User actor) {
        this.actor = actor;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(AppTheme.background());
        setBorder(new EmptyBorder(2, 2, 22, 2));
        addHeader();
        add(Box.createVerticalStrut(14));

        SurfacePanel listCard = new SurfacePanel();
        listCard.setLayout(new BorderLayout(10, 10));
        listCard.setAlignmentX(LEFT_ALIGNMENT);
        listCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 500));

        JPanel sectionHeading = new JPanel(new BorderLayout());
        sectionHeading.setOpaque(false);
        JLabel saved = new JLabel("Saved venues");
        saved.setFont(AppTheme.semibold(13));
        saved.setForeground(AppTheme.foreground());
        roomCount.setFont(AppTheme.regular(11));
        roomCount.setForeground(AppTheme.muted());
        roomCount.setHorizontalAlignment(SwingConstants.RIGHT);
        sectionHeading.add(saved, BorderLayout.WEST);
        sectionHeading.add(roomCount, BorderLayout.EAST);
        listCard.add(sectionHeading, BorderLayout.NORTH);

        roomGrid.setOpaque(false);
        roomGrid.setAlignmentX(LEFT_ALIGNMENT);
        roomGrid.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                roomGrid.revalidate();
            }
        });

        JScrollPane scroller = new JScrollPane(roomGrid);
        scroller.setOpaque(false);
        scroller.getViewport().setOpaque(false);
        scroller.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        // Always show the vertical bar so it is obvious that the list scrolls.
        scroller.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scroller.setBorder(new EmptyBorder(2, 2, 2, 2));
        scroller.setPreferredSize(new Dimension(0, 360));
        scroller.getVerticalScrollBar().setUnitIncrement(18);
        listCard.add(scroller, BorderLayout.CENTER);
        add(listCard);
        reloadRooms();
    }

    private void addHeader() {
        JPanel header = new JPanel(new BorderLayout(8, 8));
        header.setOpaque(false);
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Venue management");
        title.setFont(AppTheme.semibold(18));
        title.setForeground(AppTheme.foreground());
        JLabel subtitle = new JLabel(
                "Add rooms here to make them available immediately in the request-form Venue dropdown.");
        subtitle.setFont(AppTheme.regular(11));
        subtitle.setForeground(AppTheme.muted());
        text.add(title);
        text.add(Box.createVerticalStrut(4));
        text.add(subtitle);
        header.add(text, BorderLayout.NORTH);

        UiSupport.styleInput(roomInput);
        roomInput.putClientProperty("JTextField.placeholderText", "Enter a room or venue name");
        PrimaryButton add = new PrimaryButton("Add room");
        add.addActionListener(event -> addRoom(add));
        roomInput.addActionListener(event -> addRoom(add));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controls.setOpaque(false);
        controls.add(roomInput);
        controls.add(add);
        header.add(controls, BorderLayout.CENTER);
        header.setAlignmentX(LEFT_ALIGNMENT);
        add(header);
    }

    private void addRoom(javax.swing.AbstractButton source) {
        if (!UiSupport.beginAction(source)) {
            return;
        }
        String room = roomInput.getText() == null ? "" : roomInput.getText().trim();
        if (room.isEmpty()) {
            UiSupport.showMessageDialog(this, "Enter a room or venue name.",
                    "Room required", JOptionPane.WARNING_MESSAGE);
            UiSupport.endAction(source);
            return;
        }
        try {
            boolean added = VenueOptions.addRoom(room, actor == null ? "" : actor.getRole());
            roomInput.setText("");
            reloadRooms();
            UiSupport.showMessageDialog(this,
                    added ? "Room added to the request-form Venue dropdown."
                            : "That room is already in the Venue dropdown.",
                    added ? "Room added" : "Room already exists",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception exception) {
            UiSupport.showMessageDialog(this,
                    "Unable to add room: " + exception.getMessage(), "Room not added",
                    JOptionPane.ERROR_MESSAGE);
        } finally {
            UiSupport.endAction(source);
        }
    }

    private void reloadRooms() {
        roomGrid.removeAll();
        List<String> rooms = new ArrayList<>(VenueOptions.load());
        rooms.sort(ROOM_ORDER);
        roomCount.setText(rooms.size() + (rooms.size() == 1 ? " venue" : " venues"));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0.5;
        c.weighty = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTH;
        c.insets = new Insets(0, 0, 10, 0);
        if (rooms.isEmpty()) {
            JLabel empty = new JLabel("No venues saved yet. Add one above.");
            empty.setFont(AppTheme.regular(12));
            empty.setForeground(AppTheme.muted());
            empty.setHorizontalAlignment(SwingConstants.CENTER);
            c.gridwidth = 2;
            c.weightx = 1;
            roomGrid.add(empty, c);
        } else {
            for (int i = 0; i < rooms.size(); i++) {
                c.gridx = i % 2;
                c.gridy = i / 2;
                roomGrid.add(venueChip(rooms.get(i), i + 1), c);
            }
        }
        roomGrid.revalidate();
        roomGrid.repaint();
        if (roomGrid.getParent() != null) {
            roomGrid.getParent().revalidate();
        }
    }

    /** One uniform venue card: index badge, name, and aligned edit/delete actions. */
    private JPanel venueChip(String room, int index) {
        JPanel chip = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(AppTheme.elevatedSurface());
                g.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 16, 16);
                g.setColor(AppTheme.border());
                g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 16, 16);
                g.setColor(AppTheme.primaryInk());
                g.fillRoundRect(0, 10, 5, getHeight() - 20, 5, 5);
                g.dispose();
                super.paintComponent(graphics);
            }
        };
        chip.setOpaque(false);
        chip.setBorder(new EmptyBorder(12, 15, 12, 17));
        chip.setToolTipText(room);

        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        JLabel badge = new JLabel(String.format("%02d", index));
        badge.setFont(AppTheme.semibold(11));
        badge.setForeground(AppTheme.primaryInk());
        c.gridx = 0;
        c.gridy = 0;
        c.insets = new Insets(0, 0, 0, 12);
        chip.add(badge, c);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel name = new JLabel(room);
        name.setFont(AppTheme.semibold(12));
        name.setForeground(AppTheme.foreground());
        JLabel note = new JLabel("Available in request form");
        note.setFont(AppTheme.regular(10));
        note.setForeground(AppTheme.muted());
        text.add(name);
        text.add(Box.createVerticalStrut(2));
        text.add(note);
        c.gridx = 1;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 0);
        chip.add(text, c);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        PrimaryButton editBtn = new PrimaryButton("Edit", PrimaryButton.Style.SECONDARY);
        editBtn.setToolTipText("Edit room name");
        editBtn.addActionListener(e -> editRoom(room, editBtn));
        PrimaryButton delBtn = new PrimaryButton("Delete", PrimaryButton.Style.DANGER);
        delBtn.setToolTipText("Delete this room");
        delBtn.addActionListener(e -> deleteRoom(room, delBtn));
        actions.add(editBtn);
        actions.add(delBtn);
        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.EAST;
        c.insets = new Insets(10, 0, 0, 0);
        chip.add(actions, c);
        return chip;
    }

    private void editRoom(String oldName, javax.swing.AbstractButton source) {
        if (!UiSupport.beginAction(source)) {
            return;
        }
        try {
            String newName = UiSupport.showInputDialog(this, "Edit room name:", "Edit Room",
                    JOptionPane.PLAIN_MESSAGE, oldName);
            if (newName == null) {
                return; // Cancel or X: no change.
            }
            newName = newName.trim();
            if (newName.isEmpty()) {
                UiSupport.showMessageDialog(this, "Room name cannot be empty.", "Invalid name",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            VenueOptions.updateRoom(oldName, newName, actor == null ? "" : actor.getRole());
            reloadRooms();
            UiSupport.showMessageDialog(this,
                    "Room updated. It will appear in the request form Venue dropdown.",
                    "Room updated", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            UiSupport.showMessageDialog(this, "Unable to edit room: " + ex.getMessage(),
                    "Edit failed", JOptionPane.ERROR_MESSAGE);
        } finally {
            UiSupport.endAction(source);
        }
    }

    private void deleteRoom(String roomName, javax.swing.AbstractButton source) {
        if (!UiSupport.beginAction(source)) {
            return;
        }
        int confirm = UiSupport.showConfirmDialog(this,
                "Delete room \"" + roomName + "\"? It will be removed from the Venue dropdown.",
                "Delete Room", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            UiSupport.endAction(source); // Cancel or X: no change.
            return;
        }
        try {
            boolean ok = VenueOptions.deleteRoom(
                    roomName, actor == null ? "" : actor.getRole());
            if (ok) {
                reloadRooms();
                UiSupport.showMessageDialog(this, "Room deleted.", "Room deleted",
                        JOptionPane.INFORMATION_MESSAGE);
            } else {
                UiSupport.showMessageDialog(this, "Room not found.", "Not found",
                        JOptionPane.WARNING_MESSAGE);
            }
        } catch (Exception ex) {
            String msg = ex.getMessage();
            if (msg == null || msg.isBlank()) {
                msg = ex.getClass().getSimpleName();
            }
            UiSupport.showMessageDialog(this, "Unable to delete room: " + msg, "Delete failed",
                    JOptionPane.ERROR_MESSAGE);
        } finally {
            UiSupport.endAction(source);
        }
    }

    /** Wrapping grid that fills the viewport width so chips flow instead of scrolling sideways. */
    private static final class VenueGrid extends JPanel implements javax.swing.Scrollable {

        VenueGrid(LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(40, visible.height - 36);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
