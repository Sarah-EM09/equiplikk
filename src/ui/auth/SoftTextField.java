
package src.ui.auth;

import src.ui.AppTheme;
import src.controller.AuthController;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

/** Soft neumorphic control used by ForgotPasswordDialog. */
class SoftTextField extends JTextField {

    SoftTextField() {

        setFont(AppTheme.regular(11));
        setForeground(AppTheme.foreground());
        setCaretColor(AppTheme.primaryInk());
        setOpaque(false);

        setBorder(
                new EmptyBorder(12, 15, 12, 15)
        );
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

        // Soft inset surface
        GradientPaint gradient = new GradientPaint(
                0, 0,
                AppTheme.inputBackground(),
                0, height,
                AppTheme.primarySoft()
        );

        g2.setPaint(gradient);

        g2.fillRoundRect(
                1, 1,
                width - 3,
                height - 3,
                14, 14
        );

        // Outer border
        g2.setColor(AppTheme.border());

        g2.drawRoundRect(
                1, 1,
                width - 3,
                height - 3,
                14, 14
        );

        // Top highlight
        g2.setColor(
                AppTheme.alpha(AppTheme.shadowLight(), 210)
        );

        g2.drawLine(
                15, 3,
                width - 16, 3
        );

        // Bottom inset shadow
        g2.setColor(
                AppTheme.alpha(AppTheme.shadowDark(), 85)
        );

        g2.drawLine(
                15, height - 3,
                width - 16, height - 3
        );

        // Focus highlight
        if (hasFocus()) {

            g2.setColor(
                    AppTheme.alpha(AppTheme.primaryInk(), 150)
            );

            g2.setStroke(new BasicStroke(1.5f));

            g2.drawRoundRect(
                    1, 1,
                    width - 3,
                    height - 3,
                    14, 14
            );
        }

        g2.dispose();

        super.paintComponent(graphics);
    }
}
