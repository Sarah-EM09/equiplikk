package src.ui.account;

import src.ui.AppTheme;
import src.ui.UiSupport;
import src.controller.AuthController;
import src.controller.BorrowRequestController;
import src.model.User;
import src.ui.components.SurfacePanel;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;

public class UserManagementPanel extends JPanel {

    private final User actor;
    private final AuthController authController;
    private final BorrowRequestController requestController;
    private final JComboBox<String> statusFilter = new JComboBox<>(
            new String[] { "All accounts", "Active", "Deleted accounts" });
    private final JComboBox<String> borrowerType = new JComboBox<>(
            new String[] { "All users", "Faculty", "Student" });
    private final JComboBox<String> department = new JComboBox<>();
    private final JTextField search = new JTextField(20);
    private final JPanel listPanel = new JPanel();
    private final JPanel usersList = new JPanel();
    private List<User> users = List.of();
    private boolean navigatingToDetails;

    public UserManagementPanel(
            User actor,
            AuthController authController,
            BorrowRequestController requestController) {
        this.actor = actor;
        this.authController = authController;
        this.requestController = requestController;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(AppTheme.background());
        setBorder(new EmptyBorder(2, 2, 22, 2));
        listPanel.setOpaque(false);
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        addHeader();
        usersList.setOpaque(false);
        usersList.setLayout(new BoxLayout(usersList, BoxLayout.Y_AXIS));
        listPanel.add(usersList);
        add(listPanel);
        loadUsers();
    }

    private void addHeader() {
        JLabel title = new JLabel("Borrower accounts");
        title.setFont(AppTheme.semibold(18));
        title.setForeground(AppTheme.foreground());
        JLabel subtitle = new JLabel(
                "Students self-register. Faculty accounts are created here and get priority handling.");
        subtitle.setFont(AppTheme.regular(11));
        subtitle.setForeground(AppTheme.muted());
        JPanel titleBar = new JPanel(new BorderLayout(12, 0));
        titleBar.setOpaque(false);
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.add(title);
        titles.add(Box.createVerticalStrut(4));
        titles.add(subtitle);
        titleBar.add(titles, BorderLayout.CENTER);
        src.ui.components.PrimaryButton createFaculty =
                new src.ui.components.PrimaryButton("Create faculty account");
        createFaculty.addActionListener(event -> createFacultyAccount(createFaculty));
        JPanel actionWrap = new JPanel(new java.awt.GridBagLayout());
        actionWrap.setOpaque(false);
        actionWrap.add(createFaculty);
        titleBar.add(actionWrap, BorderLayout.EAST);
        listPanel.add(titleBar);
        listPanel.add(Box.createVerticalStrut(12));

        JPanel filters = new JPanel(new BorderLayout(8, 0));
        filters.setOpaque(false);
        borrowerType.setFont(AppTheme.regular(11));
        borrowerType.addActionListener(event -> showUsers());
        department.setFont(AppTheme.regular(11));
        department.addActionListener(event -> showUsers());
        statusFilter.setFont(AppTheme.regular(11));
        statusFilter.addActionListener(event -> showUsers());
        JPanel selects = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        selects.setOpaque(false);
        selects.add(statusFilter);
        selects.add(borrowerType);
        selects.add(department);
        filters.add(selects, BorderLayout.WEST);
        UiSupport.styleInput(search);
        search.getDocument().addDocumentListener(UiSupport.documentListener(this::showUsers));
        filters.add(search, BorderLayout.CENTER);
        listPanel.add(filters);
        listPanel.add(Box.createVerticalStrut(14));
    }

    private void loadUsers() {
        usersList.removeAll();
        usersList.add(message("Loading borrower accounts…", false));
        new SwingWorker<List<User>, Void>() {
            @Override
            protected List<User> doInBackground() throws Exception {
                return authController.findBorrowersForIct(actor);
            }

            @Override
            protected void done() {
                try {
                    users = get();
                    updateDepartments();
                    showUsers();
                } catch (Exception exception) {
                    usersList.removeAll();
                    usersList.add(message(
                            "Unable to load borrower accounts: " + messageOf(exception), true));
                    usersList.revalidate();
                    usersList.repaint();
                }
            }
        }.execute();
    }

