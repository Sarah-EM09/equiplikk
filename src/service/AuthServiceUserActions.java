package src.service;

import src.database.Database;
import src.model.Notification;
import src.model.PasswordResetRequest;
import src.model.User;
import src.util.AccountDeactivatedException;
import src.util.AccountDeletedException;
import src.util.NotificationSound;
import src.util.PasswordHasher;
import src.util.ProfilePicture;
import src.util.UsernameAlreadyExistsException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Login, registration, profile and password actions for AuthService. */
public abstract class AuthServiceUserActions {

    public User login(String usernameOrEmail, char[] password)
            throws Exception {

        String sql = """
                SELECT user_id, username, email, full_name,
                       department, role, borrower_type, active, notifications_enabled,
                       notification_sound_enabled, notification_sound,
                       deleted_at, profile_picture, created_at, last_login,
                       password_hash, password_salt,
                       password_iterations
                FROM users
                WHERE LOWER(username) = LOWER(?)
                   OR LOWER(email) = LOWER(?)
                LIMIT 1
                """;

        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, usernameOrEmail.trim());
            statement.setString(2, usernameOrEmail.trim());

            long userId;
            String username;
            String email;
            String fullName;
            String department;
            String role;
            String borrowerType;
            boolean active;
            boolean notificationsEnabled;
            boolean notificationSoundEnabled;
            String notificationSound;
            byte[] profilePicture;
            LocalDateTime createdAt;
            LocalDateTime initialLastLogin;

            try (ResultSet rs = statement.executeQuery()) {

                if (!rs.next()) {
                    return null;
                }

                // Deleted and deactivated accounts are never authenticated, so no
                // session or protected page is granted. Deletion has its own notice
                // and takes precedence over the ordinary deactivation message.
                if (rs.getTimestamp("deleted_at") != null) {
                    throw new AccountDeletedException();
                }
                if (!rs.getBoolean("active")) {
                    throw new AccountDeactivatedException();
                }

                String storedHash = rs.getString("password_hash");
                String salt = rs.getString("password_salt");
                int iterations = rs.getInt("password_iterations");

                boolean valid = PasswordHasher.verifyPassword(
                        password,
                        salt,
                        storedHash,
                        iterations);

                if (!valid) {
                    return null;
                }

                userId = rs.getLong("user_id");
                username = rs.getString("username");
                email = rs.getString("email");
                fullName = rs.getString("full_name");
                department = rs.getString("department");
                role = rs.getString("role");
                borrowerType = rs.getString("borrower_type");
                active = rs.getBoolean("active");
                notificationsEnabled = rs.getBoolean("notifications_enabled");
                notificationSoundEnabled = rs.getBoolean("notification_sound_enabled");
                notificationSound = rs.getString("notification_sound");
                profilePicture = rs.getBytes("profile_picture");
                createdAt = toDateTime(rs.getTimestamp("created_at"));
                initialLastLogin = toDateTime(rs.getTimestamp("last_login"));
            }

            User user = new User(
                    userId, username, email, fullName, department,
                    role, borrowerType, active);
            user.setNotificationsEnabled(notificationsEnabled);
            user.setNotificationSoundEnabled(notificationSoundEnabled);
            user.setNotificationSound(NotificationSound.normalize(notificationSound));
            user.setProfilePicture(profilePicture);
            user.setCreatedAt(createdAt);
            user.setLastLogin(initialLastLogin);

