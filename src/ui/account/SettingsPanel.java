package src.ui.account;

import src.ui.UiSupport;
import src.ui.auth.PasswordChangeDialog;
import src.ui.AppTheme;
import src.controller.AuthController;
import src.model.User;
import src.ui.components.PrimaryButton;
import src.ui.components.SurfacePanel;
import src.util.NotificationSound;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class SettingsPanel extends JPanel {

    private final Runnable onRefresh;
    private final boolean notificationSoundEnabled;
    private final String notificationSound;

    public SettingsPanel(
            User user,
            AuthController authController,
            boolean notificationsEnabled,
            Runnable onRefresh,
            Consumer<Boolean> onNotificationsChange,
            boolean notificationSoundEnabled,
            String notificationSound,
            BiConsumer<Boolean, String> onSoundChange) {

        this.onRefresh = onRefresh;
        this.notificationSoundEnabled = notificationSoundEnabled;
        this.notificationSound = NotificationSound.normalize(notificationSound);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(AppTheme.background());
        setBorder(new EmptyBorder(2, 2, 22, 2));
        JLabel heading = new JLabel("Preferences & account");
        heading.setFont(AppTheme.semibold(18));
        heading.setForeground(AppTheme.foreground());
        JLabel subtitle = new JLabel("Personalize how EquipLink works for you.");
        subtitle.setFont(AppTheme.regular(11));
        subtitle.setForeground(AppTheme.muted());
        add(heading);
        add(Box.createVerticalStrut(4));
        add(subtitle);
        add(Box.createVerticalStrut(14));

        add(accountSettings(user, authController));
        add(Box.createVerticalStrut(10));
        add(appearanceSettings());
        add(Box.createVerticalStrut(10));
        add(notificationSettings(notificationsEnabled, onNotificationsChange));
        add(Box.createVerticalStrut(10));
        add(soundSettings(onSoundChange));
        add(Box.createVerticalStrut(10));
        add(securitySettings(user, authController));
        add(Box.createVerticalStrut(10));
        add(systemInformation());
    }

    private JPanel accountSettings(User user, AuthController authController) {
        SurfacePanel panel = section(
                "Account information", "Your name and department are shown across EquipLink.");
        JPanel body = new JPanel(new BorderLayout(18, 0));
        body.setOpaque(false);

        JPanel details = new JPanel(new GridBagLayout());
        details.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 0, 4, 12);
        c.anchor = GridBagConstraints.WEST;
        addAccountInfo(details, c, 0, "Full Name", user.getFullName());
        addAccountInfo(details, c, 1, "Email Address", user.getEmail());
        addAccountInfo(details, c, 2, "Username", user.getUsername());
        addAccountInfo(details, c, 3, "Department", user.getDepartment());

        // Primary style uses AppTheme.primary(), the same red as other main actions.
        PrimaryButton edit = new PrimaryButton("Edit profile");
        edit.addActionListener(event -> {
            if (ProfileEditor.edit(this, user, authController) && onRefresh != null) {
                onRefresh.run();
            }
        });
        JPanel action = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        action.setOpaque(false);
        action.add(edit);

        body.add(details, BorderLayout.CENTER);
        body.add(action, BorderLayout.EAST);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private void addAccountInfo(JPanel panel, GridBagConstraints c, int row,
            String labelText, String valueText) {
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        JLabel key = new JLabel(labelText);
        key.setFont(AppTheme.regular(11));
        key.setForeground(AppTheme.muted());
        key.setPreferredSize(new Dimension(112, key.getPreferredSize().height));
        panel.add(key, c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        JLabel value = new JLabel(
                valueText == null || valueText.isBlank() ? "\u2014" : valueText);
        value.setFont(AppTheme.regular(12));
        value.setForeground(AppTheme.foreground());
        panel.add(value, c);
    }

    private JPanel appearanceSettings() {
        SurfacePanel panel = section("Appearance", "Choose a comfortable look for EquipLink.");
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        options.setOpaque(false);
        PrimaryButton light = new PrimaryButton(
                "Light mode", AppTheme.isDarkMode()
                        ? PrimaryButton.Style.SECONDARY : PrimaryButton.Style.PRIMARY);
        PrimaryButton dark = new PrimaryButton(
                "Dark mode", AppTheme.isDarkMode()
                        ? PrimaryButton.Style.PRIMARY : PrimaryButton.Style.SECONDARY);
        light.addActionListener(event -> {
            AppTheme.setDarkMode(false);
            if (onRefresh != null) {
                onRefresh.run();
            }
        });
        dark.addActionListener(event -> {
            AppTheme.setDarkMode(true);
            if (onRefresh != null) {
                onRefresh.run();
            }
        });
        options.add(light);
        options.add(dark);
        panel.add(options, BorderLayout.CENTER);
        return panel;
    }

    private JPanel notificationSettings(
            boolean enabled,
            Consumer<Boolean> onNotificationsChange) {
        SurfacePanel panel = section(
                "Notification settings", "Choose whether to receive request updates.");
        JCheckBox toggle = new JCheckBox("Enable notifications", enabled);
        styleOption(toggle);
        PrimaryButton save = new PrimaryButton("Save");
        save.setToolTipText("Save your notification preference");
        save.addActionListener(event -> {
            if (!UiSupport.beginAction(save)) {
                return;
            }
            try {
                onNotificationsChange.accept(toggle.isSelected());
                UiSupport.showMessageDialog(this,
                        "Notification preference saved.",
                        "Settings saved", JOptionPane.INFORMATION_MESSAGE);
            } catch (RuntimeException exception) {
                UiSupport.showMessageDialog(this,
                        "Unable to save notification preference: " + exception.getMessage(),
                        "Settings not saved", JOptionPane.ERROR_MESSAGE);
            } finally {
                UiSupport.endAction(save);
            }
        });
        JPanel body = new JPanel(new BorderLayout(12, 0));
        body.setOpaque(false);
        body.add(toggle, BorderLayout.CENTER);
        body.add(save, BorderLayout.EAST);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private JPanel soundSettings(BiConsumer<Boolean, String> onSoundChange) {
        SurfacePanel panel = section(
                "Notification sound", "Choose a built-in tone for new notifications.");
        JCheckBox toggle = new JCheckBox("Play a sound", notificationSoundEnabled);
        styleOption(toggle);
        JComboBox<String> sounds = new JComboBox<>(
                NotificationSound.NAMES.toArray(new String[0]));
        sounds.setSelectedItem(notificationSound);
        sounds.setFont(AppTheme.regular(12));
        sounds.setEnabled(notificationSoundEnabled);

        PrimaryButton preview = new PrimaryButton("Preview", PrimaryButton.Style.SECONDARY);
        preview.setToolTipText("Play the selected tone now");
        preview.setEnabled(notificationSoundEnabled);
        preview.addActionListener(event ->
                NotificationSound.preview((String) sounds.getSelectedItem()));
        toggle.addActionListener(event -> {
            sounds.setEnabled(toggle.isSelected());
            preview.setEnabled(toggle.isSelected());
        });

        PrimaryButton save = new PrimaryButton("Save sound");
        save.setToolTipText("Save your notification sound preference");
        save.addActionListener(event -> {
            if (!UiSupport.beginAction(save)) {
                return;
            }
            try {
                String selected = NotificationSound.normalize((String) sounds.getSelectedItem());
                onSoundChange.accept(toggle.isSelected(), selected);
                UiSupport.showMessageDialog(this,
                        "Notification sound preference saved.",
                        "Settings saved", JOptionPane.INFORMATION_MESSAGE);
            } catch (RuntimeException exception) {
                UiSupport.showMessageDialog(this,
                        "Unable to save notification sound: " + exception.getMessage(),
                        "Settings not saved", JOptionPane.ERROR_MESSAGE);
            } finally {
                UiSupport.endAction(save);
            }
        });

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controls.setOpaque(false);
        controls.add(toggle);
        controls.add(new JLabel("Tone"));
        controls.add(sounds);
        controls.add(preview);
        JPanel body = new JPanel(new BorderLayout(12, 0));
        body.setOpaque(false);
        body.add(controls, BorderLayout.CENTER);
        body.add(save, BorderLayout.EAST);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private JPanel securitySettings(User user, AuthController authController) {
        SurfacePanel panel = section(
                "Security settings", "Keep your account credentials up to date.");
        JPanel body = new JPanel(new BorderLayout(15, 0));
        body.setOpaque(false);
        JLabel note = new JLabel("Change your password using your current password.");
        note.setFont(AppTheme.regular(11));
        note.setForeground(AppTheme.muted());
        PrimaryButton changePassword = new PrimaryButton("Change password");
        changePassword.addActionListener(event -> {
            Window owner = javax.swing.SwingUtilities.getWindowAncestor(this);
            new PasswordChangeDialog(owner, user.getUserId(), authController).setVisible(true);
        });
        body.add(note, BorderLayout.CENTER);
        body.add(changePassword, BorderLayout.EAST);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private JPanel systemInformation() {
        SurfacePanel panel = section(
                "System information", "About this campus equipment borrowing system.");
        JPanel details = new JPanel();
        details.setOpaque(false);
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        addInfo(details, "Application name", "EquipLink");
        addInfo(details, "Application version", "1.0");
        addInfo(details, "Database", "PostgreSQL");
        addInfo(details, "About EquipLink",
                "A single place to request and manage campus equipment.");
        addInfo(details, "Help & support",
                "Contact your campus system administrator for assistance.");
        panel.add(details, BorderLayout.CENTER);
        return panel;
    }

    private SurfacePanel section(String titleText, String subtitleText) {
        SurfacePanel panel = new SurfacePanel();
        panel.setLayout(new BorderLayout(0, 12));
        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(titleText);
        title.setFont(AppTheme.semibold(14));
        title.setForeground(AppTheme.foreground());
        JLabel subtitle = new JLabel(subtitleText);
        subtitle.setFont(AppTheme.regular(10));
        subtitle.setForeground(AppTheme.muted());
        heading.add(title);
        heading.add(Box.createVerticalStrut(4));
        heading.add(subtitle);
        panel.add(heading, BorderLayout.NORTH);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        return panel;
    }

    private void styleOption(JCheckBox option) {
        option.setOpaque(false);
        option.setFont(AppTheme.regular(12));
        option.setForeground(AppTheme.foreground());
        option.setBorder(new EmptyBorder(0, 0, 0, 18));
    }

    private void addInfo(JPanel panel, String labelText, String valueText) {
        JPanel row = new JPanel(new BorderLayout(16, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(4, 0, 4, 0));
        JLabel label = new JLabel(labelText);
        label.setFont(AppTheme.regular(10));
        label.setForeground(AppTheme.muted());
        JLabel value = new JLabel(valueText);
        value.setFont(AppTheme.regular(11));
        value.setForeground(AppTheme.foreground());
        row.add(label, BorderLayout.WEST);
        row.add(value, BorderLayout.CENTER);
        panel.add(row);
    }

    private static String html(String value) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
