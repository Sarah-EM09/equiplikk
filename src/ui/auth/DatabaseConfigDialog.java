package src.ui.auth;

import src.ui.AppTheme;
import src.ui.UiSupport;
import src.database.DatabaseConfig;
import src.database.DatabaseInitializer;
import src.database.DatabaseSeeder;
import src.ui.components.PrimaryButton;

import javax.swing.AbstractAction;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Locale;

/** Lets a user view and change the PostgreSQL connection used by EquipLink. */
public class DatabaseConfigDialog extends JDialog {

    private static final String[] SSL_LABELS = {
            "Prefer (automatic)", "Require (Supabase / hosted DB)", "Disable"
    };
    private static final String[] SSL_VALUES = { "prefer", "require", "disable" };

    private final JTextField hostField = new JTextField(24);
    private final JTextField portField = new JTextField(6);
    private final JTextField databaseField = new JTextField(24);
    private final JTextField userField = new JTextField(24);
    private final JPasswordField passwordField = new JPasswordField(24);
    private final JComboBox<String> sslBox = new JComboBox<>(SSL_LABELS);

    private final PrimaryButton testButton =
            new PrimaryButton("Test connection", PrimaryButton.Style.SECONDARY);
    private final PrimaryButton cancelButton =
            new PrimaryButton("Cancel", PrimaryButton.Style.SECONDARY);
    private final PrimaryButton saveButton = new PrimaryButton("Save");

    private boolean saved;