            // Record the successful sign-in so My Profile can show "Last login".
            // The column is optional metadata: a failure must never block sign-in.
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE users
                    SET last_login = CURRENT_TIMESTAMP
                    WHERE user_id = ?
                    RETURNING last_login
                    """)) {
                update.setLong(1, userId);
                try (ResultSet updated = update.executeQuery()) {
                    if (updated.next()) {
                        user.setLastLogin(toDateTime(updated.getTimestamp("last_login")));
                    }
                }
            } catch (SQLException ignored) {
                // Keep the previously stored value when the column is unavailable.
            }
            return user;
        }
    }

    private static LocalDateTime toDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    public void registerBorrower(
            String username,
            String email,
            String fullName,
            String department,
            String borrowerType,
            char[] password) throws Exception {

        if (username == null || username.isBlank()
                || email == null || email.isBlank()
                || fullName == null || fullName.isBlank()
                || password == null || password.length < 8) {

            throw new IllegalArgumentException(
                    "Please complete all required fields. Password must be at least 8 characters.");
        }

        if (!email.trim().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }

        if (!"STUDENT".equalsIgnoreCase(borrowerType)) {

            throw new IllegalArgumentException(
                    "Self-registration is for students. Faculty accounts are created by ICT.");
        }

        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM users WHERE LOWER(email) = LOWER(?) LIMIT 1")) {
            statement.setString(1, email.trim());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    throw new IllegalArgumentException(
                            "An account already exists for this GSuite account.");
                }
            }
        }

        try {
            insertBorrower(username, email, fullName, department, "STUDENT", password);
        } catch (java.sql.SQLException exception) {
            if ("23505".equals(exception.getSQLState())) {
                throw new IllegalArgumentException(
                        "That username or email is already in use.");
            }
            throw exception;
        }
    }

    void insertBorrower(
            String username,
            String email,
            String fullName,
            String department,
            String borrowerType,
            char[] password) throws Exception {

        String salt = PasswordHasher.generateSalt();

        String passwordHash = PasswordHasher.hashPassword(password, salt);

        String sql = """
                INSERT INTO users (
                    username,
                    email,
                    password_hash,
                    password_salt,
                    password_iterations,
                    role,
                    borrower_type,
                    full_name,
                    department,
                    active
                )
                VALUES (?, ?, ?, ?, ?, 'BORROWER', ?, ?, ?, TRUE)
                """;

        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, username.trim());
            statement.setString(2, email.trim());
            statement.setString(3, passwordHash);
            statement.setString(4, salt);
            statement.setInt(5, PasswordHasher.getIterations());
            statement.setString(6, borrowerType.toUpperCase());
            statement.setString(7, fullName.trim());

            if (department == null || department.isBlank()) {
                statement.setNull(8, Types.VARCHAR);
            } else {
                statement.setString(8, department.trim());
            }

            statement.executeUpdate();
        }
    }

    public void changePassword(
            long userId,
            char[] currentPassword,
            char[] newPassword) throws Exception {

        if (currentPassword == null || currentPassword.length == 0
                || newPassword == null || newPassword.length < 8) {
            throw new IllegalArgumentException(
                    "The new password must be at least 8 characters.");
        }

        String selectSql = """
                SELECT password_hash, password_salt, password_iterations
                FROM users
                WHERE user_id = ?
                """;

        try (Connection connection = Database.getConnection()) {
            String storedHash;
            String salt;
            int iterations;

            try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
                statement.setLong(1, userId);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("User account was not found.");
                    }
                    storedHash = rs.getString("password_hash");
                    salt = rs.getString("password_salt");
                    iterations = rs.getInt("password_iterations");
                }
            }

            if (!PasswordHasher.verifyPassword(
                    currentPassword, salt, storedHash, iterations)) {
                throw new IllegalArgumentException("The current password is incorrect.");
            }

            String newSalt = PasswordHasher.generateSalt();
            String newHash = PasswordHasher.hashPassword(newPassword, newSalt);
            String updateSql = """
                    UPDATE users
                    SET password_hash = ?,
                        password_salt = ?,
                        password_iterations = ?
                    WHERE user_id = ?
                    """;

            try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                statement.setString(1, newHash);
                statement.setString(2, newSalt);
                statement.setInt(3, PasswordHasher.getIterations());
                statement.setLong(4, userId);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Password update did not complete.");
                }
            }
        }
    }

    public void updateNotificationPreference(long userId, boolean enabled) throws Exception {
        updateNotificationPreferences(userId, enabled, null, null);
    }

    /** Persists the per-user sound toggle and selected built-in tone. */
    public void updateNotificationSoundPreference(
            long userId, boolean enabled, String sound) throws Exception {
        updateNotificationPreferences(userId, null, enabled, sound);
    }

    private void updateNotificationPreferences(
            long userId, Boolean enabled, Boolean soundEnabled, String sound)
            throws Exception {
        if (userId <= 0) {
            throw new IllegalArgumentException("A signed-in account is required.");
        }
        List<Object> values = new ArrayList<>();
        StringBuilder assignments = new StringBuilder();
        if (enabled != null) {
            assignments.append("notifications_enabled = ?");
            values.add(enabled);
        }
        if (soundEnabled != null) {
            if (assignments.length() > 0) {
                assignments.append(", ");
            }
            assignments.append("notification_sound_enabled = ?");
            values.add(soundEnabled);
            if (assignments.length() > 0) {
                assignments.append(", ");
            }
            assignments.append("notification_sound = ?");
            values.add(NotificationSound.normalize(sound));
        }
        if (assignments.length() == 0) {
            return;
        }
        String sql = "UPDATE users SET " + assignments + " WHERE user_id = ?";
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (Object value : values) {
                statement.setObject(index++, value);
            }
            statement.setLong(index, userId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Notification preference could not be saved.");
            }
        }
    }

    /**
     * Updates the username, display name, own department, and avatar of the signed-in
     * account in one write. The id always comes from the authenticated actor, so no
     * other account can be changed here. Username matching follows the login rule:
     * values are trimmed and compared case-insensitively, and the current account is
     * excluded from the duplicate check. Email, role, and borrower type stay read-only.
     */
    public void updateOwnProfile(
            long userId, String username, String fullName, String department,
            byte[] profilePicture) throws Exception {

        if (userId <= 0) {
            throw new IllegalArgumentException("A signed-in account is required.");
        }
        String trimmedUsername = username == null ? "" : username.trim();
        if (trimmedUsername.isEmpty()) {
            throw new IllegalArgumentException("Username is required.");
        }
        if (trimmedUsername.length() > 60) {
            throw new IllegalArgumentException("Username must be 60 characters or fewer.");
        }
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("Full name is required.");
        }
        String trimmedName = fullName.trim();
        if (trimmedName.length() > 150) {
            throw new IllegalArgumentException("Full name must be 150 characters or fewer.");
        }
        if (department == null || department.isBlank()) {
            throw new IllegalArgumentException("Department is required.");
        }
        String trimmedDepartment = department.trim();
        if (trimmedDepartment.length() > 120) {
            throw new IllegalArgumentException("Department must be 120 characters or fewer.");
        }
        if (profilePicture != null && profilePicture.length > ProfilePicture.MAX_STORED_BYTES) {
            throw new IllegalArgumentException("The profile picture is too large.");
        }

        try (Connection connection = Database.getConnection()) {
            try (PreparedStatement check = connection.prepareStatement("""
                    SELECT 1 FROM users
                    WHERE LOWER(BTRIM(username)) = LOWER(?) AND user_id <> ?
                    LIMIT 1
                    """)) {
                check.setString(1, trimmedUsername);
                check.setLong(2, userId);
                try (ResultSet existing = check.executeQuery()) {
                    if (existing.next()) {
                        throw new UsernameAlreadyExistsException();
                    }
                }
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE users
                    SET username = ?, full_name = ?, department = ?, profile_picture = ?
                    WHERE user_id = ?
                    """)) {
                statement.setString(1, trimmedUsername);
                statement.setString(2, trimmedName);
                statement.setString(3, trimmedDepartment);
                statement.setBytes(4, profilePicture);
                statement.setLong(5, userId);
                try {
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("Account information could not be saved.");
                    }
                } catch (SQLException exception) {
                    // The PostgreSQL uniqueness guard is the final authority when
                    // another session changes the same username concurrently.
                    if ("23505".equals(exception.getSQLState())) {
                        throw new UsernameAlreadyExistsException();
                    }
                    throw exception;
                }
            }
        }
    }

    public void requestPasswordReset(String usernameOrEmail, String registeredEmail) throws Exception {
        if (usernameOrEmail == null || usernameOrEmail.isBlank()) {
            throw new IllegalArgumentException("Enter your username or email.");
        }

        boolean emailProvided = registeredEmail != null && !registeredEmail.isBlank();
        if (!emailProvided) {
            throw new IllegalArgumentException(
                    "Enter the email registered to your account for identity verification.");
        }

        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Long userId = null;
                String username = null;
                String fullName = null;
                boolean emailMatches = false;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT user_id, username, full_name, email
                        FROM users
                        WHERE LOWER(username) = LOWER(?) OR LOWER(email) = LOWER(?)
                        FOR UPDATE
                        """)) {
                    String identity = usernameOrEmail.trim();
                    statement.setString(1, identity);
                    statement.setString(2, identity);
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next()) {
                            userId = result.getLong("user_id");
                            username = result.getString("username");
                            fullName = result.getString("full_name");
                            emailMatches = result.getString("email") != null
                                    && result.getString("email").trim().equalsIgnoreCase(registeredEmail.trim());
                        }
                    }
                }

                if (!emailMatches) {
                    throw new IllegalArgumentException(
                            "The registered email does not match this account.");
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT reset_request_id
                        FROM password_reset_requests
                        WHERE user_id = ? AND status IN ('PENDING', 'APPROVED')
                          AND registered_email_match = TRUE AND identity_verified = TRUE
                        FOR UPDATE
                        """)) {
                    statement.setLong(1, userId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            long resetRequestId;
                            try (PreparedStatement insert = connection.prepareStatement("""
                                    INSERT INTO password_reset_requests
                                    (user_id, registered_email_match, identity_verified)
                                    VALUES (?, TRUE, TRUE)
                                    RETURNING reset_request_id
                                    """)) {
                                insert.setLong(1, userId);
                                try (ResultSet inserted = insert.executeQuery()) {
                                    if (!inserted.next()) {
                                        throw new IllegalStateException(
                                                "Password reset request could not be saved.");
                                    }
                                    resetRequestId = inserted.getLong(1);
                                }
                            }

                            // request_id intentionally stays NULL: notifications.request_id
                            // references borrow_requests, not password_reset_requests.
                            String requester = fullName == null || fullName.isBlank()
                                    ? (username == null || username.isBlank()
                                            ? "Unknown user" : username.trim())
                                    : fullName.trim()
                                            + (username == null || username.isBlank()
                                                    ? "" : " (" + username.trim() + ")");
                            String message = "Password change request #" + resetRequestId
                                    + " from " + requester + ".";
                            try (PreparedStatement notify = connection.prepareStatement("""
                                    INSERT INTO notifications
                                        (user_id, title, message, department, action)
                                    SELECT u.user_id, ?, ?, NULL, ?
                                    FROM users u
                                    WHERE u.role = 'ICT_STAFF' AND u.active = TRUE
                                      AND COALESCE(u.notifications_enabled, TRUE) = TRUE
                                      AND NOT EXISTS (
                                          SELECT 1 FROM notifications n
                                          WHERE n.user_id = u.user_id
                                            AND n.action = ?
                                            AND n.message = ?
                                      )
                                    """)) {
                                notify.setString(1, Notification.TITLE_PASSWORD_CHANGE_REQUEST);
                                notify.setString(2, message);
                                notify.setString(3, Notification.ACTION_PASSWORD_CHANGE_REQUEST);
                                notify.setString(4, Notification.ACTION_PASSWORD_CHANGE_REQUEST);
                                notify.setString(5, message);
                                notify.executeUpdate();
                            }
                        }
                    }
                }
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }
}
