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

/** Form validation, inserts and availability checks for BorrowRequestService. */
public abstract class BorrowRequestValidation extends BorrowRequestNotifications {

    public int countActiveForBorrower(long borrowerId) throws SQLException {
        return countRequests(borrowerId,
                "('PENDING_STAFF', 'NEEDS_ACTION', 'PENDING_ADMIN', "
                        + "'PENDING_DIRECTOR', 'APPROVED', 'RELEASED')");
    }

    public int countPendingForBorrower(long borrowerId) throws SQLException {
        return countRequests(borrowerId,
                "('PENDING_STAFF', 'NEEDS_ACTION', 'PENDING_ADMIN', "
                        + "'PENDING_DIRECTOR', 'APPROVED')");
    }

    public int countBorrowedForBorrower(long borrowerId) throws SQLException {
        return countRequests(borrowerId, "('RELEASED')");
    }

    void notifyAfterDepartmentDecision(
            Connection connection, long requestId, String previousStatus, String nextStatus)
            throws SQLException {
        if (previousStatus.equals(nextStatus)) {
            return;
        }
        if ("PENDING_ADMIN".equals(nextStatus)) {
            notifyBorrower(connection, requestId,
                    "Request ready for approval",
                    "Every department finished its review. Your request is waiting for administrative review.",
                    null, "PENDING_ADMIN");
            notifyRole(connection, "ADMIN", requestId,
                    "Request awaiting review",
                    "All departments decided on this request. It is waiting for administrative review.",
                    null, "PENDING_ADMIN");
        } else if ("REJECTED".equals(nextStatus)) {
            notifyBorrower(connection, requestId,
                    "Request declined",
                    "Every department declined its equipment, so the request was declined.",
                    null, "DECLINED");
            notifyRole(connection, "ADMIN", requestId,
                    "Request declined by all departments",
                    "Every department declined its equipment for this request.",
                    null, "DECLINED");
        }
    }
    String requireCustodianRole(String role) {
        String normalizedRole = role == null ? "" : role.trim().toUpperCase();
        if (!"ICT_STAFF".equals(normalizedRole)
                && !"FACILITY_STAFF".equals(normalizedRole)) {
            throw new SecurityException(
                    "Only equipment staff can manage this request group.");
        }
        return normalizedRole;
    }

