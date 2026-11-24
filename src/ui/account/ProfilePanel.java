package src.ui.account;

import src.ui.AppTheme;
import src.controller.AuthController;
import src.model.User;
import src.ui.components.Avatar;
import src.ui.components.PrimaryButton;
import src.ui.components.StatusBadge;
import src.ui.components.SurfacePanel;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.time.format.DateTimeFormatter;

/** Read-only profile view for the authenticated account, with a guarded edit action. */
public class ProfilePanel extends JPanel {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("MMM d, yyyy");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("MMM d, yyyy  h:mm a");

    private final User user;
    private final AuthController authController;
    private final Runnable onUpdated;
    private final JLabel nameLabel = new JLabel();

    public ProfilePanel(User user, AuthController authController, Runnable onUpdated) {
        this.user = user;
        this.authController = authController;
        this.onUpdated = onUpdated;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(AppTheme.background());
        setBorder(new EmptyBorder(2, 2, 22, 2));

        add(header());
        add(Box.createVerticalStrut(16));
        add(profileCard());
        add(Box.createVerticalStrut(12));
        add(detailsCard());
    }

    private JPanel header() {
        JPanel header = new JPanel(new BorderLayout(12, 6));
        header.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("My Profile");
        title.setFont(AppTheme.semibold(18));
        title.setForeground(AppTheme.foreground());
        JLabel subtitle = new JLabel("Your EquipLink account information.");
        subtitle.setFont(AppTheme.regular(11));
        subtitle.setForeground(AppTheme.muted());
        titles.add(title);
        titles.add(Box.createVerticalStrut(4));
        titles.add(subtitle);

        PrimaryButton edit = new PrimaryButton("Edit Profile", PrimaryButton.Style.SECONDARY);
        edit.addActionListener(event -> {
            if (ProfileEditor.edit(this, user, authController) && onUpdated != null) {
                onUpdated.run();
            }
        });

        header.add(titles, BorderLayout.WEST);
        header.add(edit, BorderLayout.EAST);
        return header;
    }

    private JPanel profileCard() {
        SurfacePanel card = new SurfacePanel();
        card.setLayout(new BorderLayout(18, 10));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        Avatar avatar = new Avatar(
                Avatar.initials(user.getFullName()), AppTheme.primary(), 999);
        avatar.setIdentity(user.getFullName(), user.getProfilePicture());
        avatar.setPreferredSize(new java.awt.Dimension(72, 72));
        avatar.setFont(AppTheme.semibold(22));

        JPanel identity = new JPanel();
        identity.setOpaque(false);
        identity.setLayout(new BoxLayout(identity, BoxLayout.Y_AXIS));
        nameLabel.setText(displayName());
        nameLabel.setFont(AppTheme.semibold(17));
        nameLabel.setForeground(AppTheme.foreground());

        JLabel email = new JLabel(value(user.getEmail()));
        email.setFont(AppTheme.regular(12));
        email.setForeground(AppTheme.muted());

        JPanel badges = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        badges.setOpaque(false);
        badges.add(new StatusBadge(displayRole(user.getRole()), AppTheme.primaryInk()));
        badges.add(new StatusBadge(
                user.isActive() ? "Active" : "Deactivated",
                user.isActive() ? AppTheme.success() : AppTheme.danger()));

        identity.add(nameLabel);
        identity.add(Box.createVerticalStrut(5));
        identity.add(email);
        identity.add(Box.createVerticalStrut(9));
        identity.add(badges);

        card.add(avatar, BorderLayout.WEST);
        card.add(identity, BorderLayout.CENTER);
        return card;
    }

    private JPanel detailsCard() {
        SurfacePanel card = new SurfacePanel();
        card.setLayout(new GridBagLayout());
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 4, 6, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        addInfo(card, c, 0, "Full name", displayName());
        addInfo(card, c, 1, "Username", value(user.getUsername()));
        addInfo(card, c, 2, "Email address", value(user.getEmail()));
        addInfo(card, c, 3, "Assigned role", displayRole(user.getRole()));
        addInfo(card, c, 4, "Department", value(user.getDepartment()));
        if (user.getBorrowerType() != null && !user.getBorrowerType().isBlank()) {
            addInfo(card, c, 5, "Account type",
                    user.getBorrowerType().substring(0, 1).toUpperCase()
                            + user.getBorrowerType().substring(1).toLowerCase());
        }
        addInfo(card, c, 6, "Registration date",
                user.getCreatedAt() == null ? "—" : DATE.format(user.getCreatedAt()));
        addInfo(card, c, 7, "Last login",
                user.getLastLogin() == null ? "—" : STAMP.format(user.getLastLogin()));
        return card;
    }

    private void addInfo(
            JPanel panel, GridBagConstraints c, int row, String label, String text) {
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        JLabel key = new JLabel(label);
        key.setFont(AppTheme.semibold(12));
        key.setForeground(AppTheme.muted());
        panel.add(key, c);

        c.gridx = 1;
        c.weightx = 1;
        JLabel field = new JLabel(text);
        field.setFont(AppTheme.regular(12));
        field.setForeground(AppTheme.foreground());
        panel.add(field, c);
    }

    private String displayName() {
        return value(user.getFullName());
    }

    private static String displayRole(String role) {
        if (role == null) {
            return "—";
        }
        return switch (role.trim().toUpperCase()) {
            case "BORROWER" -> "Borrower";
            case "ICT_STAFF" -> "System Admin";
            case "FACILITY_STAFF" -> "Facility Staff";
            case "ADMIN" -> "Facility Admin";
            case "DIRECTOR" -> "Campus Director";
            default -> role;
        };
    }

    private static String value(String text) {
        return text == null || text.isBlank() ? "—" : text;
    }
}
