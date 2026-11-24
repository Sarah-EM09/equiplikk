package src.service;

import src.database.Database;
import src.model.Notification;
import src.model.RequestStatus;
import src.util.UndoPolicy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** Reading, marking and deleting notifications for NotificationService. */
public abstract class NotificationInbox {

    /**
     * Receipt notices are department-scoped by the custodian role of the request's
     * equipment group. Older rows were broadcast with a null department, so the
     * recipient's own role is used here to hide a notice that belongs to the other
     * department. This only affects Borrower confirmed receipt notices; every other
     * notification type is left untouched. Shared by the list and unread count so
     * the badge never advertises a notice the user cannot see.
     */
    static final String RECEIPT_RECIPIENT_GUARD = """
            AND (
                (COALESCE(n.action, '') <> 'BORROWER_RECEIVED'
                 AND LOWER(COALESCE(n.title, '')) NOT LIKE '%confirmed receipt%')
                OR EXISTS (
                    SELECT 1 FROM request_groups rg
                    WHERE rg.request_id = n.request_id
                      AND rg.custodian_role = recipient.role
                )
            )
            """;

    /**
     * A notification tagged with a custodian department belongs to that department.
     * ICT and Facility staff only see their own tag; the reviewer roles and the
     * borrower of the request also see it because department notices are created
     * for them intentionally. Untagged notices are unaffected. Requires the
     * {@code recipient} and {@code br} aliases.
     */
    static final String DEPARTMENT_RECIPIENT_GUARD = """
            AND (
                n.department IS NULL
                OR UPPER(BTRIM(n.department)) NOT IN
                    ('ICT', 'ICT_STAFF', 'FACILITY', 'FACILITY_STAFF')
                OR recipient.role IN ('ADMIN', 'DIRECTOR')
                OR (recipient.role = 'ICT_STAFF'
                    AND UPPER(BTRIM(n.department)) IN ('ICT', 'ICT_STAFF'))
                OR (recipient.role = 'FACILITY_STAFF'
                    AND UPPER(BTRIM(n.department)) IN ('FACILITY', 'FACILITY_STAFF'))
                OR br.borrower_id = recipient.user_id
            )
            """;

    /**
     * Facility Admin is a reviewer, not a department custodian. Only notices that
     * actually ask for or report an administrative decision are visible to that role;
     * department-scoped lifecycle chatter stays in the database but out of the panel.
     */
    static final String ADMIN_NOTIFICATION_GUARD = """
            AND (
                COALESCE(recipient.role, '') <> 'ADMIN'
                OR (
                    (n.department IS NULL OR BTRIM(n.department) = '')
                    AND COALESCE(n.action, '') IN
                        ('PENDING_ADMIN', 'DECLINED', 'DIRECTOR_APPROVED',
                         'DIRECTOR_REJECTED', 'OVERDUE', 'PASSWORD_CHANGE_REQUEST')
                )
            )
            """;