    private void updateDepartments() {
        String selected = (String) department.getSelectedItem();
        Set<String> departments = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        departments.add("All departments");
        for (User user : users) {
            if (user.getDepartment() != null && !user.getDepartment().isBlank()) {
                departments.add(user.getDepartment().trim());
            }
        }
        department.removeAllItems();
        for (String value : departments) {
            department.addItem(value);
        }
        if (selected != null) {
            department.setSelectedItem(departments.contains(selected)
                    ? selected : "All departments");
        }
    }

    private void showUsers() {
        usersList.removeAll();
        String selectedStatus = (String) statusFilter.getSelectedItem();
        String selectedType = (String) borrowerType.getSelectedItem();
        String selectedDepartment = (String) department.getSelectedItem();
        String query = search.getText().trim().toLowerCase();
        List<User> filtered = new ArrayList<>();
        for (User user : users) {
            if ("Active".equals(selectedStatus) && user.isDeleted()) {
                continue;
            }
            if ("Deleted accounts".equals(selectedStatus) && !user.isDeleted()) {
                continue;
            }
            if ("Faculty".equals(selectedType)
                    && !"FACULTY".equals(user.getBorrowerType())) {
                continue;
            }
            if ("Student".equals(selectedType)
                    && !"STUDENT".equals(user.getBorrowerType())) {
                continue;
            }
            if (selectedDepartment != null
                    && !"All departments".equals(selectedDepartment)
                    && !selectedDepartment.equalsIgnoreCase(
                            user.getDepartment() == null ? "" : user.getDepartment().trim())) {
                continue;
            }
            String searchable = user.getFullName() + " " + user.getUsername()
                    + " " + user.getEmail() + " " + user.getDepartment();
            if (searchable.toLowerCase().contains(query)) {
                filtered.add(user);
            }
        }
        if (filtered.isEmpty()) {
            usersList.add(message("No accounts match the selected filters.", false));
        } else {
            for (User user : filtered) {
                usersList.add(userCard(user));
                usersList.add(Box.createVerticalStrut(8));
            }
        }
        usersList.revalidate();
        usersList.repaint();
    }

    private JPanel userCard(User user) {
        SurfacePanel card = new SurfacePanel();
        card.setLayout(new BorderLayout(12, 4));
        JLabel name = new JLabel(user.getFullName());
        name.setFont(AppTheme.semibold(12));
        name.setForeground(AppTheme.foreground());
        String info = user.getUsername() + "  ·  " + user.getEmail() + "  ·  "
                + user.getBorrowerType()
                + (user.getDepartment() == null ? "" : "  ·  " + user.getDepartment())
                + (user.isDeleted() && user.getDeletedAt() != null
                        ? "  ·  deleted " + user.getDeletedAt().toLocalDate() : "");
        JLabel meta = new JLabel(info);
        meta.setFont(AppTheme.regular(10));
        meta.setForeground(AppTheme.muted());
        JPanel detail = new JPanel();
        detail.setOpaque(false);
        detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
        detail.add(name);
        detail.add(Box.createVerticalStrut(3));
        detail.add(meta);
        JLabel status = new JLabel(user.isDeleted() ? "Deleted" : "Active");
        status.setFont(AppTheme.semibold(10.5f));
        status.setForeground(user.isDeleted() ? AppTheme.danger() : AppTheme.success());
        src.ui.components.PrimaryButton details = new src.ui.components.PrimaryButton(
                "Details", src.ui.components.PrimaryButton.Style.SECONDARY);
        details.addActionListener(event -> openAccountDetails(user));
        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
        status.setAlignmentX(java.awt.Component.RIGHT_ALIGNMENT);
        details.setAlignmentX(java.awt.Component.RIGHT_ALIGNMENT);
        actions.add(status);
        actions.add(Box.createVerticalStrut(6));
        actions.add(details);

        if (!user.isDeleted()) {
            src.ui.components.PrimaryButton delete = new src.ui.components.PrimaryButton(
                    "Delete Account", src.ui.components.PrimaryButton.Style.DANGER);
            delete.addActionListener(event -> deleteBorrower(user, delete));
            delete.setAlignmentX(java.awt.Component.RIGHT_ALIGNMENT);
            actions.add(Box.createVerticalStrut(5));
            actions.add(delete);
        } else {
            src.ui.components.PrimaryButton restore = new src.ui.components.PrimaryButton(
                    "Restore Account", src.ui.components.PrimaryButton.Style.PRIMARY);
            restore.addActionListener(event -> restoreBorrower(user, restore));
            restore.setAlignmentX(java.awt.Component.RIGHT_ALIGNMENT);
            actions.add(Box.createVerticalStrut(5));
            actions.add(restore);
        }
        card.add(detail, BorderLayout.CENTER);
        card.add(actions, BorderLayout.EAST);
        attachCardClick(card, user);
        return card;
    }

