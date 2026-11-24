package src.ui.account;

import src.ui.AppTheme;
import src.ui.UiSupport;
import src.controller.AuthController;
import src.model.User;
import src.ui.components.Avatar;
import src.ui.components.PrimaryButton;
import src.util.ProfilePicture;
import src.util.UsernameAlreadyExistsException;

import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.io.IOException;

/** Shared self-service profile editor for My Profile and Account Settings. */
public final class ProfileEditor {

    private ProfileEditor() {
    }

    /**
     * Shows a modal editor and saves the permitted fields to PostgreSQL. Username
     * is editable but checked for uniqueness across all roles; email and role stay
     * read-only, so no role can escalate privileges through this path. The picture
     * is stored once on the user account, which is what every avatar reads.
     * Returns true only after a successful save.
     */
    public static boolean edit(Component parent, User user, AuthController authController) {
        if (user == null || authController == null) {
            return false;
        }

        State state = new State(user);
        JTextField username = new JTextField(
                user.getUsername() == null ? "" : user.getUsername(), 24);
        UiSupport.styleInput(username);
        JTextField fullName = new JTextField(
                user.getFullName() == null ? "" : user.getFullName(), 24);
        UiSupport.styleInput(fullName);
        JTextField department = new JTextField(
                user.getDepartment() == null ? "" : user.getDepartment(), 24);
        UiSupport.styleInput(department);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new java.awt.Insets(5, 4, 5, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        JPanel pictureRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        pictureRow.setOpaque(false);
        PrimaryButton change = new PrimaryButton(
                "Change picture", PrimaryButton.Style.SECONDARY);
        change.addActionListener(event -> choosePicture(form, user, state));
        PrimaryButton remove = new PrimaryButton(
                "Remove picture", PrimaryButton.Style.SECONDARY);
        remove.addActionListener(event -> {
            state.picture = null;
            refreshPreview(user, state);
        });
        pictureRow.add(state.preview);
        pictureRow.add(change);
        pictureRow.add(remove);

        addRow(form, c, 0, "Profile picture", pictureRow);
        addRow(form, c, 1, "Full name", fullName);
        addRow(form, c, 2, "Username", username);
        addRow(form, c, 3, "Email address", readOnly(user.getEmail()));
        addRow(form, c, 4, "Role", readOnly(user.getRole()));
        addRow(form, c, 5, "Department", department);

        c.gridy = 6;
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        JLabel note = new JLabel(
                "<html><body style='width:330px'>"
                        + "Your username can be changed here and must be unique across all "
                        + "accounts. Email and role are verified details and cannot be "
                        + "changed here."
                        + "</body></html>");
        note.setFont(AppTheme.regular(10.5f));
        note.setForeground(AppTheme.muted());
        form.add(note, c);

        while (true) {
            refreshPreview(user, state);
            int answer = UiSupport.showConfirmDialog(
                    parent, form, "Edit profile",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) {
                return false;
            }

            String usernameValue = username.getText().trim();
            if (usernameValue.isEmpty()) {
                UiSupport.showMessageDialog(
                        parent, "Username is required.", "Check the details",
                        JOptionPane.WARNING_MESSAGE);
                continue;
            }
            if (usernameValue.length() > 60) {
                UiSupport.showMessageDialog(
                        parent, "Username must be 60 characters or fewer.",
                        "Check the details", JOptionPane.WARNING_MESSAGE);
                continue;
            }

            String value = fullName.getText().trim();
            if (value.isEmpty()) {
                UiSupport.showMessageDialog(
                        parent, "Full name is required.", "Check the details",
                        JOptionPane.WARNING_MESSAGE);
                continue;
            }
            if (value.length() > 150) {
                UiSupport.showMessageDialog(
                        parent, "Full name must be 150 characters or fewer.",
                        "Check the details", JOptionPane.WARNING_MESSAGE);
                continue;
            }

            String departmentValue = department.getText().trim();
            if (departmentValue.isEmpty()) {
                UiSupport.showMessageDialog(
                        parent, "Department is required.", "Check the details",
                        JOptionPane.WARNING_MESSAGE);
                continue;
            }
            if (departmentValue.length() > 120) {
                UiSupport.showMessageDialog(
                        parent, "Department must be 120 characters or fewer.",
                        "Check the details", JOptionPane.WARNING_MESSAGE);
                continue;
            }

            try {
                authController.updateOwnProfile(
                        user, usernameValue, value, departmentValue, state.picture);
                user.setUsername(usernameValue);
                user.setFullName(value);
                user.setDepartment(departmentValue);
                user.setProfilePicture(state.picture);
                return true;
            } catch (UsernameAlreadyExistsException exception) {
                UiSupport.showMessageDialog(
                        parent,
                        UsernameAlreadyExistsException.MESSAGE,
                        UsernameAlreadyExistsException.TITLE,
                        JOptionPane.WARNING_MESSAGE);
            } catch (Exception exception) {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                UiSupport.showMessageDialog(
                        parent,
                        "Unable to save your profile: " + cause.getMessage(),
                        "Profile not saved", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private static void choosePicture(Component parent, User user, State state) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose profile picture");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Image files (PNG, JPG, GIF, BMP)", "png", "jpg", "jpeg", "gif", "bmp"));
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            state.picture = ProfilePicture.fromFile(chooser.getSelectedFile());
            refreshPreview(user, state);
        } catch (IOException exception) {
            UiSupport.showMessageDialog(
                    parent, exception.getMessage(), "Picture not loaded",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private static void refreshPreview(User user, State state) {
        state.preview.setIdentity(user.getFullName(), state.picture);
    }

    private static JComponent readOnly(String value) {
        JLabel label = new JLabel(
                value == null || value.isBlank() ? "—" : value);
        label.setFont(AppTheme.regular(12));
        label.setForeground(AppTheme.muted());
        return label;
    }

    private static void addRow(
            JPanel panel, GridBagConstraints c, int row, String label, JComponent field) {
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        JLabel key = new JLabel(label);
        key.setFont(AppTheme.semibold(12));
        key.setForeground(AppTheme.foreground());
        panel.add(key, c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(field, c);
    }

    /** Mutable state captured by the dialog's button listeners. */
    private static final class State {
        final Avatar preview;
        byte[] picture;

        State(User user) {
            picture = user.getProfilePicture();
            preview = new Avatar(
                    Avatar.initials(user.getFullName()), AppTheme.primary(), 999);
            preview.setPreferredSize(new Dimension(48, 48));
            preview.setFont(AppTheme.semibold(15));
            preview.setIdentity(user.getFullName(), picture);
        }
    }
}
