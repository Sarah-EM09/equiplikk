
package src.ui.auth;

import src.ui.AppTheme;
import src.controller.AuthController;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

/** Soft neumorphic control used by ForgotPasswordDialog. */
class Soft3DButton extends JButton {

    final boolean primary;

    Soft3DButton(String text, boolean primary) {

        super(text);

        this.primary = primary;

        setFont(AppTheme.semibold(11));

        setForeground(
                primary ? AppTheme.onPrimary() : AppTheme.foreground()
        );

        setHorizontalAlignment(SwingConstants.CENTER);

        setFocusPainted(false);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setOpaque(false);

        setRolloverEnabled(true);

        setBorder(
                new EmptyBorder(12, 12, 12, 12)
        );

        setCursor(
                Cursor.getPredefinedCursor(
                        Cursor.HAND_CURSOR
                )
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

        boolean pressed = getModel().isPressed();
        boolean hovered = getModel().isRollover();
        boolean enabled = isEnabled();

        int offset = pressed ? 3 : 0;

        // Bottom depth / shadow
        if (!pressed && enabled) {

            g2.setColor(
                    primary
                            ? AppTheme.alpha(AppTheme.primaryDark(), 100)
                            : AppTheme.alpha(AppTheme.shadowDark(), 100)
            );

            g2.fillRoundRect(
                    3, 5,
                    width - 7,
                    height - 8,
                    17, 17
            );
        }

        // Main button surface
        Color top;
        Color bottom;

        if (!enabled) {

            top = AppTheme.subtle();
            bottom = AppTheme.mix(AppTheme.subtle(), AppTheme.shadowDark(), 0.25f);

        } else if (primary) {

            top = hovered
                    ? (AppTheme.isDarkMode()
                            ? AppTheme.buttonHoverBackground() : AppTheme.primaryTint())
                    : AppTheme.buttonBackground();

            bottom = AppTheme.isDarkMode()
                    ? AppTheme.buttonBackground() : AppTheme.primaryDark();

        } else {

            top = hovered
                    ? AppTheme.surface()
                    : AppTheme.elevatedSurface();

            bottom = pressed
                    ? AppTheme.border()
                    : AppTheme.subtle();
        }

        GradientPaint gradient = new GradientPaint(
                0, 2 + offset,
                top,
                0, height - 5 + offset,
                bottom
        );

        g2.setPaint(gradient);

        g2.fillRoundRect(
                2, 2 + offset,
                width - 5,
                height - 7,
                16, 16
        );

        // Surface outline
        g2.setColor(
                primary
                        ? AppTheme.primaryDark()
                        : AppTheme.border()
        );

        g2.drawRoundRect(
                2, 2 + offset,
                width - 5,
                height - 7,
                16, 16
        );

        // Top reflective edge
        if (enabled && !pressed) {

            g2.setColor(
                    primary
                            ? AppTheme.alpha(AppTheme.onPrimary(), 75)
                            : AppTheme.alpha(AppTheme.onPrimary(), 230)
            );

            g2.setStroke(new BasicStroke(1.2f));

            g2.drawLine(
                    16, 5,
                    width - 17, 5
            );
        }

        g2.dispose();

        super.paintComponent(graphics);
    }
}
