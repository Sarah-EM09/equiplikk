package src.service;

import src.database.Database;
import src.model.PasswordResetRequest;
import src.model.User;
import src.util.PasswordHasher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/** Admin account management and password reset approval for AuthService. */
public abstract class AuthServiceAdminActions extends AuthServiceUserActions {

    /** ICT creates faculty accounts, which receive priority request handling. */
    public void createFacultyAccount(
            String actorRole,
            String username,
            String email,
            String fullName,
            String department,
            char[] password) throws Exception {
        requireIctStaff(actorRole);
        if (username == null || username.isBlank()
                || email == null || email.isBlank()
                || fullName == null || fullName.isBlank()
                || password == null || password.length < 8) {
            throw new IllegalArgumentException(
                    "Complete name, username, and email. Password must be at least 8 characters.");
        }
        if (!email.trim().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }
        try {
            insertBorrower(username, email, fullName, department, "FACULTY", password);
        } catch (java.sql.SQLException exception) {
            if ("23505".equals(exception.getSQLState())) {
                throw new IllegalArgumentException(
                        "That username or email is already in use.");
            }
            throw exception;
        }
    }

    public void resetPasswordAfterApproval(
            String usernameOrEmail,
            String registeredEmail,
            char[] newPassword) throws Exception {
        if (usernameOrEmail == null || usernameOrEmail.isBlank()
                || newPassword == null || newPassword.length < 8) {
            throw new IllegalArgumentException(
                    "Enter your username or email and a password of at least 8 characters.");
        }

        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long userId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT user_id, email, role
                        FROM users
                        WHERE LOWER(username) = LOWER(?) OR LOWER(email) = LOWER(?)
                        FOR UPDATE
                        """)) {
                    String identity = usernameOrEmail.trim();
                    statement.setString(1, identity);
                    statement.setString(2, identity);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new IllegalArgumentException(
                                    "There is no approved password reset request for this account.");
                        }
                        if (registeredEmail == null || registeredEmail.isBlank()
                                || result.getString("email") == null
                                || !result.getString("email").trim().equalsIgnoreCase(registeredEmail.trim())) {
                            throw new IllegalArgumentException(
                                    "The registered email does not match this account.");
                        }
                        userId = result.getLong("user_id");
                    }
                }

                long requestId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT reset_request_id
                        FROM password_reset_requests
                        WHERE user_id = ? AND status = 'APPROVED'
                          AND registered_email_match = TRUE AND identity_verified = TRUE
                        ORDER BY requested_at DESC
                        LIMIT 1
                        FOR UPDATE
                        """)) {
                    statement.setLong(1, userId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new IllegalArgumentException(
                                    "There is no verified, approved password reset request for this account.");
                        }
                        requestId = result.getLong("reset_request_id");
                    }
                }

                String salt = PasswordHasher.generateSalt();
                String hash = PasswordHasher.hashPassword(newPassword, salt);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE users
                        SET password_hash = ?, password_salt = ?, password_iterations = ?
                        WHERE user_id = ?
                        """)) {
                    statement.setString(1, hash);
                    statement.setString(2, salt);
                    statement.setInt(3, PasswordHasher.getIterations());
                    statement.setLong(4, userId);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("Password update did not complete.");
                    }
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE password_reset_requests
                        SET status = 'COMPLETED'
                        WHERE reset_request_id = ? AND status = 'APPROVED'
                        """)) {
                    statement.setLong(1, requestId);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException(
                                "The approved password reset could not be completed.");
                    }
                }
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<PasswordResetRequest> findPasswordResetRequests(String actorRole)
            throws Exception {
        requirePasswordResetReviewerRole(actorRole);
        String sql = """
                SELECT pr.reset_request_id, u.user_id, u.username, u.full_name,
                       u.email, u.role, u.department, pr.requested_at,
                       pr.registered_email_match, pr.identity_verified
                FROM password_reset_requests pr
                JOIN users u ON u.user_id = pr.user_id
                WHERE pr.status = 'PENDING'
                ORDER BY pr.requested_at ASC, pr.reset_request_id ASC
                """;
        List<PasswordResetRequest> requests = new ArrayList<>();
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                requests.add(new PasswordResetRequest(
                        result.getLong("reset_request_id"),
                        result.getLong("user_id"),
                        result.getString("username"),
                        result.getString("full_name"),
                        result.getString("email"),
                        result.getString("role"),
                        result.getString("department"),
                        result.getTimestamp("requested_at").toLocalDateTime(),
                        result.getBoolean("registered_email_match"),
                        result.getBoolean("identity_verified")));
            }
        }
        return requests;
    }

    public List<User> findBorrowersForIct(String actorRole) throws Exception {
        requireIctStaff(actorRole);
        List<User> users = new ArrayList<>();
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT user_id, username, email, full_name, department,
                               role, borrower_type, active, deleted_at
                        FROM users
                        WHERE role = 'BORROWER'
                        ORDER BY borrower_type, department NULLS LAST, full_name
                        """);
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                User user = new User(
                        result.getLong("user_id"),
                        result.getString("username"),
                        result.getString("email"),
                        result.getString("full_name"),
                        result.getString("department"),
                        result.getString("role"),
                        result.getString("borrower_type"),
                        result.getBoolean("active"));
                java.sql.Timestamp deleted = result.getTimestamp("deleted_at");
                user.setDeletedAt(deleted == null ? null : deleted.toLocalDateTime());
                users.add(user);
            }
        }
        return users;
    }

    /**
     * Deactivates or reactivates a borrower without removing any of its data.
     * This only flips the existing {@code users.active} switch; requests,
     * approvals, notifications, and audit records are deliberately untouched.
     */
    public void setBorrowerActive(
            String actorRole, long actorId, long userId, boolean active)
            throws Exception {

        requireIctStaff(actorRole);
        if (actorId <= 0) {
            throw new SecurityException("A signed-in account is required.");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("Invalid borrower account.");
        }

        try (Connection connection = Database.getConnection()) {
            // Re-check the actor in the database so a forged role cannot bypass
            // the permission check. Only users.active is changed: requests,
            // notifications and history are deliberately left untouched.
            requireActiveIctAccount(connection, actorId);

            String sql = active
                    ? """
                            UPDATE users
                            SET active = ?
                            WHERE user_id = ? AND role = 'BORROWER'
                              AND deleted_at IS NULL
                            """
                    : """
                            UPDATE users
                            SET active = ?
                            WHERE user_id = ? AND role = 'BORROWER'
                            """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setBoolean(1, active);
                statement.setLong(2, userId);
                if (statement.executeUpdate() == 0) {
                    throw new IllegalArgumentException(
                            "This borrower account no longer exists.");
                }
            }
        }
    }

    /**
     * Soft-deletes a borrower: the account is disabled and marked deleted, but
     * no user or related record is removed. All borrowing history remains
     * joined to the preserved account row.
     */
    public java.time.LocalDateTime deleteBorrowerAccount(
            String actorRole, long actorId, long userId) throws Exception {

        requireIctStaff(actorRole);
        if (actorId <= 0) {
            throw new SecurityException("A signed-in account is required.");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("Invalid borrower account.");
        }
        if (actorId == userId) {
            throw new IllegalArgumentException("You cannot delete your own account.");
        }

        try (Connection connection = Database.getConnection()) {
            // Re-check the signed-in actor in the database so a forged role
            // cannot bypass the permission check.
            requireActiveIctAccount(connection, actorId);

            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE users
                    SET active = FALSE, deleted_at = CURRENT_TIMESTAMP
                    WHERE user_id = ? AND role = 'BORROWER' AND deleted_at IS NULL
                    RETURNING deleted_at
                    """)) {
                statement.setLong(1, userId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalArgumentException(
                                "This borrower account no longer exists.");
                    }
                    return result.getTimestamp("deleted_at").toLocalDateTime();
                }
            }
        }
    }

    /**
     * Restores a soft-deleted borrower in place: clears the deleted marker and
     * reactivates the original row. The account id, credentials, profile and all
     * related history are untouched. Restoration is refused when another account
     * already uses the same username, so no duplicate login name can be created.
     */
    public void restoreBorrowerAccount(
            String actorRole, long actorId, long userId) throws Exception {

        requireIctStaff(actorRole);
        if (actorId <= 0) {
            throw new SecurityException("A signed-in account is required.");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("Invalid borrower account.");
        }

        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                requireActiveIctAccount(connection, actorId);

                try (PreparedStatement target = connection.prepareStatement("""
                        SELECT username FROM users
                        WHERE user_id = ? AND role = 'BORROWER' AND deleted_at IS NOT NULL
                        FOR UPDATE
                        """)) {
                    target.setLong(1, userId);
                    try (ResultSet result = target.executeQuery()) {
                        if (!result.next()) {
                            throw new IllegalArgumentException(
                                    "This deleted borrower account no longer exists.");
                        }
                        String username = result.getString("username");

                        // Use the same advisory lock as the username uniqueness
                        // trigger so a concurrent rename cannot slip past this check.
                        try (PreparedStatement lock = connection.prepareStatement("""
                                SELECT pg_advisory_xact_lock(
                                        hashtextextended(LOWER(BTRIM(?)), 0))
                                """)) {
                            lock.setString(1, username);
                            lock.execute();
                        }
                        try (PreparedStatement conflict = connection.prepareStatement("""
                                SELECT 1 FROM users
                                WHERE LOWER(BTRIM(username)) = LOWER(BTRIM(?))
                                  AND user_id <> ?
                                LIMIT 1
                                """)) {
                            conflict.setString(1, username);
                            conflict.setLong(2, userId);
                            try (ResultSet taken = conflict.executeQuery()) {
                                if (taken.next()) {
                                    throw new src.util.UsernameConflictException();
                                }
                            }
                        }
                    }
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE users
                        SET active = TRUE, deleted_at = NULL
                        WHERE user_id = ? AND role = 'BORROWER' AND deleted_at IS NOT NULL
                        """)) {
                    statement.setLong(1, userId);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalArgumentException(
                                "This deleted borrower account no longer exists.");
                    }
                }
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    /** Confirms the actor's role and active status from the database itself. */
    private void requireActiveIctAccount(Connection connection, long actorId)
            throws Exception {

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT role, active FROM users WHERE user_id = ?")) {
            statement.setLong(1, actorId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()
                        || !"ICT_STAFF".equalsIgnoreCase(result.getString("role"))
                        || !result.getBoolean("active")) {
                    throw new SecurityException(
                            "Only ICT system administrators can manage borrower accounts.");
                }
            }
        }
    }

    void requireIctStaff(String actorRole) {
        if (!"ICT_STAFF".equalsIgnoreCase(actorRole == null ? "" : actorRole.trim())) {
            throw new SecurityException("Only ICT system administrators can manage this.");
        }
    }

    void requirePasswordResetReviewerRole(String actorRole) {
        String role = actorRole == null ? "" : actorRole.trim().toUpperCase();
        if (!role.equals("ICT_STAFF") && !role.equals("ADMIN") && !role.equals("DIRECTOR")) {
            throw new SecurityException("Only authorized administrators can review password requests.");
        }
    }

    void requirePasswordResetReviewer(
            String actorRole, long actorId, String targetRole, long targetId) {
        requirePasswordResetReviewerRole(actorRole);
        String role = actorRole.trim().toUpperCase();
        String target = targetRole == null ? "" : targetRole.trim().toUpperCase();
        if (actorId == targetId) {
            throw new SecurityException("You cannot review your own password reset request.");
        }
        if (isElevatedRole(target) && !isFacilityOrDirector(role)) {
            throw new SecurityException(
                    "Password recovery for elevated accounts requires a Facility or Campus Director reviewer.");
        }
    }

    boolean isFacilityOrDirector(String role) {
        String value = role == null ? "" : role.trim().toUpperCase();
        return value.equals("ADMIN") || value.equals("DIRECTOR");
    }

    boolean isElevatedRole(String role) {
        String value = role == null ? "" : role.trim().toUpperCase();
        return value.equals("ICT_STAFF") || value.equals("ADMIN") || value.equals("DIRECTOR");
    }
}
