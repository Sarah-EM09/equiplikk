
package src.ui.auth;

import src.ui.AppTheme;
import src.controller.AuthController;
import src.ui.components.PrimaryButton;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Arrays;

/** Password fields and background styling for PasswordChangeDialog. */
public abstract class PasswordChangeFields extends JDialog {

    protected PasswordChangeFields(java.awt.Window owner, String title, java.awt.Dialog.ModalityType modality) {
        super(owner, title, modality);
    }


    // =========================================
    // EXISTING FIELDS AND CONTROLLERS
    // =========================================

    long userId;
    AuthController authController;

    final JPasswordField currentPassword =
            new GlassPasswordField();

    final JPasswordField newPassword =
            new GlassPasswordField();

    final JPasswordField confirmPassword =
            new GlassPasswordField();

    final PrimaryButton saveButton =
            new PrimaryButton("Update password");

    // =========================================
    // PASSWORD FIELD DESIGN
    // =========================================

    void addPasswordField(
            JPanel panel,
            String labelText,
            JPasswordField field) {

        JLabel label = new JLabel(labelText);

        label.setFont(
                AppTheme.semibold(10)
        );

        label.setForeground(AppTheme.foreground());

        label.setAlignmentX(
                Component.LEFT_ALIGNMENT
        );

        field.setFont(
                AppTheme.regular(12)
        );

        field.setPreferredSize(
                new Dimension(420, 44)
        );

        field.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, 44)
        );

        field.setAlignmentX(
                Component.LEFT_ALIGNMENT
        );

        panel.add(label);

        panel.add(
                Box.createVerticalStrut(7)
        );

        panel.add(field);

        panel.add(
                Box.createVerticalStrut(17)
        );
    }

    // =========================================
    // GLASS PASSWORD FIELD
    // =========================================

    static class GlassPasswordField
            extends JPasswordField {

        GlassPasswordField() {

            setFont(
                    AppTheme.regular(12)
            );

            setForeground(AppTheme.foreground());

            setCaretColor(AppTheme.primaryInk());

            setOpaque(false);

            setBorder(
                    new EmptyBorder(11, 15, 11, 15)
            );

            setFocusable(true);
        }

        @Override
        protected void paintComponent(Graphics graphics) {

            Graphics2D g2 =
                    (Graphics2D) graphics.create();

            g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            int width = getWidth();
            int height = getHeight();

            // Input background gradient
            GradientPaint gradient = new GradientPaint(
                    0, 0,
                    AppTheme.alpha(AppTheme.inputBackground(), 245),
                    0, height,
                    AppTheme.alpha(AppTheme.primarySoft(), 235)
            );

            g2.setPaint(gradient);

            g2.fillRoundRect(
                    1,
                    1,
                    width - 3,
                    height - 3,
                    14,
                    14
            );

            // Soft border
            g2.setColor(AppTheme.border());

            g2.setStroke(
                    new BasicStroke(1f)
            );

            g2.drawRoundRect(
                    1,
                    1,
                    width - 3,
                    height - 3,
                    14,
                    14
            );

            // Top highlight
            g2.setColor(
                    AppTheme.alpha(AppTheme.shadowLight(), 220)
            );

            g2.drawLine(
                    15,
                    3,
                    width - 16,
                    3
            );

            // Focus effect
            if (hasFocus()) {

                g2.setColor(
                        AppTheme.alpha(AppTheme.primaryInk(), 155)
                );

                g2.setStroke(
                        new BasicStroke(1.5f)
                );

                g2.drawRoundRect(
                        1,
                        1,
                        width - 3,
                        height - 3,
                        14,
                        14
                );
            }

            g2.dispose();

            super.paintComponent(graphics);
        }
    }

    // =========================================
    // GLASS BACKGROUND
    // =========================================

    static class GlassBackgroundPanel
            extends JPanel {

        GlassBackgroundPanel() {
            setOpaque(true);
            setBackground(AppTheme.authBackground());
        }

        @Override
        protected void paintComponent(Graphics graphics) {

            super.paintComponent(graphics);

            Graphics2D g2 =
                    (Graphics2D) graphics.create();

            g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            int width = getWidth();
            int height = getHeight();

            // Main gradient
            GradientPaint gradient = new GradientPaint(
                    0, 0,
                    AppTheme.authBackground(),
                    width, height,
                    AppTheme.backgroundDeep()
            );

            g2.setPaint(gradient);

            g2.fillRect(
                    0,
                    0,
                    width,
                    height
            );

            // Decorative upper circle
            g2.setColor(
                    AppTheme.alpha(AppTheme.shadowLight(), 75)
            );

            g2.fillOval(
                    width - 155,
                    35,
                    190,
                    190
            );

            // Decorative bottom circle
            g2.setColor(
                    AppTheme.alpha(AppTheme.primaryDark(), 30)
            );

            g2.fillOval(
                    15,
                    height - 170,
                    195,
                    195
            );

            // Subtle light reflection
            g2.setColor(
                    AppTheme.alpha(AppTheme.shadowLight(), 45)
            );

            g2.fillOval(
                    width / 2,
                    height / 2 - 130,
                    65,
                    65
            );

            g2.dispose();
        }
    }
}
