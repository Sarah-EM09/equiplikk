package src.ui.requests;

import src.ui.UiSupport;
import src.controller.BorrowRequestController;
import src.model.BorrowRequest;
import src.model.DepartmentStatus;
import src.model.EquipmentRequestItem;
import src.model.RequestStatus;
import src.model.User;
import src.util.UndoPolicy;
import src.ui.components.PrimaryButton;
import src.ui.components.SurfacePanel;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/** Assign, decline, review, release and return actions for RequestQueuePanel. */
public abstract class RequestQueueActions extends RequestQueueCards {

    void deleteAsAdmin(BorrowRequest request, javax.swing.AbstractButton source) {
        if (!UiSupport.beginAction(source)) {
            return;
        }
        long requestId = request.getRequestId();
        String label = requestLabel(request);
        int answer = UiSupport.showConfirmDialog(this,
                "Delete request " + label + "?\n"
                        + "It will be removed from all request lists and kept for audit. "
                        + "You can undo for " + UndoPolicy.RECOVERY_LABEL + ".",
                "Delete request", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            UiSupport.endAction(source);
            return;
        }
        String reason = UiSupport.showInputDialog(this,
                "Reason for deleting request " + label + ":",
                "Delete request", JOptionPane.QUESTION_MESSAGE);
        if (reason == null || reason.isBlank()) {
            UiSupport.endAction(source);
            return;
        }
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                requestController.deleteRequestAsAdmin(actor, requestId, reason);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    loadRequests();
                    offerAdminUndo(label, requestId);
                } catch (Exception exception) {
                    UiSupport.showMessageDialog(RequestQueueActions.this,
                            messageOf(exception), "Unable to delete request",
                            JOptionPane.ERROR_MESSAGE);
                } finally {
                    UiSupport.endAction(source);
                }
            }
        }.execute();
    }

    void offerAdminUndo(String label, long requestId) {
        int choice = UiSupport.showUndoDialog(this,
                label + " was deleted.\nUndo is available for " + UndoPolicy.RECOVERY_LABEL + ".",
                "Request deleted");
        if (choice != 0) {
            return;
        }
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                requestController.restoreRequestAsAdmin(actor, requestId);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    loadRequests();
                    UiSupport.showMessageDialog(RequestQueueActions.this,
                            label + " was restored with its original status.",
                            "Request restored", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception exception) {
                    UiSupport.showMessageDialog(RequestQueueActions.this,
                            messageOf(exception), "Unable to restore request",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    void declineRequest(BorrowRequest request, javax.swing.AbstractButton source) {
        if (!UiSupport.beginAction(source)) {
            return;
        }
        String remarks = UiSupport.showInputDialog(
                this, "Reason for declining your department's equipment:",
                "Decline " + RequestStatus.departmentLabel(actor.getRole()) + " equipment",
                JOptionPane.WARNING_MESSAGE);
        if (remarks == null) {
            UiSupport.endAction(source);
            return;
        }
        if (remarks.isBlank()) {
            UiSupport.endAction(source);
            UiSupport.showMessageDialog(
                    this, "Enter a reason for declining.",
                    "Reason required", JOptionPane.WARNING_MESSAGE);
            return;
        }
        runAction("Unable to decline request", () ->
                requestController.declineStaffRequest(actor, request.getRequestId(), remarks),
                source);
    }

    void assignRequest(BorrowRequest request, javax.swing.AbstractButton source) {
        if (!UiSupport.beginAction(source)) {
            return;
        }
        new SwingWorker<Map<Long, Integer>, Void>() {
            @Override
            protected Map<Long, Integer> doInBackground() throws Exception {
                return requestController.availableAssignmentQuantities(
                        actor, request.getRequestId());
            }

            @Override
            protected void done() {
                try {
                    Map<Long, Integer> availability = get();
                    List<src.model.ItemInspection> assignments = ItemInspectionDialog.show(
                            RequestQueueActions.this, request, actor.getRole(),
                            ItemInspectionDialog.Kind.ASSIGN, availability);
                    if (assignments == null) {
                        UiSupport.endAction(source);
                        return;
                    }
                    runAction("Unable to assign equipment", () ->
                            requestController.assignStaffRequest(
                                    actor, request.getRequestId(), assignments), source);
                } catch (Exception exception) {
                    UiSupport.endAction(source);
                    UiSupport.showMessageDialog(RequestQueueActions.this,
                            messageOf(exception), "Unable to check availability",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    void reviewRequest(
            BorrowRequest request, boolean approve, javax.swing.AbstractButton source) {
        if (!UiSupport.beginAction(source)) {
            return;
        }
        final String remarks;
        if (approve) {
            remarks = null;
        } else {
            remarks = UiSupport.showInputDialog(
                    this, "Reason for declining this request:",
                    "Decline request", JOptionPane.QUESTION_MESSAGE);
            if (remarks == null) {
                UiSupport.endAction(source);
                return;
            }
            if (remarks.isBlank()) {
                UiSupport.endAction(source);
                UiSupport.showMessageDialog(
                        this, "Enter a reason before declining.",
                        "Reason required", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }
        runAction("Unable to review request", () -> {
            if (mode == Mode.ADMIN_REVIEW) {
                requestController.reviewByAdmin(
                        actor, request.getRequestId(), approve, remarks);
            } else {
                requestController.reviewByDirector(
                        actor, request.getRequestId(), approve, remarks);
            }
        }, source);
    }

    void updateTransaction(
            BorrowRequest request, boolean release, Long groupId,
            javax.swing.AbstractButton source) {
        if (!UiSupport.beginAction(source)) {
            return;
        }
        if (!release) {
            openReturnDialog(request, source);
            return;
        }
        // Re-read the request before release: a queue row can outlive its reservation date.
        new SwingWorker<BorrowRequest, Void>() {
            @Override
            protected BorrowRequest doInBackground() throws Exception {
                return requestController.findRequestForUser(actor, request.getRequestId());
            }

            @Override
            protected void done() {
                try {
                    BorrowRequest current = get();
                    DepartmentStatus releaseGroup = releaseGroupForToday(current);
                    if (releaseGroup == null) {
                        UiSupport.showMessageDialog(
                                RequestQueueActions.this,
                                "This equipment can no longer be released today. "
                                        + "The request list has been refreshed.",
                                "Release unavailable", JOptionPane.INFORMATION_MESSAGE);
                        loadRequests();
                        UiSupport.endAction(source);
                        return;
                    }
                    openReleaseDialog(current, releaseGroup.getGroupId(), source);
                } catch (Exception exception) {
                    UiSupport.showMessageDialog(
                            RequestQueueActions.this, messageOf(exception),
                            "Unable to release equipment", JOptionPane.ERROR_MESSAGE);
                    UiSupport.endAction(source);
                }
            }
        }.execute();
    }

    private void openReleaseDialog(
            BorrowRequest request, Long groupId, javax.swing.AbstractButton source) {
        List<src.model.ItemInspection> inspections = ItemInspectionDialog.show(
                this, request, actor.getRole(),
                ItemInspectionDialog.Kind.RELEASE, groupId);
        if (inspections == null) {
            UiSupport.endAction(source);
            return;
        }
        runAction(
                "Unable to release equipment",
                () -> requestController.releaseStaffAssignment(
                        actor, request.getRequestId(), groupId, inspections),
                source);
    }

    private void openReturnDialog(
            BorrowRequest request, javax.swing.AbstractButton source) {
        List<src.model.ItemInspection> inspections = ItemInspectionDialog.show(
                this, request, actor.getRole(), ItemInspectionDialog.Kind.RETURN);
        if (inspections == null) {
            UiSupport.endAction(source);
            return;
        }
        runAction(
                "Unable to record return",
                () -> requestController.recordStaffReturn(
                        actor, request.getRequestId(), inspections),
                source);
    }

    void runAction(
            String errorTitle, ThrowingAction action, javax.swing.AbstractButton source) {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                action.run();
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    loadRequests();
                } catch (Exception exception) {
                    UiSupport.showMessageDialog(
                            RequestQueueActions.this, messageOf(exception),
                            errorTitle, JOptionPane.ERROR_MESSAGE);
                } finally {
                    UiSupport.endAction(source);
                }
            }
        }.execute();
    }
}
