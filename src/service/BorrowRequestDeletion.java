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

/** Delete, restore and staff assignment for BorrowRequestService. */
public abstract class BorrowRequestDeletion extends BorrowRequestAvailability {

    /**
     * Soft-deletes one of the borrower's requests. Every status is deletable, including
     * completed requests. The request's original status is left untouched so Undo can
     * put it back exactly where it was instead of turning it into a cancellation.
     */
    public void deleteRequest(long borrowerId, long requestId) throws Exception {
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String previousStatus = lockOwnRequestForDeletion(connection, borrowerId, requestId);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE borrow_requests
                        SET deleted_at = CURRENT_TIMESTAMP,
                            deleted_by = ?,
                            deletion_reason = ?,
                            status_before_delete = ?,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE request_id = ? AND borrower_id = ? AND deleted_at IS NULL
                        """)) {
                    statement.setLong(1, borrowerId);
                    statement.setString(2, "Deleted by borrower");
                    statement.setString(3, previousStatus);
                    statement.setLong(4, requestId);
                    statement.setLong(5, borrowerId);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("The request could not be deleted.");
                    }
                }
                recordHistory(connection, requestId, null, borrowerId,
                        "BORROWER_DELETED", previousStatus, previousStatus,
                        "Deleted by borrower; recoverable within "
                                + UndoPolicy.RECOVERY_LABEL + ".");
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    /** Restores a request deleted by its borrower, with its original status intact. */
    public void restoreRequest(long borrowerId, long requestId) throws Exception {
        restoreDeletedRequest(borrowerId, borrowerId, requestId, true, "BORROWER_RESTORED");
    }

    /**
     * ICT System Admin deletion is a recoverable soft delete: the row keeps its
     * original status and stays out of every list until it is restored or expires.
     */
    public void deleteRequestAsAdmin(
            String actorRole, long actorId, long requestId, String reason) throws Exception {
        requireIctRole(actorRole);
        String auditReason = reason == null || reason.isBlank()
                ? "Deleted by ICT System Admin" : reason.trim();
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String previousStatus = lockRequestStatus(connection, requestId);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE borrow_requests
                        SET deleted_at = CURRENT_TIMESTAMP,
                            deleted_by = ?,
                            deletion_reason = ?,
                            status_before_delete = ?,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE request_id = ? AND deleted_at IS NULL
                        """)) {
                    statement.setLong(1, actorId);
                    statement.setString(2, auditReason);
                    statement.setString(3, previousStatus);
                    statement.setLong(4, requestId);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("The request could not be deleted.");
                    }
                }
                recordHistory(connection, requestId, null, actorId,
                        "ADMIN_DELETED", previousStatus, previousStatus, auditReason);
                notifyBorrower(connection, requestId, "Request deleted",
                        "The ICT System Admin deleted this request. Reason: " + auditReason,
                        null, "ADMIN_DELETED");
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    /** Restores a request deleted by the ICT System Admin, with its original status intact. */
    public void restoreRequestAsAdmin(
            String actorRole, long actorId, long requestId) throws Exception {
        requireIctRole(actorRole);
        restoreDeletedRequest(null, actorId, requestId, false, "ADMIN_RESTORED");
    }

    /**
     * Shared restore path. Only rows deleted inside the recovery window are brought
     * back; the status column is never rewritten, so approvals and completions survive.
     */
    void restoreDeletedRequest(
            Long borrowerId, long actorId, long requestId,
            boolean mustOwn, String event) throws Exception {
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String status;
                String previousStatus;
                Timestamp deletedAt;
                String selectSql = "SELECT status, status_before_delete, deleted_at "
                        + "FROM borrow_requests WHERE request_id = ?"
                        + (mustOwn ? " AND borrower_id = ?" : "") + " FOR UPDATE";
                try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
                    statement.setLong(1, requestId);
                    if (mustOwn) {
                        statement.setLong(2, borrowerId == null ? 0L : borrowerId);
                    }
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new IllegalArgumentException("Request was not found.");
                        }
                        status = result.getString("status");
                        previousStatus = result.getString("status_before_delete");
                        deletedAt = result.getTimestamp("deleted_at");
                    }
                }
                if (deletedAt == null) {
                    throw new IllegalArgumentException("This request is not deleted.");
                }
                LocalDateTime earliest = LocalDateTime.now(MANILA)
                        .minusMinutes(UndoPolicy.RECOVERY_MINUTES);
                if (deletedAt.toLocalDateTime().isBefore(earliest)) {
                    throw new IllegalArgumentException(
                            "The " + UndoPolicy.RECOVERY_LABEL + " recovery period has ended.");
                }

                String updateSql = "UPDATE borrow_requests "
                        + "SET deleted_at = NULL, deleted_by = NULL, deletion_reason = NULL, "
                        + "status_before_delete = NULL, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE request_id = ? AND deleted_at IS NOT NULL"
                        + (mustOwn ? " AND borrower_id = ?" : "");
                try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
                    statement.setLong(1, requestId);
                    if (mustOwn) {
                        statement.setLong(2, borrowerId == null ? 0L : borrowerId);
                    }
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("The request could not be restored.");
                    }
                }
                recordHistory(connection, requestId, null, actorId, event,
                        previousStatus == null ? status : previousStatus, status,
                        "Restored within the " + UndoPolicy.RECOVERY_LABEL + " recovery window.");
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    /**
     * Schedule-aware availability for this actor's pending equipment items. The smallest
     * value across occurrences is returned per equipment type so a spinner can never
     * promise more units than any one schedule can actually accept.
     */
    public Map<Long, Integer> findAssignmentAvailability(String role, long requestId)
            throws SQLException {
        String normalizedRole = requireCustodianRole(role);
        Map<Long, Integer> availability = new HashMap<>();
        try (Connection connection = Database.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT ri.requested_equipment_id,
                               COALESCE(ro.event_date, br.event_date) AS event_date,
                               COALESCE(ro.start_time, br.start_time) AS start_time,
                               COALESCE(ro.end_time, br.end_time) AS end_time
                        FROM request_groups rg
                        JOIN borrow_requests br ON br.request_id = rg.request_id
                        JOIN request_items ri ON ri.group_id = rg.group_id
                        LEFT JOIN request_occurrences ro ON ro.occurrence_id = rg.occurrence_id
                        WHERE rg.request_id = ? AND rg.custodian_role = ?
                          AND rg.status = 'PENDING' AND br.deleted_at IS NULL
                        """)) {
            statement.setLong(1, requestId);
            statement.setString(2, normalizedRole);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long equipmentId = result.getLong("requested_equipment_id");
                    Date eventDate = result.getDate("event_date");
                    Time startTime = result.getTime("start_time");
                    Time endTime = result.getTime("end_time");
                    int available = EquipmentService.findAvailableQuantity(
                            connection, equipmentId, requestId,
                            eventDate == null ? null : eventDate.toLocalDate(),
                            startTime == null ? null : startTime.toLocalTime(),
                            endTime == null ? null : endTime.toLocalTime());
                    availability.merge(equipmentId, available, Math::min);
                }
            }
        }
        return availability;
    }

    public void assignStaffRequest(String role, long actorId, long requestId)
            throws Exception {
        assignStaffRequest(role, actorId, requestId, null);
    }

    /**
     * Department approval: assigns quantities for the actor's own department only. A
     * null list assigns the full requested quantities; otherwise each entry sets the
     * quantity for one reservation group's equipment row, where 0 marks the item as
     * unable to fulfill. The other department's records are never read for permission
     * or modified.
     */
    public void assignStaffRequest(String role, long actorId, long requestId,
            List<ItemInspection> assignments) throws Exception {
        String normalizedRole = requireCustodianRole(role);
        String department = RequestStatus.departmentLabel(normalizedRole);
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String previousStatus = lockRequestStatus(connection, requestId);
                requireStaffReview(previousStatus);
                List<GroupRow> pending = processablePendingGroups(
                        lockRoleGroups(connection, requestId, normalizedRole));

                Map<AssignmentInputKey, ItemInspection> groupedInputs =
                        indexAssignmentInspections(assignments);
                Map<Long, ItemInspection> inputs = indexUngroupedInspections(assignments);
                Map<Long, Map<Long, Integer>> requestedByGroup = new LinkedHashMap<>();
                java.util.TreeSet<Long> equipmentIds = new java.util.TreeSet<>();
                for (GroupRow group : pending) {
                    Map<Long, Integer> requested = lockGroupItems(connection, group.groupId);
                    requestedByGroup.put(group.groupId, requested);
                    equipmentIds.addAll(requested.keySet());
                }
                // Lock every stock row before reading availability. Two staff members
                // assigning the same equipment cannot both promise the same units.
                lockEquipmentRows(connection, equipmentIds);
                StringBuilder partialRemarks = new StringBuilder();
                int totalAssigned = 0;
                int totalItems = 0;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE request_items
                        SET assigned_equipment_id = requested_equipment_id,
                            assigned_quantity = ?,
                            inspection_result = ?,
                            staff_remarks = ?
                        WHERE group_id = ? AND requested_equipment_id = ?
                        """)) {
                    for (GroupRow group : pending) {
                        Map<Long, Integer> requested = requestedByGroup.get(group.groupId);
                        for (Map.Entry<Long, Integer> item : requested.entrySet()) {
                            totalItems++;
                            ItemInspection input = groupedInputs.get(
                                    new AssignmentInputKey(group.groupId, item.getKey()));
                            if (input == null) {
                                input = inputs.get(item.getKey());
                            }
                            int quantity = input == null ? item.getValue() : input.getQuantity();
                            if (quantity < 0 || quantity > item.getValue()) {
                                throw new IllegalArgumentException(
                                        "Assigned quantity must be between 0 and the requested quantity.");
                            }
                            String note = input == null ? null : blankToNull(input.getRemarks());
                            if (quantity > 0 && quantity < item.getValue()
                                    && (note == null || note.isBlank())) {
                                throw new IllegalArgumentException(
                                        "Enter a staff remark explaining the partial assignment for equipment "
                                                + item.getKey() + ".");
                            }
                            int available = EquipmentService.findAvailableQuantity(
                                    connection, item.getKey(), requestId,
                                    group.eventDate, group.startTime, group.endTime);
                            if (quantity > available) {
                                throw new IllegalArgumentException(
                                        "Only " + available
                                                + " unit(s) are available for the schedule on "
                                                + group.eventDate + ".");
                            }
                            statement.setInt(1, quantity);
                            statement.setString(2, quantity == 0 ? "UNABLE_TO_FULFILL"
                                    : quantity < item.getValue() ? "PARTIAL" : null);
                            statement.setString(3, note);
                            statement.setLong(4, group.groupId);
                            statement.setLong(5, item.getKey());
                            statement.addBatch();
                            totalAssigned += quantity;
                            if (quantity > 0 && quantity < item.getValue()) {
                                if (partialRemarks.length() > 0) partialRemarks.append("; ");
                                partialRemarks.append("Equipment ").append(item.getKey())
                                        .append(": ").append(note);
                            }
                        }
                    }
                    if (totalItems == 0) {
                        throw new IllegalArgumentException(
                                "There are no equipment items to assign in this request.");
                    }
                    if (totalAssigned == 0) {
                        throw new IllegalArgumentException(
                                "No equipment can be assigned. Use \"Decline\" or \"Request changes\" instead.");
                    }
                    statement.executeBatch();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE request_groups
                        SET status = 'ASSIGNED', assigned_by = ?,
                            assigned_at = CURRENT_TIMESTAMP, staff_remarks = ?,
                            declined_by = NULL, declined_at = NULL, overdue_notified = FALSE
                        WHERE group_id IN (%s) AND status = 'PENDING'
                        """.formatted(placeholders(pending.size())))) {
                    statement.setLong(1, actorId);
                    statement.setString(2, blankToNull(partialRemarks.toString()));
                    int parameter = 3;
                    for (GroupRow group : pending) {
                        statement.setLong(parameter++, group.groupId);
                    }
                    if (statement.executeUpdate() != pending.size()) {
                        throw new IllegalStateException("Equipment assignment did not complete.");
                    }
                }

                String nextStatus = advanceAfterDepartmentDecision(
                        connection, requestId, previousStatus);
                recordHistory(connection, requestId, pending.get(0).groupId, actorId,
                        "EQUIPMENT_ASSIGNED", previousStatus, nextStatus,
                        department + " approved its equipment.");
                notifyBorrower(connection, requestId,
                        department + " equipment approved",
                        department + " staff approved the " + department
                                + " equipment in your request.",
                        normalizedRole, "DEPARTMENT_APPROVED");
                notifyRole(connection, "ADMIN", requestId,
                        department + " approved equipment",
                        department + " staff approved their assigned equipment for this request.",
                        normalizedRole, "DEPARTMENT_APPROVED");
                notifyDepartment(connection, normalizedRole, requestId, actorId,
                        department + " equipment approved",
                        "Your department approved its equipment for this request.",
                        "DEPARTMENT_APPROVED");
                notifyAfterDepartmentDecision(connection, requestId, previousStatus, nextStatus);
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    /** Serializes availability checks on the stock rows that may be assigned. */
    private void lockEquipmentRows(Connection connection, java.util.Collection<Long> equipmentIds)
            throws SQLException {
        if (equipmentIds == null || equipmentIds.isEmpty()) {
            return;
        }
        String sql = "SELECT 1 FROM equipment WHERE equipment_id IN ("
                + placeholders(equipmentIds.size()) + ") ORDER BY equipment_id FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (long equipmentId : equipmentIds) {
                statement.setLong(index++, equipmentId);
            }
            statement.executeQuery().close();
        }
    }
}