    public DatabaseConfigDialog(Window owner) {

        super(owner, "Database configuration", ModalityType.APPLICATION_MODAL);

        DatabaseConfig config = DatabaseConfig.current();
        hostField.setText(config.host());
        portField.setText(config.port());
        databaseField.setText(config.database());
        userField.setText(config.user());
        passwordField.setText(config.password());
        for (int i = 0; i < SSL_VALUES.length; i++) {
            if (SSL_VALUES[i].equalsIgnoreCase(config.sslMode())) {
                sslBox.setSelectedIndex(i);
                break;
            }
        }

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(false);
        setContentPane(src.ui.components.Motion.popIn(buildContent()));
        getRootPane().setDefaultButton(saveButton);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        getRootPane().getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                dispose();
            }
        });

        pack();
        setLocationRelativeTo(null);
    }

    /** True after the user saved a configuration successfully. */
    public boolean wasSaved() {
        return saved;
    }

    private JPanel buildContent() {

        JPanel root = new JPanel(new BorderLayout(0, 18));
        root.setBackground(AppTheme.surface());
        root.setBorder(new EmptyBorder(24, 26, 22, 26));

        JPanel header = new JPanel();
        header.setLayout(new javax.swing.BoxLayout(header, javax.swing.BoxLayout.Y_AXIS));
        header.setOpaque(false);

        JLabel heading = new JLabel("Database connection");
        heading.setFont(AppTheme.semibold(19));
        heading.setForeground(AppTheme.foreground());
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel(
                "<html><div width='390'>Set the PostgreSQL server used by EquipLink. "
                        + "You can paste a full connection string into <b>Host</b>; "
                        + "it will be split into the fields below.</div></html>");
        subtitle.setFont(AppTheme.regular(11.5f));
        subtitle.setForeground(AppTheme.muted());
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        header.add(heading);
        header.add(javax.swing.Box.createVerticalStrut(6));
        header.add(subtitle);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);

        UiSupport.styleInput(hostField);
        UiSupport.styleInput(portField);
        UiSupport.styleInput(databaseField);
        UiSupport.styleInput(userField);
        UiSupport.styleInput(passwordField);
        UiSupport.styleInput(sslBox);
        portField.setPreferredSize(new Dimension(86, 40));
        portField.setToolTipText("PostgreSQL port (usually 5432)");
        sslBox.setToolTipText("Supabase and other hosted databases require SSL");

        JPanel hostPort = new JPanel(new BorderLayout(8, 0));
        hostPort.setOpaque(false);
        hostPort.add(hostField, BorderLayout.CENTER);
        hostPort.add(portField, BorderLayout.EAST);

        addRow(form, 0, "Host or connection string", hostPort);
        addRow(form, 1, "Database name", databaseField);
        addRow(form, 2, "Username", userField);
        addRow(form, 3, "Password", passwordField);
        addRow(form, 4, "SSL mode", sslBox);

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 1;
        constraints.gridy = 5;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;
        constraints.insets = new Insets(6, 0, 0, 0);

        String savedPath = DatabaseConfig.configPath().toString()
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        JLabel savedHint = new JLabel(
                "<html><div width='390'>Saved automatically to:<br>"
                        + savedPath + "</div></html>");
        savedHint.setFont(AppTheme.regular(10));
        savedHint.setForeground(AppTheme.muted());
        form.add(savedHint, constraints);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(testButton);
        actions.add(cancelButton);
        actions.add(saveButton);

        root.add(header, BorderLayout.NORTH);
        root.add(form, BorderLayout.CENTER);
        root.add(actions, BorderLayout.SOUTH);

        testButton.addActionListener(event -> testConnection());
        cancelButton.addActionListener(event -> dispose());
        saveButton.addActionListener(event -> saveConfiguration());

        return root;
    }

    private void addRow(JPanel form, int row, String text, JComponent field) {

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(0, 0, 8, 0);

        JLabel label = new JLabel(text);
        label.setFont(AppTheme.semibold(11));
        label.setForeground(AppTheme.foreground());

        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.weightx = 0;
        constraints.insets = new Insets(0, 0, 8, 12);
        form.add(label, constraints);

        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.insets = new Insets(0, 0, 8, 0);
        form.add(field, constraints);
    }

    private DatabaseConfig readConfiguration() {

        String host = hostField.getText().trim();
        String port = portField.getText().trim();
        String database = databaseField.getText().trim();
        String user = userField.getText().trim();
        String password = new String(passwordField.getPassword());

        // Supabase (and many hosts) show a full connection string. Accept it in
        // the Host field and split it into the separate settings automatically.
        String pasted = host;
        String prefix = null;
        for (String candidate : new String[] {
                "jdbc:postgresql://", "postgresql://", "postgres://"}) {
            if (pasted.toLowerCase(Locale.ROOT).startsWith(candidate)) {
                prefix = candidate;
                break;
            }
        }

        if (prefix != null) {
            try {
                URI uri = new URI("postgresql://" + pasted.substring(prefix.length()));
                if (uri.getHost() != null) {
                    host = uri.getHost();
                }
                if (uri.getPort() != -1) {
                    port = String.valueOf(uri.getPort());
                }
                if (uri.getRawPath() != null && uri.getRawPath().length() > 1) {
                    database = decode(uri.getRawPath().substring(1));
                }
                if (uri.getRawUserInfo() != null) {
                    String info = uri.getRawUserInfo();
                    int colon = info.indexOf(':');
                    if (colon >= 0) {
                        user = decode(info.substring(0, colon));
                        password = decode(info.substring(colon + 1));
                    } else {
                        user = decode(info);
                    }
                }
                if (uri.getQuery() != null) {
                    for (String pair : uri.getQuery().split("&")) {
                        String[] keyValue = pair.split("=", 2);
                        if (keyValue.length == 2
                                && "sslmode".equalsIgnoreCase(keyValue[0])) {
                            for (int i = 0; i < SSL_VALUES.length; i++) {
                                if (SSL_VALUES[i].equalsIgnoreCase(keyValue[1])) {
                                    sslBox.setSelectedIndex(i);
                                }
                            }
                        }
                    }
                }

                hostField.setText(host);
                portField.setText(port);
                databaseField.setText(database);
                userField.setText(user);
                passwordField.setText(password);

            } catch (Exception exception) {
                UiSupport.showMessageDialog(
                        this,
                        "The connection string could not be read:\n"
                                + exception.getMessage(),
                        "Invalid connection string",
                        JOptionPane.WARNING_MESSAGE);
                return null;
            }
        }

        if (host.isEmpty() || database.isEmpty() || user.isEmpty()) {
            UiSupport.showMessageDialog(
                    this,
                    "Host, database name and username are required.",
                    "Missing information",
                    JOptionPane.WARNING_MESSAGE);
            return null;
        }

        try {
            int portNumber = Integer.parseInt(port);
            if (portNumber < 1 || portNumber > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            UiSupport.showMessageDialog(
                    this,
                    "Port must be a number between 1 and 65535.",
                    "Invalid port",
                    JOptionPane.WARNING_MESSAGE);
            return null;
        }

        return new DatabaseConfig(
                host, port, database, user, password,
                SSL_VALUES[sslBox.getSelectedIndex()]);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return value;
        }
    }

    private void testConnection() {

        DatabaseConfig candidate = readConfiguration();
        if (candidate == null) {
            return;
        }
        if (!UiSupport.beginAction(testButton)) {
            return;
        }

        testButton.setText("Testing...");

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                DriverManager.setLoginTimeout(10);
                try (Connection ignored = DriverManager.getConnection(
                        candidate.jdbcUrl(), candidate.user(), candidate.password())) {
                    return null;
                }
            }

            @Override
            protected void done() {
                testButton.setText("Test connection");
                UiSupport.endAction(testButton);
                try {
                    get();
                    UiSupport.showMessageDialog(
                            DatabaseConfigDialog.this,
                            "Connection successful.",
                            "Database configuration",
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception exception) {
                    Throwable cause = exception.getCause() == null
                            ? exception : exception.getCause();
                    UiSupport.showMessageDialog(
                            DatabaseConfigDialog.this,
                            "Could not connect:\n" + cause.getMessage(),
                            "Connection failed",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void saveConfiguration() {

        DatabaseConfig candidate = readConfiguration();
        if (candidate == null) {
            return;
        }
        if (!UiSupport.beginAction(saveButton)) {
            return;
        }

        saveButton.setText("Saving...");
        try {
            DatabaseConfig.save(candidate);
        } catch (IOException exception) {
            saveButton.setText("Save");
            UiSupport.endAction(saveButton);
            UiSupport.showMessageDialog(
                    this,
                    "The configuration could not be saved to:\n"
                            + DatabaseConfig.configPath() + "\n\n"
                            + exception.getMessage(),
                    "Save failed",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        saved = true;

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                DatabaseInitializer.initializeDatabase();
                DatabaseSeeder.seedAccounts();
                return null;
            }

            @Override
            protected void done() {
                saveButton.setText("Save");
                UiSupport.endAction(saveButton);
                try {
                    get();
                    UiSupport.showMessageDialog(
                            DatabaseConfigDialog.this,
                            "Configuration saved to:\n" + DatabaseConfig.configPath()
                                    + "\n\nIt will be used automatically on the next launch.",
                            "Database configuration",
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception exception) {
                    Throwable cause = exception.getCause() == null
                            ? exception : exception.getCause();
                    UiSupport.showMessageDialog(
                            DatabaseConfigDialog.this,
                            "Configuration saved, but the new database could not be "
                                    + "prepared:\n" + cause.getMessage(),
                            "Database setup warning",
                            JOptionPane.WARNING_MESSAGE);
                }
                dispose();
            }
        }.execute();
    }
}