    public int countUnread(long userId) throws SQLException {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT COUNT(*) FROM notifications n
                        JOIN users recipient ON recipient.user_id = n.user_id
                        LEFT JOIN borrow_requests br ON br.request_id = n.request_id
                        WHERE n.user_id = ? AND n.is_read = FALSE AND n.is_deleted = FALSE
                        """ + RECEIPT_RECIPIENT_GUARD + DEPARTMENT_RECIPIENT_GUARD
                        + ADMIN_NOTIFICATION_GUARD)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    /**
     * Newest notification this user can actually see. The live sync uses this to
     * play a sound only when a new notice arrived, not when other requests changed.
     */
    public long latestNotificationId(long userId) throws SQLException {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT COALESCE(MAX(n.notification_id), 0)
                        FROM notifications n
                        JOIN users recipient ON recipient.user_id = n.user_id
                        LEFT JOIN borrow_requests br ON br.request_id = n.request_id
                        WHERE n.user_id = ? AND n.is_deleted = FALSE
                        """ + RECEIPT_RECIPIENT_GUARD + DEPARTMENT_RECIPIENT_GUARD
                        + ADMIN_NOTIFICATION_GUARD)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    public void markRead(long userId, long notificationId) throws SQLException {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE notifications
                        SET is_read = TRUE, read_at = COALESCE(read_at, CURRENT_TIMESTAMP)
                        WHERE notification_id = ? AND user_id = ? AND is_deleted = FALSE
                        """)) {
            statement.setLong(1, notificationId);
            statement.setLong(2, userId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("Notification was not found.");
            }
        }
    }

    public void markAllRead(long userId) throws SQLException {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE notifications
                        SET is_read = TRUE, read_at = COALESCE(read_at, CURRENT_TIMESTAMP)
                        WHERE user_id = ? AND is_read = FALSE AND is_deleted = FALSE
                        """)) {
            statement.setLong(1, userId);
            statement.executeUpdate();
        }
    }

    /**
     * Removes one notification from this user's panel. Only the user's own
     * notification row is touched — requests, approvals, releases, and history stay.
     * The row is soft-deleted so reminders already sent are not generated again.
     */
    public void delete(long userId, long notificationId) throws SQLException {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE notifications
                        SET read_before_delete = is_read,
                            is_deleted = TRUE,
                            deleted_at = CURRENT_TIMESTAMP,
                            is_read = TRUE
                        WHERE notification_id = ? AND user_id = ? AND is_deleted = FALSE
                        """)) {
            statement.setLong(1, notificationId);
            statement.setLong(2, userId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("Notification was not found.");
            }
        }
    }

    /** Removes every notification from this user's panel; returns how many. */
    public int deleteAll(long userId) throws SQLException {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE notifications
                        SET read_before_delete = is_read,
                            is_deleted = TRUE,
                            deleted_at = CURRENT_TIMESTAMP,
                            is_read = TRUE
                        WHERE user_id = ? AND is_deleted = FALSE
                        """)) {
            statement.setLong(1, userId);
            return statement.executeUpdate();
        }
    }

    /**
     * Brings one deleted notification back to the user's list. Restore is only allowed
     * while the shared undo window is open; the original read/unread state is restored.
     */
    public void restore(long userId, long notificationId) throws SQLException {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE notifications
                        SET is_deleted = FALSE,
                            deleted_at = NULL,
                            is_read = COALESCE(read_before_delete, FALSE),
                            read_before_delete = NULL
                        WHERE notification_id = ? AND user_id = ? AND is_deleted = TRUE
                          AND deleted_at >= CURRENT_TIMESTAMP - """
                        + UndoPolicy.RECOVERY_SQL + """
                         """)) {
            statement.setLong(1, notificationId);
            statement.setLong(2, userId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException(
                        "The " + UndoPolicy.RECOVERY_LABEL
                                + " recovery period has ended or the notification no longer exists.");
            }
        }
    }

    /** Restores several notifications deleted together (Undo for "Delete all"). */
    public int restoreAll(long userId, java.util.Collection<Long> notificationIds)
            throws SQLException {
        if (notificationIds == null || notificationIds.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("""
                UPDATE notifications
                SET is_deleted = FALSE,
                    deleted_at = NULL,
                    is_read = COALESCE(read_before_delete, FALSE),
                    read_before_delete = NULL
                WHERE user_id = ? AND is_deleted = TRUE
                  AND deleted_at >= CURRENT_TIMESTAMP - """
                + UndoPolicy.RECOVERY_SQL + " AND notification_id IN (");
        for (int i = 0; i < notificationIds.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(')');
        int restored = 0;
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setLong(1, userId);
            int index = 2;
            for (Long id : notificationIds) {
                statement.setLong(index++, id == null ? 0L : id);
            }
            restored = statement.executeUpdate();
        }
        if (restored == 0) {
            throw new IllegalArgumentException(
                    "The " + UndoPolicy.RECOVERY_LABEL + " recovery period has ended.");
        }
        return restored;
    }

    /**
     * Cheap fingerprint of request activity plus this user's notifications. Screens
     * poll it and reload only when it changes, keeping every dashboard in sync.
     */
    public String changeToken(long userId) throws SQLException {
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT (SELECT COALESCE(MAX(history_id), 0) FROM request_history),
                               (SELECT COALESCE(MAX(updated_at), TIMESTAMP '1970-01-01')
                                  FROM borrow_requests),
                               (SELECT COUNT(*) FROM borrow_requests),
                               (SELECT COALESCE(MAX(notification_id), 0) FROM notifications
                                 WHERE user_id = ?),
                               (SELECT COUNT(*) FROM notifications n
                                 JOIN users recipient ON recipient.user_id = n.user_id
                                 LEFT JOIN borrow_requests br ON br.request_id = n.request_id
                                 WHERE n.user_id = ? AND n.is_deleted = FALSE
                                   AND n.is_read = FALSE
                        """ + RECEIPT_RECIPIENT_GUARD + DEPARTMENT_RECIPIENT_GUARD
                                   + ADMIN_NOTIFICATION_GUARD + """
                               ),
                               (SELECT COUNT(*) FROM notifications
                                 WHERE user_id = ? AND is_deleted = TRUE)
                        """)) {
            statement.setLong(1, userId);
            statement.setLong(2, userId);
            statement.setLong(3, userId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1) + "|" + result.getTimestamp(2) + "|"
                        + result.getLong(3) + "|" + result.getLong(4) + "|"
                        + result.getLong(5) + "|" + result.getLong(6);
            }
        }
    }

    void notifyUsers(Connection connection, String predicate, String value,
            long requestId, String title, String message, String department, String action)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO notifications (user_id, request_id, title, message, department, action) "
                        + "SELECT user_id, ?, ?, ?, ?, ? FROM users WHERE active = TRUE "
                        + "AND COALESCE(notifications_enabled, TRUE) = TRUE AND "
                        + predicate)) {
            statement.setLong(1, requestId);
            statement.setString(2, title);
            statement.setString(3, message);
            statement.setString(4, department);
            statement.setString(5, action);
            statement.setString(6, value);
            statement.executeUpdate();
        }
    }

    void notifyBorrower(Connection connection, long requestId, String title,
            String message, String department) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO notifications (user_id, request_id, title, message, department, action)
                SELECT br.borrower_id, br.request_id, ?, ?, ?, 'OVERDUE'
                FROM borrow_requests br
                JOIN users u ON u.user_id = br.borrower_id
                WHERE br.request_id = ?
                  AND COALESCE(u.notifications_enabled, TRUE) = TRUE
                """)) {
            statement.setString(1, title);
            statement.setString(2, message);
            statement.setString(3, department);
            statement.setLong(4, requestId);
            statement.executeUpdate();
        }
    }
}