    /** Makes the whole card clickable while leaving its action buttons independent. */
    private void attachCardClick(javax.swing.JComponent component, User user) {
        if (component instanceof javax.swing.AbstractButton) {
            return;
        }
        component.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        component.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (!fromButton(event)) {
                    openAccountDetails(user);
                }
            }
        });
        for (java.awt.Component child : component.getComponents()) {
            if (child instanceof javax.swing.JComponent jchild) {
                attachCardClick(jchild, user);
            }
        }
    }

    private boolean fromButton(java.awt.event.MouseEvent event) {
        java.awt.Component source = event.getComponent();
        while (source != null && source != this) {
            if (source instanceof javax.swing.AbstractButton) {
                return true;
            }
            source = source.getParent();
        }
        return false;
    }

    private void openAccountDetails(User user) {
        // Mouse events can reach both a child label and the parent card; navigate once.
        if (navigatingToDetails) {
            return;
        }
        navigatingToDetails = true;
        try {
            removeAll();
            add(new AccountDetailsPanel(
                    actor, user, authController, requestController,
                    this::showAccountList));
            revalidate();
            repaint();
            // Reset now and again after Swing's deferred layout/focus passes.
            // AccountDetailsPanel also resets once its history finishes loading.
            resetScroll();
            UiSupport.scrollToTopAfterLayout(this);
        } finally {
            navigatingToDetails = false;
        }
    }

    private void showAccountList() {
        removeAll();
        add(listPanel);
        showUsers();
        resetScroll();
        revalidate();
        repaint();
        UiSupport.scrollToTopAfterLayout(this);
    }

    private void resetScroll() {
        UiSupport.scrollToTop(this);
    }

    private void deleteBorrower(User user, src.ui.components.PrimaryButton actionButton) {
        if (!UiSupport.beginAction(actionButton)) {
            return;
        }
        int answer = UiSupport.showConfirmDialog(
                this,
                "Delete " + user.getFullName() + " (" + user.getUsername() + ")?\n\n"
                        + "The account will be disabled and can no longer sign in. All "
                        + "requests, approvals, notifications, and history are preserved.",
                "Delete borrower account",
                javax.swing.JOptionPane.YES_NO_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE);
        if (answer != javax.swing.JOptionPane.YES_OPTION) {
            UiSupport.endAction(actionButton);
            return;
        }

        actionButton.setText("Deleting...");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                java.time.LocalDateTime deletedAt =
                        authController.deleteBorrowerAccount(actor, user.getUserId());
                user.setActive(false);
                user.setDeletedAt(deletedAt);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    loadUsers();
                    UiSupport.showMessageDialog(
                            UserManagementPanel.this,
                            user.getFullName() + " account deleted. History is preserved.",
                            "Account deleted",
                            javax.swing.JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception exception) {
                    UiSupport.showMessageDialog(
                            UserManagementPanel.this,
                            messageOf(exception),
                            "Unable to delete account",
                            javax.swing.JOptionPane.WARNING_MESSAGE);
                } finally {
                    actionButton.setText("Delete Account");
                    UiSupport.endAction(actionButton);
                }
            }
        }.execute();
    }

    private void restoreBorrower(User user, src.ui.components.PrimaryButton actionButton) {
        if (!UiSupport.beginAction(actionButton)) {
            return;
        }
        Object[] options = { "Cancel", "Restore Account" };
        int answer = UiSupport.showOptionDialog(
                this,
                "Are you sure you want to restore this borrower account? "
                        + "The user will be able to log in to EquipLink again.",
                "Restore Account?",
                javax.swing.JOptionPane.QUESTION_MESSAGE,
                options, options[0]);
        if (answer != 1) {
            UiSupport.endAction(actionButton);
            return;
        }

        actionButton.setText("Restoring...");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                authController.restoreBorrowerAccount(actor, user.getUserId());
                user.setActive(true);
                user.setDeletedAt(null);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    loadUsers();
                    UiSupport.showMessageDialog(
                            UserManagementPanel.this,
                            user.getFullName() + " has been restored and can log in again.",
                            "Account restored",
                            javax.swing.JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception exception) {
                    UiSupport.showMessageDialog(
                            UserManagementPanel.this,
                            messageOf(exception),
                            "Unable to restore account",
                            javax.swing.JOptionPane.WARNING_MESSAGE);
                } finally {
                    actionButton.setText("Restore Account");
                    UiSupport.endAction(actionButton);
                }
            }
        }.execute();
    }

    private void createFacultyAccount(javax.swing.AbstractButton source) {
        if (!UiSupport.beginAction(source)) {
            return;
        }
        try {
            JTextField fullName = new JTextField(24);
            JTextField username = new JTextField(24);
            JTextField email = new JTextField(24);
            JTextField departmentField = new JTextField(24);
            JTextField password = new JTextField(generatePassword(), 24);
            UiSupport.styleInput(fullName);
            UiSupport.styleInput(username);
            UiSupport.styleInput(email);
            UiSupport.styleInput(departmentField);
            UiSupport.styleInput(password);
            password.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13));
        src.ui.components.PrimaryButton generate = new src.ui.components.PrimaryButton(
                "Generate", src.ui.components.PrimaryButton.Style.SECONDARY);
        generate.addActionListener(event -> password.setText(generatePassword()));

        JPanel form = new JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.insets = new java.awt.Insets(5, 4, 5, 4);
        c.anchor = java.awt.GridBagConstraints.WEST;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        Object[][] rows = {
                { "Full name", fullName }, { "Username", username },
                { "Email address", email }, { "Department", departmentField },
                { "Temporary password", password } };
        for (int i = 0; i < rows.length; i++) {
            c.gridy = i;
            c.gridx = 0;
            c.weightx = 0;
            JLabel label = new JLabel((String) rows[i][0]);
            label.setFont(AppTheme.semibold(12));
            form.add(label, c);
            c.gridx = 1;
            c.weightx = 1;
            form.add((java.awt.Component) rows[i][1], c);
        }
        c.gridx = 2;
        c.weightx = 0;
        form.add(generate, c);
        c.gridy = rows.length;
        c.gridx = 0;
        c.gridwidth = 3;
        JLabel note = new JLabel(
                "Share the temporary password with the faculty member; they can change it in Settings.");
        note.setFont(AppTheme.regular(11));
        note.setForeground(AppTheme.muted());
        form.add(note, c);

        while (true) {
            int answer = UiSupport.showConfirmDialog(
                    this, form, "Create faculty account",
                    javax.swing.JOptionPane.OK_CANCEL_OPTION,
                    javax.swing.JOptionPane.PLAIN_MESSAGE);
            if (answer != javax.swing.JOptionPane.OK_OPTION) {
                return;
            }
            String temporary = password.getText().trim();
            try {
                authController.createFacultyAccount(
                        actor, username.getText(), email.getText(), fullName.getText(),
                        departmentField.getText(), temporary.toCharArray());
            } catch (Exception exception) {
                UiSupport.showMessageDialog(this,
                        messageOf(exception), "Unable to create account",
                        javax.swing.JOptionPane.WARNING_MESSAGE);
                continue;
            }
            JTextField shown = new JTextField(temporary);
            shown.setEditable(false);
            JPanel done = new JPanel(new BorderLayout(0, 8));
            done.add(new JLabel("Faculty account created for " + fullName.getText().trim()
                    + " (" + username.getText().trim() + ")."), BorderLayout.NORTH);
            done.add(shown, BorderLayout.CENTER);
            done.add(new JLabel("Temporary password, copy it now; it won't be shown again."),
                    BorderLayout.SOUTH);
            UiSupport.showMessageDialog(this, done, "Account created",
                    javax.swing.JOptionPane.INFORMATION_MESSAGE);
            borrowerType.setSelectedItem("Faculty");
            loadUsers();
            return;
        }
        } finally {
            UiSupport.endAction(source);
        }
    }

    private static String generatePassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }

    private JPanel message(String text, boolean error) {
        SurfacePanel panel = new SurfacePanel();
        panel.setLayout(new BorderLayout());
        JLabel label = new JLabel(text);
        label.setFont(AppTheme.regular(11));
        label.setForeground(error ? AppTheme.danger() : AppTheme.muted());
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    private String messageOf(Exception exception) {
        Throwable cause = exception instanceof ExecutionException && exception.getCause() != null
                ? exception.getCause() : exception;
        return cause.getMessage();
    }
}