    int countRequests(long borrowerId, String statuses) throws SQLException {
        String sql = "SELECT COUNT(*) FROM borrow_requests "
                + "WHERE deleted_at IS NULL AND borrower_id = ? AND status IN " + statuses;
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, borrowerId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    static String placeholders(int count) {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) in.append(',');
            in.append('?');
        }
        return in.toString();
    }

    static LocalDateTime toDateTime(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
    void validate(BorrowRequestForm form) {
        if (form == null) {
            throw new IllegalArgumentException("Request details are required.");
        }
        List<RequestOccurrence> occs = effectiveOccurrences(form);
        if (occs.isEmpty()) {
            throw new IllegalArgumentException("Add at least one schedule.");
        }
        RequestOccurrence first = occs.get(0);
        if (form.getPurpose().isBlank()
                || form.getParticipants().isBlank()
                || form.getPersonInCharge().isBlank()
                || form.getPosition().isBlank()) {
            throw new IllegalArgumentException("Complete all required request details.");
        }
        if (form.getParticipants().length() > 255
                || form.getPersonInCharge().length() > 150
                || form.getPosition().length() > 100) {
            throw new IllegalArgumentException(
                    "Some request details are too long. Shorten the participants, person in charge, or position.");
        }
        if (form.getBorrowerRemarks().length() > BorrowRequestForm.MAX_REMARKS_LENGTH) {
            throw new IllegalArgumentException("Remarks cannot exceed "
                    + BorrowRequestForm.MAX_REMARKS_LENGTH + " characters.");
        }
        if (form.isUrgent() && form.getUrgentReason().isBlank()) {
            throw new IllegalArgumentException(
                    "Explain why the request is urgent, or clear the urgent option.");
        }
        if (form.getUrgentReason().length() > BorrowRequestForm.MAX_URGENT_REASON_LENGTH) {
            throw new IllegalArgumentException("The urgency reason cannot exceed "
                    + BorrowRequestForm.MAX_URGENT_REASON_LENGTH + " characters.");
        }
        for (RequestOccurrence o : occs) {
            if (o.getVenue() == null || o.getVenue().isBlank() || o.getVenue().length() > 150
                    || o.getEventDate() == null || o.getStartTime() == null || o.getEndTime() == null) {
                throw new IllegalArgumentException("Complete venue, date, start time, and end time for each schedule.");
            }
            if (o.getEventDate().isBefore(LocalDate.now(ZoneId.of("Asia/Manila")))) {
                throw new IllegalArgumentException("Schedule dates cannot be in the past.");
            }
            if (!o.getEndTime().isAfter(o.getStartTime())) {
                throw new IllegalArgumentException("Each schedule's end time must be after its start time.");
            }
        }
        // One overall request owns one room reservation, so every schedule must name
        // the same venue regardless of how many occurrences the request has.
        String reservedRoom = first.getVenue() == null ? "" : first.getVenue().trim();
        for (RequestOccurrence o : occs) {
            if (!reservedRoom.equalsIgnoreCase(o.getVenue() == null ? "" : o.getVenue().trim())) {
                throw new IllegalArgumentException(
                        "A request can reserve only one room. Use the same venue for every schedule.");
            }
        }
        // One request reserves the same equipment and room for every occurrence, so
        // two overlapping occurrences would double-book its own units.
        for (int i = 0; i < occs.size(); i++) {
            RequestOccurrence a = occs.get(i);
            for (int j = i + 1; j < occs.size(); j++) {
                RequestOccurrence b = occs.get(j);
                boolean overlap = a.getEventDate().atTime(a.getStartTime())
                        .isBefore(b.getEventDate().atTime(b.getEndTime()))
                        && a.getEventDate().atTime(a.getEndTime())
                        .isAfter(b.getEventDate().atTime(b.getStartTime()));
                if (overlap) {
                    throw new IllegalArgumentException(
                            "Schedules in the same request cannot overlap. "
                                    + "Combine them into one time range.");
                }
            }
        }
        if (form.getEquipmentItems().isEmpty()) {
            throw new IllegalArgumentException("Add at least one equipment item.");
        }

        Set<Long> equipmentIds = new HashSet<>();
        for (EquipmentRequestItem item : form.getEquipmentItems()) {
            if (item.getEquipmentId() <= 0 || item.getRequestedQuantity() <= 0) {
                throw new IllegalArgumentException("Select equipment and enter a valid quantity.");
            }
            if (!equipmentIds.add(item.getEquipmentId())) {
                throw new IllegalArgumentException(
                        "Each equipment type can only be added once.");
            }
        }
    }

    List<RequestOccurrence> effectiveOccurrences(BorrowRequestForm form) {
        if (form.getOccurrences() != null && !form.getOccurrences().isEmpty()) {
            return form.getOccurrences();
        }
        if (form.getEventDate() != null && form.getStartTime() != null && form.getEndTime() != null) {
            return List.of(new RequestOccurrence(form.getEventDate(), form.getStartTime(),
                    form.getEndTime(), form.getVenue()));
        }
        return List.of();
    }

    long insertRequest(
            Connection connection,
            long borrowerId,
            BorrowRequestForm form) throws SQLException {
        String sql = """
                INSERT INTO borrow_requests (
                    request_code, borrower_id, venue, event_date, start_time,
                    end_time, purpose, participants, person_in_charge, position,
                    borrower_remarks, urgent, urgent_reason, status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_STAFF')
                """;
        String code = "EL-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 10).toUpperCase();
        List<RequestOccurrence> occs = effectiveOccurrences(form);
        RequestOccurrence first = occs.get(0);
        try (PreparedStatement statement = connection.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, code);
            statement.setLong(2, borrowerId);
            statement.setString(3, first.getVenue());
            statement.setDate(4, Date.valueOf(first.getEventDate()));
            statement.setTime(5, Time.valueOf(first.getStartTime()));
            statement.setTime(6, Time.valueOf(first.getEndTime()));
            statement.setString(7, form.getPurpose());
            statement.setString(8, form.getParticipants());
            statement.setString(9, form.getPersonInCharge());
            statement.setString(10, form.getPosition());
            statement.setString(11, blankToNull(form.getBorrowerRemarks()));
            statement.setBoolean(12, form.isUrgent());
            statement.setString(13, form.isUrgent()
                    ? blankToNull(form.getUrgentReason()) : null);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Database did not return a request ID.");
                }
                return keys.getLong(1);
            }
        }
    }

    List<Long> insertOccurrences(Connection connection, long requestId, BorrowRequestForm form)
            throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO request_occurrences (request_id, event_date, start_time, end_time, venue)
                VALUES (?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            for (RequestOccurrence o : effectiveOccurrences(form)) {
                statement.setLong(1, requestId);
                statement.setDate(2, Date.valueOf(o.getEventDate()));
                statement.setTime(3, Time.valueOf(o.getStartTime()));
                statement.setTime(4, Time.valueOf(o.getEndTime()));
                statement.setString(5, o.getVenue());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) ids.add(keys.getLong(1));
                }
            }
        }
        return ids;
    }

    void bindForm(
            PreparedStatement statement,
            BorrowRequestForm form,
            int offset) throws SQLException {
        List<RequestOccurrence> occs = effectiveOccurrences(form);
        RequestOccurrence first = occs.get(0);
        statement.setString(1 + offset, first.getVenue());
        statement.setDate(2 + offset, Date.valueOf(first.getEventDate()));
        statement.setTime(3 + offset, Time.valueOf(first.getStartTime()));
        statement.setTime(4 + offset, Time.valueOf(first.getEndTime()));
        statement.setString(5 + offset, form.getPurpose());
        statement.setString(6 + offset, form.getParticipants());
        statement.setString(7 + offset, form.getPersonInCharge());
        statement.setString(8 + offset, form.getPosition());
    }

    long insertGroup(Connection connection, long requestId, Long occurrenceId, String role)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO request_groups (request_id, occurrence_id, custodian_role, status)
                VALUES (?, ?, ?, 'PENDING')
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, requestId);
            if (occurrenceId == null) statement.setNull(2, Types.BIGINT);
            else statement.setLong(2, occurrenceId);
            statement.setString(3, role);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Database did not return a request group ID.");
                }
                return keys.getLong(1);
            }
        }
    }

    String lockEditableRequest(
            Connection connection,
            long borrowerId,
            long requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status
                FROM borrow_requests
                WHERE request_id = ? AND borrower_id = ? AND deleted_at IS NULL
                FOR UPDATE
                """)) {
            statement.setLong(1, requestId);
            statement.setLong(2, borrowerId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Request was not found.");
                }
                String status = result.getString("status");
                if (!Arrays.asList(EDITABLE_STATUSES).contains(status)) {
                    throw new IllegalArgumentException(
                            "Only pending requests that have not been processed can be changed.");
                }
                return status;
            }
        }
    }

    void ensureGroupsArePending(Connection connection, long requestId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM request_groups
                WHERE request_id = ?
                  AND status NOT IN ('PENDING', 'UNABLE', 'CANCELLED', 'DECLINED')
                """)) {
            statement.setLong(1, requestId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getInt(1) > 0) {
                    throw new IllegalArgumentException(
                            "This request can no longer be changed because staff has processed it.");
                }
            }
        }
    }

    /**
     * A room is held once a request reaches final approval. Pending requests are
     * allowed to overlap (they are reported as conflicts to staff instead), so a
     * tentative request never blocks other borrowers by itself. Overlap uses the
     * same rule as equipment: new start before existing end and new end after
     * existing start.
     */
    void assertRoomsAvailable(
            Connection connection, List<RequestOccurrence> occurrences,
            Long excludedRequestId) throws SQLException {
        if (occurrences == null || occurrences.isEmpty()) {
            return;
        }
        String sql = """
                SELECT br.request_code, COALESCE(ro.event_date, br.event_date) AS event_date,
                       COALESCE(ro.start_time, br.start_time) AS start_time,
                       COALESCE(ro.end_time, br.end_time) AS end_time
                FROM request_occurrences ro
                JOIN borrow_requests br ON br.request_id = ro.request_id
                WHERE br.deleted_at IS NULL
                  AND br.status IN ('APPROVED', 'RELEASED')
                  AND UPPER(BTRIM(ro.venue)) = UPPER(BTRIM(?))
                  AND (ro.event_date + ro.start_time) < ?
                  AND (ro.event_date + ro.end_time) > ?
                """
                + (excludedRequestId == null ? "" : "  AND br.request_id <> ?\n")
                + "LIMIT 1";
        for (RequestOccurrence occurrence : occurrences) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, occurrence.getVenue());
                statement.setTimestamp(2, Timestamp.valueOf(
                        occurrence.getEventDate().atTime(occurrence.getEndTime())));
                statement.setTimestamp(3, Timestamp.valueOf(
                        occurrence.getEventDate().atTime(occurrence.getStartTime())));
                if (excludedRequestId != null) {
                    statement.setLong(4, excludedRequestId);
                }
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        throw new IllegalArgumentException("Room " + occurrence.getVenue()
                                + " is already booked on "
                                + result.getDate("event_date").toLocalDate() + " from "
                                + result.getTime("start_time").toLocalTime() + " to "
                                + result.getTime("end_time").toLocalTime()
                                + " (" + result.getString("request_code")
                                + "). Choose another room or time.");
                    }
                }
            }
        }
    }

    /** Rechecks the room for every schedule stored on one request (approval time). */
    void assertRequestRoomsAvailable(Connection connection, long requestId)
            throws SQLException {
        List<RequestOccurrence> occurrences = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT event_date, start_time, end_time, venue
                FROM request_occurrences
                WHERE request_id = ?
                ORDER BY event_date, start_time
                """)) {
            statement.setLong(1, requestId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    occurrences.add(new RequestOccurrence(
                            result.getDate("event_date").toLocalDate(),
                            result.getTime("start_time").toLocalTime(),
                            result.getTime("end_time").toLocalTime(),
                            result.getString("venue")));
                }
            }
        }
        assertRoomsAvailable(connection, occurrences, requestId);
    }

    /**
     * Re-checks every already-assigned item at review time. The request itself is
     * excluded from the overlap sum because its own assignment is expected; only a
     * competing request that was approved or assigned in the meantime can fail the
     * check. Equipment rows are locked in id order so two simultaneous approvals
     * cannot both promise the same units.
     */
    void assertRequestEquipmentAvailable(Connection connection, long requestId)
            throws SQLException {
        List<Long> equipmentIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT DISTINCT ri.assigned_equipment_id
                FROM request_items ri
                JOIN request_groups rg ON rg.group_id = ri.group_id
                WHERE ri.request_id = ?
                  AND rg.status = 'ASSIGNED'
                  AND ri.assigned_equipment_id IS NOT NULL
                  AND ri.assigned_quantity > 0
                ORDER BY ri.assigned_equipment_id
                """)) {
            statement.setLong(1, requestId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    equipmentIds.add(result.getLong(1));
                }
            }
        }
        for (long equipmentId : equipmentIds) {
            try (PreparedStatement lock = connection.prepareStatement(
                    "SELECT 1 FROM equipment WHERE equipment_id = ? FOR UPDATE")) {
                lock.setLong(1, equipmentId);
                lock.executeQuery().close();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT br.request_code, e.equipment_name,
                       ri.assigned_equipment_id, ri.assigned_quantity,
                       COALESCE(ro.event_date, br.event_date) AS event_date,
                       COALESCE(ro.start_time, br.start_time) AS start_time,
                       COALESCE(ro.end_time, br.end_time) AS end_time
                FROM request_items ri
                JOIN request_groups rg ON rg.group_id = ri.group_id
                JOIN borrow_requests br ON br.request_id = ri.request_id
                LEFT JOIN request_occurrences ro ON ro.occurrence_id = rg.occurrence_id
                JOIN equipment e ON e.equipment_id = ri.assigned_equipment_id
                WHERE ri.request_id = ?
                  AND rg.status = 'ASSIGNED'
                  AND ri.assigned_equipment_id IS NOT NULL
                  AND ri.assigned_quantity > 0
                ORDER BY ri.request_item_id
                """)) {
            statement.setLong(1, requestId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    LocalDate day = result.getDate("event_date").toLocalDate();
                    LocalTime start = result.getTime("start_time").toLocalTime();
                    LocalTime end = result.getTime("end_time").toLocalTime();
                    // Exclude this request's own assignment; only competing bookings count.
                    int available = EquipmentService.findAvailableQuantity(
                            connection, result.getLong("assigned_equipment_id"),
                            requestId, day, start, end);
                    int assigned = result.getInt("assigned_quantity");
                    if (assigned > available) {
                        throw new IllegalArgumentException(
                                "Equipment " + result.getString("equipment_name")
                                        + " is no longer available for "
                                        + day + " " + start + "\u2013" + end
                                        + " (" + available + " free, "
                                        + assigned + " assigned). Resolve the conflict "
                                        + "before approving " + result.getString("request_code")
                                        + ".");
                    }
                }
            }
        }
    }

}
