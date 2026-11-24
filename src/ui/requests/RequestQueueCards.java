package src.ui.requests;

import src.ui.AppTheme;
import src.ui.UiSupport;
import src.controller.BorrowRequestController;
import src.model.BorrowRequest;
import src.model.DepartmentStatus;
import src.model.EquipmentRequestItem;
import src.model.RequestStatus;
import src.model.User;
import src.util.UndoPolicy;
import src.ui.components.DateFilter;
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
import java.util.Set;
import java.util.concurrent.ExecutionException;

/** Loading requests and request cards for RequestQueuePanel. */
public abstract class RequestQueueCards extends RequestQueueModes {

    void loadRequests() {
        loadRequests(false);
    }

    void loadRequests(boolean quiet) {
        if (loading) {
            return;
        }
        loading = true;
        if (!quiet) {
            showMessage("Loading requests…", false);
        }
        new SwingWorker<List<BorrowRequest>, Void>() {
            @Override
            protected List<BorrowRequest> doInBackground() throws Exception {
                return switch (mode) {
                    case STAFF_QUEUE -> requestController.findStaffQueue(actor);
                    case ADMIN_REVIEW -> requestController.findAdminQueue(actor);
                    case DIRECTOR_REVIEW -> requestController.findDirectorQueue(actor);
                    case SCHEDULE_ASSIGNMENT -> requestController.findScheduleForRole(actor);
                    case TRANSACTION_HISTORY -> isStaffActor(actor)
                            ? requestController.findHistoryForRole(actor)
                            : requestController.findAllRequests(actor);
                    case ALL_REQUESTS -> requestController.findAllRequests(actor);
                };
            }

            @Override
            protected void done() {
                loading = false;
                try {
                    requests = get();
                    showRequests();
                } catch (Exception exception) {
                    showMessage("Unable to load requests: " + messageOf(exception), true);
                }
            }
        }.execute();
    }

    void showRequests() {
        requestList.removeAll();
        dateFilter.setRequestDays(mode == Mode.SCHEDULE_ASSIGNMENT
                ? buildScheduleHighlightDates() : buildRequestDates());
        updateDailySchedule();
        String query = search.getText().trim().toLowerCase();
        List<BorrowRequest> filtered = new ArrayList<>();
        for (BorrowRequest request : requests) {
            if (mode == Mode.SCHEDULE_ASSIGNMENT
                    && RequestStatus.COMPLETED.equals(request.getStatus())) {
                continue;
            }
            boolean returnTask = mode == Mode.SCHEDULE_ASSIGNMENT && canInspectReturn(request);
            if ((returnTask || matchesDate(request))
                    && matchesPriority(request)
                    && matchesStatus(request)
                    && matchesDepartment(request)
                    && searchableText(request).contains(query)) {
                filtered.add(request);
            }
        }
        Comparator<BorrowRequest> byDate = Comparator.comparing(BorrowRequest::getSubmittedAt);
        if (dateSort.getSelectedIndex() == 0) {
            byDate = byDate.reversed();
        }
        // Work queues serve urgent requests first, then faculty; record views keep date order.
        // The daily hand-over board is ordered by reservation time, not submission time.
        if (mode == Mode.SCHEDULE_ASSIGNMENT) {
            filtered.sort(Comparator
                    .comparing((BorrowRequest request) -> request.getEventDate() == null
                            ? java.time.LocalDate.MAX : request.getEventDate())
                    .thenComparing(request -> request.getStartTime() == null
                            ? java.time.LocalTime.MAX : request.getStartTime()));
        } else {
            filtered.sort(isWorkQueue() ? BorrowRequest.URGENT_FIRST.thenComparing(byDate) : byDate);
        }

        resultCount.setText(filtered.size() + " of " + requests.size() + " shown");
        if (filtered.isEmpty()) {
            requestList.add(message(
                    requests.isEmpty()
                            ? emptyMessage()
                            : "No requests match the current search or filters.",
                    false));
        } else {
            for (BorrowRequest request : filtered) {
                requestList.add(requestCard(request));
                requestList.add(Box.createVerticalStrut(9));
            }
        }
        requestList.revalidate();
        requestList.repaint();
    }

    /** Rebuilds the selected-day table whenever the date selection or request data changes. */
    void updateDailySchedule() {
        java.time.LocalDate selected = dateFilter.getSelectedDate();
        boolean visible = mode == Mode.SCHEDULE_ASSIGNMENT && selected != null;
        dailyScheduleHost.removeAll();
        dailyScheduleHost.setVisible(visible);
        if (visible) {
            DailySchedulePanel daily = new DailySchedulePanel(
                    selected, requests, scopeRole,
                    this::openDetails, () -> dateFilter.clear());
            dailyScheduleHost.add(daily, BorderLayout.CENTER);
        }
        dailyScheduleHost.revalidate();
        dailyScheduleHost.repaint();
    }

