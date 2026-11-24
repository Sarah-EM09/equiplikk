
package src;

import src.database.DatabaseInitializer;
import src.database.DatabaseSeeder;
import src.controller.AuthController;
import src.controller.BorrowRequestController;
import src.controller.EquipmentController;
import src.controller.NotificationController;
import src.layout.MainLayout;
import src.model.User;
import src.service.AuthService;
import src.service.BorrowRequestService;
import src.service.EquipmentService;
import src.service.NotificationService;
import src.ui.auth.DatabaseConfigDialog;
import src.ui.auth.LoginFrame;
import src.ui.SplashWindow;
import src.util.AppInfo;
import src.util.Updater;
import src.ui.UiSupport;

import javax.swing.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {

    /** Prevents the update prompt (and its install) from being handled twice. */
    private static final AtomicBoolean UPDATE_PROMPT_SHOWN = new AtomicBoolean(false);

    public static void main(String[] args) throws Exception {

        SwingUtilities.invokeAndWait(src.ui.AppTheme::install);

        // Splash plays the logo animation once while the database and update check run.
        CountDownLatch animationDone = new CountDownLatch(1);
        SplashWindow[] splash = new SplashWindow[1];
        SwingUtilities.invokeAndWait(() -> {
            splash[0] = new SplashWindow(animationDone::countDown);
            splash[0].setVisible(true);
        });
        CompletableFuture<Updater.Release> update = CompletableFuture
                .supplyAsync(Updater::check)
                .completeOnTimeout(null, 15, TimeUnit.SECONDS);

        // If the configured server cannot be reached, let the user fix the
        // connection instead of closing the application before the login page.
        boolean databaseReady = false;
        while (!databaseReady) {

            try {

                DatabaseInitializer.initializeDatabase();

                DatabaseSeeder.seedAccounts();

                databaseReady = true;
                System.out.println("EquipLink is ready.");

            } catch (Exception exception) {

                exception.printStackTrace();
                animationDone.await();

                AtomicBoolean retry = new AtomicBoolean(false);
                SwingUtilities.invokeAndWait(() -> {
                    UiSupport.showMessageDialog(
                            null,
                            "Unable to initialize EquipLink database.\n"
                                    + exception.getMessage()
                                    + "\n\nSet the correct connection and try again.",
                            "Database Error",
                            JOptionPane.ERROR_MESSAGE);
                    DatabaseConfigDialog dialog = new DatabaseConfigDialog(null);
                    dialog.setVisible(true);
                    retry.set(dialog.wasSaved());
                });

                if (!retry.get()) {
                    SwingUtilities.invokeAndWait(() -> splash[0].dispose());
                    return;
                }
            }
        }

        animationDone.await();
        Updater.Release release = update.join();

        SwingUtilities.invokeLater(() -> {
            Runnable openLogin = () -> {
                splash[0].dispose();

                AuthService authService = new AuthService();

                AuthController authController = new AuthController(
                        authService);
                BorrowRequestController requestController =
                        new BorrowRequestController(new BorrowRequestService());
                EquipmentController equipmentController =
                        new EquipmentController(new EquipmentService());
                NotificationController notificationController =
                        new NotificationController(new NotificationService());

                showLogin(authController, requestController, equipmentController,
                        notificationController);
            };
            if (release == null) {
                openLogin.run();
            } else {
                offerUpdate(release, splash[0], openLogin);
            }
        });
    }

    /** Asks to install a newer release; installs and exits, or continues to login. */
    private static void offerUpdate(Updater.Release release, SplashWindow splash, Runnable skip) {
        if (!UPDATE_PROMPT_SHOWN.compareAndSet(false, true)) {
            skip.run();
            return;
        }
        Object[] options = { "Update now", "Later" };
        int choice = UiSupport.showOptionDialog(
                splash,
                "EquipLink " + release.version() + " is available (you have "
                        + AppInfo.version() + ").\nInstall it now? EquipLink will restart.",
                "Update available",
                JOptionPane.INFORMATION_MESSAGE,
                options,
                options[0]);
        if (choice != 0) {
            skip.run();
            return;
        }
        splash.setStatus("Downloading EquipLink " + release.version() + "...");
        Thread worker = new Thread(() -> {
            try {
                Updater.downloadAndInstall(release);
                System.exit(0);
            } catch (Exception exception) {
                exception.printStackTrace();
                SwingUtilities.invokeLater(() -> {
                    UiSupport.showMessageDialog(
                            splash,
                            "The update could not be installed.\n" + exception.getMessage(),
                            "Update failed",
                            JOptionPane.WARNING_MESSAGE);
                    skip.run();
                });
            }
        }, "equiplink-updater");
        worker.setDaemon(true);
        worker.start();
    }

    private static void showLogin(
            AuthController authController,
            BorrowRequestController requestController,
            EquipmentController equipmentController,
            NotificationController notificationController) {
        LoginFrame loginFrame = new LoginFrame(
                authController,
                user -> showDashboard(
                        user, authController, requestController,
                        equipmentController, notificationController));
        loginFrame.setVisible(true);
    }

    private static void showDashboard(
            User user,
            AuthController authController,
            BorrowRequestController requestController,
            EquipmentController equipmentController,
            NotificationController notificationController) {
        MainLayout dashboard = new MainLayout(
                user, authController, requestController,
                equipmentController, notificationController);
        JFrame frame = new JFrame("EquipLink | " + user.getRole());
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        javax.swing.ImageIcon windowIcon = src.ui.BrandLogo.icon(64);
        if (windowIcon != null) {
            frame.setIconImage(windowIcon.getImage());
        }
        frame.setMinimumSize(new java.awt.Dimension(960, 640));
        frame.setSize(1280, 800);
        frame.setLocationRelativeTo(null);
        frame.setContentPane(dashboard);
        dashboard.setLogoutAction(() -> {
            dashboard.setLogoutAction(null);
            frame.dispose();
            showLogin(authController, requestController,
                    equipmentController, notificationController);
        });
        frame.setVisible(true);
    }
}
