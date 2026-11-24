package src.service;

import src.database.Database;
import src.model.BorrowRequest;
import src.model.BorrowRequestForm;
import src.model.DepartmentStatus;
import src.model.EquipmentRequestItem;
import src.model.ItemInspection;
import src.model.RequestOccurrence;
import src.model.RequestStatus;
import src.util.UndoPolicy;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Room and equipment availability lookups and request history for BorrowRequestService. */
public abstract class BorrowRequestAvailability extends BorrowRequestValidation {

    /**
     * Human-readable availability context for the staff detail view. Every figure is
     * derived; nothing is written and no other borrower's request is changed.
     */
    public List<String> findAvailabilityNotes(long requestId, String scopeRole)
            throws SQLException {
        List<String> notes = new ArrayList<>();
        try (Connection connection = Database.getConnection()) {
            String venue = "";
            String borrowerRemarks = "";
            String urgentReason = "";
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT venue, borrower_remarks, urgent_reason
                    FROM borrow_requests WHERE request_id = ?
                    """)) {
                statement.setLong(1, requestId);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        venue = nullToEmpty(result.getString("venue"));
                        borrowerRemarks = nullToEmpty(result.getString("borrower_remarks"));
                        urgentReason = nullToEmpty(result.getString("urgent_reason"));
                    }
                }
            }

            List<String> roomNotes = new ArrayList<>();
            for (src.model.RoomConflict conflict : findRoomConflicts(scopeRole)) {
                if (conflict.getFirstRequestId() != requestId
                        && conflict.getSecondRequestId() != requestId) {
                    continue;
                }
                boolean firstIsThis = conflict.getFirstRequestId() == requestId;
                String otherCode = firstIsThis
                        ? conflict.getSecondRequestCode() : conflict.getFirstRequestCode();
                roomNotes.add(otherCode + " · " + conflict.getEventDate() + " "
                        + conflict.getStartTime() + "\u2013" + conflict.getEndTime()
                        + " · " + conflict.getVenue());
            }
            notes.add("Room: " + (venue.isBlank() ? "\u2014" : venue) + " — "
                    + (roomNotes.isEmpty()
                            ? "no overlapping bookings" : String.join("; ", roomNotes)));

            String role = scopeRole == null || scopeRole.isBlank()
                    ? null : scopeRole.trim().toUpperCase();
            String sql = """
                    SELECT ri.requested_quantity, ri.assigned_quantity,
                           COALESCE(ri.assigned_equipment_id, ri.requested_equipment_id)
                               AS equipment_id,
                           COALESCE(e.equipment_name, 'Unresolved equipment') AS equipment_name,
                           COALESCE(ro.event_date, br.event_date) AS event_date,
                           COALESCE(ro.start_time, br.start_time) AS start_time,
                           COALESCE(ro.end_time, br.end_time) AS end_time
                    FROM request_items ri
                    JOIN request_groups rg ON rg.group_id = ri.group_id
                    JOIN borrow_requests br ON br.request_id = ri.request_id
                    LEFT JOIN request_occurrences ro ON ro.occurrence_id = rg.occurrence_id
                    LEFT JOIN equipment e ON e.equipment_id =
                        COALESCE(ri.assigned_equipment_id, ri.requested_equipment_id)
                    WHERE ri.request_id = ?
                    """
                    + (role == null ? "" : "  AND rg.custodian_role = ?\n")
                    + "ORDER BY equipment_name, event_date, start_time\n";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, requestId);
                if (role != null) {
                    statement.setString(2, role);
                }
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        long equipmentId = result.getLong("equipment_id");
                        if (result.wasNull()) {
                            equipmentId = 0L;
                        }
                        int requested = result.getInt("requested_quantity");
                        int assigned = result.getInt("assigned_quantity");
                        int quantity = assigned > 0 ? assigned : requested;
                        LocalDate day = result.getDate("event_date").toLocalDate();
                        LocalTime start = result.getTime("start_time").toLocalTime();
                        LocalTime end = result.getTime("end_time").toLocalTime();
                        int inventory = 0;
                        int reserved = 0;
                        int inUse = 0;
                        int onHand = 0;
                        int available = 0;
                        String conflicts = "none";
                        if (equipmentId > 0) {
                            inventory = EquipmentService.findUsableQuantity(connection, equipmentId);
                            reserved = EquipmentService.findReservedQuantity(
                                    connection, equipmentId, null, day, start, end);
                            inUse = EquipmentService.findInUseQuantity(connection, equipmentId);
                            onHand = Math.max(inventory - inUse, 0);
                            available = EquipmentService.findAvailableQuantity(
                                    connection, equipmentId, requestId, day, start, end);
                            conflicts = findCompetingEquipmentCodes(
                                    connection, equipmentId, requestId, day, start, end);
                        }
                        notes.add(result.getString("equipment_name") + " — quantity " + quantity
                                + " (requested " + requested + ", assigned " + assigned
                                + "); inventory " + inventory + "; reserved " + reserved
                                + "; In Use " + inUse + "; available now " + onHand
                                + "; available for this schedule " + available
                                + "; conflicts: " + conflicts);
                    }
                }
            }
            notes.add("Borrower remarks: " + (borrowerRemarks.isBlank() ? "\u2014" : borrowerRemarks));
            if (!urgentReason.isBlank()) {
                notes.add("Urgent reason: " + urgentReason);
            }
        }
        return notes;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String findCompetingEquipmentCodes(
            Connection connection, long equipmentId, long requestId,
            LocalDate eventDate, LocalTime startTime, LocalTime endTime) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT DISTINCT br.request_code
                FROM request_items ri
                JOIN request_groups rg ON rg.group_id = ri.group_id
                JOIN borrow_requests br ON br.request_id = ri.request_id
                LEFT JOIN request_occurrences ro ON ro.occurrence_id = rg.occurrence_id
                WHERE ri.assigned_equipment_id = ?
                  AND br.request_id <> ?
                  AND br.deleted_at IS NULL
                  AND rg.status IN ('ASSIGNED', 'RELEASED', 'RECEIVED')
                  AND (COALESCE(ro.event_date, br.event_date)
                          + COALESCE(ro.start_time, br.start_time)) < ?
                  AND (COALESCE(ro.event_date, br.event_date)
                          + COALESCE(ro.end_time, br.end_time)) > ?
                ORDER BY br.request_code
                """)) {
            statement.setLong(1, equipmentId);
            statement.setLong(2, requestId);
            statement.setTimestamp(3, Timestamp.valueOf(eventDate.atTime(endTime)));
            statement.setTimestamp(4, Timestamp.valueOf(eventDate.atTime(startTime)));
            List<String> codes = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    codes.add(result.getString(1));
                }
            }
            return codes.isEmpty() ? "none" : String.join(", ", codes);
        }
    }

    /**
     * Overlapping room bookings still visible to staff so they can coordinate.
     * Pairs are emitted once and never change either request.
     */
    public List<src.model.RoomConflict> findRoomConflicts(String role) throws SQLException {
        String normalizedRole = role == null ? null : role.trim().toUpperCase();
        String scope = "";
        if ("ICT_STAFF".equals(normalizedRole) || "FACILITY_STAFF".equals(normalizedRole)) {
            scope = """
                      AND EXISTS (
                          SELECT 1 FROM request_groups rg
                          WHERE rg.request_id IN (a.request_id, b.request_id)
                            AND rg.custodian_role = ?
                      )
                    """;
        }
        String sql = """
                SELECT a.request_id AS a_id, a.request_code AS a_code,
                       ua.full_name AS a_borrower, a.status AS a_status,
                       COALESCE((
                           SELECT string_agg(DISTINCT e.equipment_name || ' ×'
                                             || ri.requested_quantity, ', ')
                           FROM request_items ri
                           JOIN equipment e ON e.equipment_id = ri.requested_equipment_id
                           WHERE ri.request_id = a.request_id
                       ), '') AS a_equipment,
                       b.request_id AS b_id, b.request_code AS b_code,
                       ub.full_name AS b_borrower, b.status AS b_status,
                       COALESCE((
                           SELECT string_agg(DISTINCT e.equipment_name || ' ×'
                                             || ri.requested_quantity, ', ')
                           FROM request_items ri
                           JOIN equipment e ON e.equipment_id = ri.requested_equipment_id
                           WHERE ri.request_id = b.request_id
                       ), '') AS b_equipment,
                       aro.venue,
                       aro.event_date,
                       GREATEST(aro.start_time, bro.start_time) AS start_time,
                       LEAST(aro.end_time, bro.end_time) AS end_time
                FROM request_occurrences aro
                JOIN request_occurrences bro
                  ON bro.occurrence_id <> aro.occurrence_id
                 AND bro.event_date = aro.event_date
                 AND UPPER(BTRIM(bro.venue)) = UPPER(BTRIM(aro.venue))
                 AND aro.start_time < bro.end_time
                 AND aro.end_time > bro.start_time
                JOIN borrow_requests a ON a.request_id = aro.request_id
                JOIN borrow_requests b ON b.request_id = bro.request_id
                JOIN users ua ON ua.user_id = a.borrower_id
                JOIN users ub ON ub.user_id = b.borrower_id
                WHERE a.deleted_at IS NULL AND b.deleted_at IS NULL
                  AND a.request_id < b.request_id
                  AND a.status NOT IN ('REJECTED', 'CANCELLED', 'COMPLETED')
                  AND b.status NOT IN ('REJECTED', 'CANCELLED', 'COMPLETED')
                """ + scope + """
                ORDER BY aro.event_date, start_time, aro.venue, a.request_code, b.request_code
                """;
        List<src.model.RoomConflict> conflicts = new ArrayList<>();
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            if (!scope.isEmpty()) {
                statement.setString(1, normalizedRole);
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    conflicts.add(new src.model.RoomConflict(
                            result.getLong("a_id"), result.getString("a_code"),
                            result.getString("a_borrower"), result.getString("a_status"),
                            result.getString("a_equipment"),
                            result.getLong("b_id"), result.getString("b_code"),
                            result.getString("b_borrower"), result.getString("b_status"),
                            result.getString("b_equipment"),
                            result.getString("venue"),
                            result.getDate("event_date").toLocalDate(),
                            result.getTime("start_time").toLocalTime(),
                            result.getTime("end_time").toLocalTime()));
                }
            }
        }
        return conflicts;
    }

    /**
     * Requests that reached full approval on one Manila day. Each request is counted
     * once because final_approved_at is a single column written when the campus
     * director gives final approval; pending and partially approved requests have no
     * value and are excluded.
     */
    public int countFullyApprovedOn(LocalDate day) throws SQLException {
        LocalDate effectiveDay = day == null ? LocalDate.now(MANILA) : day;
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT COUNT(*) FROM borrow_requests
                        WHERE deleted_at IS NULL
                          AND final_approved_at IS NOT NULL
                          AND CAST(final_approved_at AS DATE) = ?
                          AND status NOT IN ('REJECTED', 'CANCELLED')
                        """)) {
            statement.setDate(1, Date.valueOf(effectiveDay));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    void recordHistory(
            Connection connection,
            long requestId,
            Long groupId,
            long actorId,
            String event,
            String fromStatus,
            String toStatus,
            String remarks) throws SQLException {        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO request_history (
                    request_id, group_id, actor_id, event_type,
                    from_status, to_status, remarks
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, requestId);
            if (groupId == null) {
                statement.setNull(2, Types.BIGINT);
            } else {
                statement.setLong(2, groupId);
            }
            statement.setLong(3, actorId);
            statement.setString(4, event);
            statement.setString(5, fromStatus);
            statement.setString(6, toStatus);
            statement.setString(7, remarks);
            statement.executeUpdate();
        }
    }
}