    void openDetails(BorrowRequest request) {
        new RequestDetailsDialog(
                javax.swing.SwingUtilities.getWindowAncestor(this), request, scopeRole,
                actor, requestController, mode != Mode.TRANSACTION_HISTORY)
                .setVisible(true);
    }

    JPanel requestCard(BorrowRequest request) {
        final float[] hover = {0f};
        SurfacePanel card = new SurfacePanel() {
            @Override
            protected void paintBorder(java.awt.Graphics graphics) {
                super.paintBorder(graphics);
                if (hover[0] <= 0f) return;
                java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(AppTheme.alpha(AppTheme.primaryInk(), Math.round(255 * hover[0])));
                g.setStroke(new java.awt.BasicStroke(2f));
                int m = SurfacePanel.MARGIN;
                g.drawRoundRect(m, m, getWidth() - m * 2 - 1, getHeight() - m * 2 - 1, 18, 18);
                g.dispose();
            }
        };
        javax.swing.Timer hoverTimer = new javax.swing.Timer(15, null);
        hoverTimer.addActionListener(event -> {
            boolean inside = card.getMousePosition() != null;
            float target = inside ? 1f : 0f, step = 15f / 200f;
            if (Math.abs(hover[0] - target) <= step) { hover[0] = target; hoverTimer.stop(); }
            else { hover[0] += hover[0] < target ? step : -step; }
            card.repaint();
        });
        card.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) { if (!hoverTimer.isRunning()) hoverTimer.start(); }
            @Override public void mouseExited(java.awt.event.MouseEvent e) { if (!hoverTimer.isRunning()) hoverTimer.start(); }
        });
        card.setLayout(new BorderLayout(12, 8));
        JPanel details = new JPanel();
        details.setOpaque(false);
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));

        JPanel title = new JPanel(new BorderLayout(8, 0));
        title.setOpaque(false);
        JLabel code = new JLabel(request.getRequestCode() + "  ·  "
                + request.getBorrowerName()
                + (request.isUrgent() ? "  ·  URGENT" : ""));
        code.setFont(AppTheme.semibold(13));
        code.setForeground(AppTheme.foreground());
        title.add(code, BorderLayout.CENTER);
        title.add(UiSupport.statusBadges(request, scopeRole, scopeRole == null),
                BorderLayout.EAST);

        String equipment = equipmentSummary(request);
        int schedules = request.getOccurrences().size();
        JLabel meta = new JLabel(request.getVenue() + "  ·  "
                + request.getEventDate().format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                + "  ·  " + UiSupport.timeRange(request.getStartTime(), request.getEndTime())
                + (schedules > 1 ? "  ·  " + schedules + " schedules" : ""));
        meta.setFont(AppTheme.regular(10));
        meta.setForeground(AppTheme.muted());
        JLabel itemLine = new JLabel(equipment);
        itemLine.setFont(AppTheme.regular(10));
        itemLine.setForeground(AppTheme.muted());
        details.add(title);
        details.add(Box.createVerticalStrut(5));
        details.add(meta);
        details.add(Box.createVerticalStrut(4));
        details.add(itemLine);
        if (mode == Mode.TRANSACTION_HISTORY) {
            JLabel approval = new JLabel(
                    "Approval progress: " + RequestStatus.approvalProgress(request, scopeRole));
            approval.setFont(AppTheme.regular(10));
            approval.setForeground(AppTheme.muted());
            details.add(Box.createVerticalStrut(4));
            details.add(approval);
        }
        if (mode == Mode.SCHEDULE_ASSIGNMENT || mode == Mode.TRANSACTION_HISTORY) {
            String handover = handoverLine(request);
            if (!handover.isEmpty()) {
                JLabel actionsLine = new JLabel(handover);
                actionsLine.setFont(AppTheme.regular(10));
                actionsLine.setForeground(AppTheme.muted());
                details.add(Box.createVerticalStrut(4));
                details.add(actionsLine);
            }
        }
        String remarks = departmentRemarks(request);
        if (!remarks.isEmpty()) {
            JLabel remarksLine = new JLabel("<html>" + remarks + "</html>");
            remarksLine.setFont(AppTheme.regular(10));
            remarksLine.setForeground(AppTheme.muted());
            details.add(Box.createVerticalStrut(4));
            details.add(remarksLine);
        }
        card.add(details, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
        actions.setOpaque(false);
        PrimaryButton view = new PrimaryButton("View details", PrimaryButton.Style.SECONDARY);
        view.addActionListener(event -> openDetails(request));
        actions.add(view);
        addModeActions(actions, request);
        card.add(actions, BorderLayout.SOUTH);
        return card;
    }

    void addModeActions(JPanel actions, BorrowRequest request) {
        if (mode == Mode.STAFF_QUEUE) {
            if (hasOwnGroup(request, "PENDING")) {
                PrimaryButton approve = new PrimaryButton("Accept");
                approve.setToolTipText("Accept and assign your department's equipment");
                approve.addActionListener(event -> assignRequest(request, approve));
                PrimaryButton decline = new PrimaryButton("Decline", PrimaryButton.Style.DANGER);
                decline.setToolTipText("Decline only your department's equipment");
                decline.addActionListener(event -> declineRequest(request, decline));
                // Requirement III.3: remove "Request Changes" from ICT System Admin Request section
                actions.add(approve);
                actions.add(decline);
            }
        } else if (mode == Mode.ADMIN_REVIEW || mode == Mode.DIRECTOR_REVIEW) {
            PrimaryButton approve = new PrimaryButton("Approve");
            approve.addActionListener(event -> reviewRequest(request, true, approve));
            PrimaryButton reject = new PrimaryButton("Decline", PrimaryButton.Style.DANGER);
            reject.addActionListener(event -> reviewRequest(request, false, reject));
            actions.add(approve);
            actions.add(reject);
        } else if (mode == Mode.SCHEDULE_ASSIGNMENT || mode == Mode.TRANSACTION_HISTORY) {
            if (mode == Mode.SCHEDULE_ASSIGNMENT
                    && RequestStatus.isFinalApprovalGranted(request)
                    && hasOwnGroup(request, "PENDING")) {
                PrimaryButton assign = new PrimaryButton("Accept");
                assign.setToolTipText(
                        "Assign your department's equipment for this approved reservation");
                assign.addActionListener(event -> assignRequest(request, assign));
                actions.add(assign);
            }
            DepartmentStatus releaseGroup = releaseGroupForToday(request);
            if (canRelease(request) && releaseGroup != null) {
                PrimaryButton release = new PrimaryButton(
                        RequestStatus.departmentLabel(actor.getRole()) + " release");
                release.setToolTipText("Release is available only on the reservation date ("
                        + releaseGroup.getScheduleStart().toLocalDate() + ").");
                release.addActionListener(event ->
                        updateTransaction(request, true, releaseGroup.getGroupId(), release));
                actions.add(release);
            } else if (canReleaseOnAnyDate(request)) {
                PrimaryButton release = new PrimaryButton(
                        RequestStatus.departmentLabel(actor.getRole()) + " release");
                release.setEnabled(false);
                release.setToolTipText("Release is allowed only on the reservation date ("
                        + assignedReservationDates(request) + "); today is "
                        + DateFilter.today() + ".");
                actions.add(release);
            }
            if (canInspectReturn(request)) {
                PrimaryButton returned = new PrimaryButton(
                        "Inspect return", PrimaryButton.Style.SECONDARY);
                returned.setToolTipText(
                        "Available only after the borrower confirms receipt");
                returned.addActionListener(event -> updateTransaction(request, false, returned));
                actions.add(returned);
            }
        }
        // Requirement III.3: keep Delete only in Transaction History (and allowed contexts)
        if (isIctAdmin() && !"CANCELLED".equals(request.getStatus())
                && mode == Mode.TRANSACTION_HISTORY) {
            PrimaryButton delete = new PrimaryButton("Delete", PrimaryButton.Style.DANGER);
            delete.setToolTipText("Delete this request (ICT System Admin only)");
            delete.addActionListener(event -> deleteAsAdmin(request, delete));
            actions.add(delete);
        }
    }

    // Implemented in RequestQueueActions.
    abstract void deleteAsAdmin(BorrowRequest request, javax.swing.AbstractButton source);

    abstract void declineRequest(BorrowRequest request, javax.swing.AbstractButton source);

    abstract void assignRequest(BorrowRequest request, javax.swing.AbstractButton source);

    abstract void reviewRequest(
            BorrowRequest request, boolean approve, javax.swing.AbstractButton source);

    void updateTransaction(
            BorrowRequest request, boolean release, javax.swing.AbstractButton source) {
        updateTransaction(request, release, null, source);
    }

    abstract void updateTransaction(
            BorrowRequest request, boolean release, Long groupId,
            javax.swing.AbstractButton source);
}
