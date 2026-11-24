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

public class NotificationService extends NotificationReminders {

    public List<Notification> findForUser(long userId) throws SQLException {
        try {
            createUpcomingReleaseReminders(userId);
        } catch (Exception exception) {
            System.err.println("Unable to create upcoming release reminders: "
                    + exception.getMessage());
        }
        try {
            runOverdueSweep();
        } catch (Exception exception) {
            System.err.println("Unable to run overdue sweep: " + exception.getMessage());
        }
        String sql = """
                SELECT n.notification_id, n.user_id, n.request_id, n.title,
                       n.message, n.is_read, n.created_at, n.department, n.action,
                       br.event_date, br.request_code, br.status AS request_status,
                       COALESCE(string_agg(DISTINCT e.equipment_name, ', '), '') AS equipment_names,
                       COALESCE(string_agg(DISTINCT e.category, ', '), '') AS equipment_categories
                FROM notifications n
                JOIN users recipient ON recipient.user_id = n.user_id
                LEFT JOIN borrow_requests br ON br.request_id = n.request_id
                LEFT JOIN request_items ri ON ri.request_id = br.request_id
                LEFT JOIN equipment e ON e.equipment_id = COALESCE(
                        ri.assigned_equipment_id, ri.requested_equipment_id)
                WHERE n.user_id = ? AND n.is_deleted = FALSE
                """ + RECEIPT_RECIPIENT_GUARD + DEPARTMENT_RECIPIENT_GUARD
                + ADMIN_NOTIFICATION_GUARD + """
                GROUP BY n.notification_id, n.user_id, n.request_id, n.title,
                         n.message, n.is_read, n.created_at, n.department, n.action,
                         br.event_date, br.request_code, br.status
                ORDER BY n.created_at DESC, n.notification_id DESC
                LIMIT 200
                """;
        List<Notification> notifications = new ArrayList<>();
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            Calendar manila = Calendar.getInstance(TimeZone.getTimeZone("Asia/Manila"));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long requestId = result.getLong("request_id");
                    Long nullableRequestId = result.wasNull() ? null : requestId;
                    java.sql.Date eventDate = result.getDate("event_date");
                    java.sql.Timestamp created = result.getTimestamp("created_at", manila);
                    notifications.add(new Notification(
                            result.getLong("notification_id"),
                            result.getLong("user_id"),
                            nullableRequestId,
                            result.getString("title"),
                            result.getString("message"),
                            result.getBoolean("is_read"),
                            created == null ? null : created.toLocalDateTime(),
                            result.getString("equipment_names"),
                            result.getString("equipment_categories"),
                            eventDate == null ? null : eventDate.toLocalDate())
                            .withDetails(
                                    result.getString("request_code"),
                                    result.getString("department"),
                                    result.getString("action"),
                                    result.getString("request_status")));
                }
            }
        }
        return notifications;
    }
}
