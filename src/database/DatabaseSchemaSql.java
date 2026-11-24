package src.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** SQL statements used by DatabaseInitializer. */
public abstract class DatabaseSchemaSql {

    static final List<String> SQL_STATEMENTS = java.util.stream.Stream.concat(
            DatabaseSchema.FIRST_STATEMENTS.stream(),
            List.<String>of(

            // 6. NOTIFICATIONS
            """
                    CREATE TABLE IF NOT EXISTS notifications (
                        notification_id BIGSERIAL PRIMARY KEY,
                        user_id BIGINT NOT NULL
                            REFERENCES users(user_id) ON DELETE CASCADE,

                        request_id BIGINT
                            REFERENCES borrow_requests(request_id)
                            ON DELETE CASCADE,

                        title VARCHAR(150) NOT NULL,
                        message TEXT NOT NULL,
                        is_read BOOLEAN NOT NULL DEFAULT FALSE,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """,

            // 7. REQUEST HISTORY
            """
                    CREATE TABLE IF NOT EXISTS request_history (
                        history_id BIGSERIAL PRIMARY KEY,

                        request_id BIGINT NOT NULL
                            REFERENCES borrow_requests(request_id)
                            ON DELETE CASCADE,

                        group_id BIGINT
                            REFERENCES request_groups(group_id)
                            ON DELETE CASCADE,

                        actor_id BIGINT REFERENCES users(user_id),
                        event_type VARCHAR(60) NOT NULL,
                        from_status VARCHAR(30),
                        to_status VARCHAR(30),
                        remarks TEXT,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """,

            // MIGRATIONS (run after every table exists)
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login TIMESTAMP",
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_picture BYTEA",
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP",

            /*
             * Username uniqueness mirrors the login rule: values are trimmed and
             * compared case-insensitively across every role. The advisory lock keeps
             * concurrent profile changes from both passing the check. A trigger is
             * used instead of a plain functional unique index so legacy databases
             * with existing case-only duplicates can still start while all future
             * writes are guarded.
             */
            """
                    CREATE OR REPLACE FUNCTION equiplink_enforce_username_unique()
                    RETURNS trigger AS $$
                    DECLARE
                        candidate TEXT;
                    BEGIN
                        candidate := LOWER(BTRIM(NEW.username));
                        NEW.username := BTRIM(NEW.username);
                        IF TG_OP = 'INSERT' THEN
                            PERFORM pg_advisory_xact_lock(hashtextextended(candidate, 0));
                            IF EXISTS (
                                SELECT 1 FROM users u
                                WHERE LOWER(BTRIM(u.username)) = candidate
                                  AND u.user_id <> NEW.user_id
                            ) THEN
                                RAISE EXCEPTION 'username already exists'
                                    USING ERRCODE = '23505';
                            END IF;
                        ELSIF NEW.username IS DISTINCT FROM OLD.username THEN
                            PERFORM pg_advisory_xact_lock(hashtextextended(candidate, 0));
                            IF EXISTS (
                                SELECT 1 FROM users u
                                WHERE LOWER(BTRIM(u.username)) = candidate
                                  AND u.user_id <> NEW.user_id
                            ) THEN
                                RAISE EXCEPTION 'username already exists'
                                    USING ERRCODE = '23505';
                            END IF;
                        END IF;
                        RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql
                    """,
            "DROP TRIGGER IF EXISTS users_username_ci_unique ON users",
            """
                    CREATE TRIGGER users_username_ci_unique
                    BEFORE INSERT OR UPDATE OF username ON users
                    FOR EACH ROW EXECUTE FUNCTION equiplink_enforce_username_unique()
                    """,
            "ALTER TABLE request_groups ADD COLUMN IF NOT EXISTS occurrence_id BIGINT REFERENCES request_occurrences(occurrence_id) ON DELETE CASCADE",
            "ALTER TABLE request_items ADD COLUMN IF NOT EXISTS occurrence_id BIGINT REFERENCES request_occurrences(occurrence_id) ON DELETE CASCADE",
            "ALTER TABLE request_items ADD COLUMN IF NOT EXISTS substitute_equipment_id BIGINT REFERENCES equipment(equipment_id)",
            "ALTER TABLE request_items ADD COLUMN IF NOT EXISTS unavailable_remarks TEXT",
            "ALTER TABLE notifications ADD COLUMN IF NOT EXISTS occurrence_id BIGINT REFERENCES request_occurrences(occurrence_id) ON DELETE CASCADE",
            "ALTER TABLE request_groups DROP CONSTRAINT IF EXISTS request_groups_request_id_custodian_role_key",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_request_groups_occ_role ON request_groups(occurrence_id, custodian_role) WHERE occurrence_id IS NOT NULL",
            "CREATE INDEX IF NOT EXISTS idx_occurrences_request ON request_occurrences(request_id, event_date, start_time)",

            /*
             * LEGACY CLEANUP: the earlier work-in-progress schema tracked schedules in
             * request_schedules and required request_items.schedule_id. The current
             * code uses request_occurrences instead, so carry the old schedules over
             * and then remove the obsolete column. This block is idempotent and is a
             * no-op on databases created with the current schema.
             */
            """
                    DO $$
                    DECLARE
                        legacy_schedules BOOLEAN;
                        legacy_column BOOLEAN;
                    BEGIN
                        SELECT EXISTS (
                            SELECT 1 FROM information_schema.tables
                            WHERE table_schema = 'public'
                              AND table_name = 'request_schedules'
                        ) INTO legacy_schedules;

                        SELECT EXISTS (
                            SELECT 1 FROM information_schema.columns
                            WHERE table_schema = 'public'
                              AND table_name = 'request_items'
                              AND column_name = 'schedule_id'
                        ) INTO legacy_column;

                        IF legacy_schedules THEN
                            INSERT INTO request_occurrences (
                                request_id, event_date, start_time, end_time, venue
                            )
                            SELECT rs.request_id, rs.event_date, rs.start_time,
                                   rs.end_time, br.venue
                            FROM request_schedules rs
                            JOIN borrow_requests br ON br.request_id = rs.request_id
                            WHERE NOT EXISTS (
                                SELECT 1 FROM request_occurrences ro
                                WHERE ro.request_id = rs.request_id
                                  AND ro.event_date = rs.event_date
                                  AND ro.start_time = rs.start_time
                                  AND ro.end_time = rs.end_time
                            );

                            IF legacy_column THEN
                                UPDATE request_items ri
                                SET occurrence_id = ro.occurrence_id
                                FROM request_schedules rs
                                JOIN request_occurrences ro
                                  ON ro.request_id = rs.request_id
                                 AND ro.event_date = rs.event_date
                                 AND ro.start_time = rs.start_time
                                 AND ro.end_time = rs.end_time
                                WHERE ri.schedule_id = rs.schedule_id
                                  AND ri.occurrence_id IS NULL;

                                UPDATE request_groups rg
                                SET occurrence_id = ro.occurrence_id
                                FROM request_occurrences ro
                                WHERE rg.request_id = ro.request_id
                                  AND rg.occurrence_id IS NULL
                                  AND (
                                      SELECT COUNT(*) FROM request_occurrences x
                                      WHERE x.request_id = rg.request_id
                                  ) = 1
                                  AND NOT EXISTS (
                                      SELECT 1 FROM request_groups other
                                      WHERE other.occurrence_id = ro.occurrence_id
                                        AND other.custodian_role = rg.custodian_role
                                        AND other.group_id <> rg.group_id
                                  );
                            END IF;
                        END IF;

                        IF legacy_column THEN
                            ALTER TABLE request_items
                                DROP COLUMN IF EXISTS schedule_id;
                        END IF;
                    END $$;
                    """,


            // Independent department (ICT / Facility) decisions
            "ALTER TABLE request_groups ADD COLUMN IF NOT EXISTS declined_by BIGINT REFERENCES users(user_id)",
            "ALTER TABLE request_groups ADD COLUMN IF NOT EXISTS declined_at TIMESTAMP",
            "ALTER TABLE request_groups ADD COLUMN IF NOT EXISTS received_by BIGINT REFERENCES users(user_id)",
            "ALTER TABLE request_groups ADD COLUMN IF NOT EXISTS received_at TIMESTAMP",
            "ALTER TABLE request_groups ADD COLUMN IF NOT EXISTS overdue_notified BOOLEAN NOT NULL DEFAULT FALSE",
            "ALTER TABLE request_groups ADD COLUMN IF NOT EXISTS return_notified BOOLEAN NOT NULL DEFAULT FALSE",
            "ALTER TABLE request_groups DROP CONSTRAINT IF EXISTS request_groups_status_check",
            """
                    ALTER TABLE request_groups ADD CONSTRAINT request_groups_status_check
                    CHECK (status IN (
                        'PENDING', 'ASSIGNED', 'UNABLE', 'RELEASED', 'RECEIVED',
                        'RETURNED', 'DECLINED', 'CANCELLED', 'NOT_REQUIRED'
                    ))
                    """,
            "ALTER TABLE borrow_requests ADD COLUMN IF NOT EXISTS overdue_notified BOOLEAN NOT NULL DEFAULT FALSE",

            // Borrower-supplied remarks, urgency, and the actual final-approval date.
            // These are separate from borrow_requests.remarks, which stores reviewer notes.
            "ALTER TABLE borrow_requests ADD COLUMN IF NOT EXISTS borrower_remarks TEXT",
            "ALTER TABLE borrow_requests ADD COLUMN IF NOT EXISTS urgent BOOLEAN NOT NULL DEFAULT FALSE",
            "ALTER TABLE borrow_requests ADD COLUMN IF NOT EXISTS urgent_reason TEXT",
            "ALTER TABLE borrow_requests ADD COLUMN IF NOT EXISTS final_approved_at TIMESTAMP",
            "CREATE INDEX IF NOT EXISTS idx_requests_final_approved ON borrow_requests(final_approved_at)",
            """
                    UPDATE borrow_requests br
                    SET final_approved_at = history.first_approval
                    FROM (
                        SELECT request_id, MIN(created_at) AS first_approval
                        FROM request_history
                        WHERE event_type = 'DIRECTOR_APPROVED'
                        GROUP BY request_id
                    ) history
                    WHERE br.request_id = history.request_id
                      AND br.final_approved_at IS NULL
                    """,

            // Per-user notification sound preference. Sounds are built into the app,
            // so only the selection name needs to be stored.
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS notification_sound_enabled BOOLEAN NOT NULL DEFAULT TRUE",
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS notification_sound VARCHAR(30) NOT NULL DEFAULT 'chime'",

            // Notification details, per-user read and delete state
            "ALTER TABLE notifications ADD COLUMN IF NOT EXISTS department VARCHAR(20)",
            "ALTER TABLE notifications ADD COLUMN IF NOT EXISTS action VARCHAR(40)",
            "ALTER TABLE notifications ADD COLUMN IF NOT EXISTS read_at TIMESTAMP",
            "ALTER TABLE notifications ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE",
            "ALTER TABLE notifications ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP",
            "ALTER TABLE notifications ADD COLUMN IF NOT EXISTS read_before_delete BOOLEAN",
            "CREATE INDEX IF NOT EXISTS idx_notifications_user_visible ON notifications(user_id, is_deleted, created_at)",

            // Recoverable request deletion: rows stay in the database until the
            // undo window expires, and the original stage is kept for restores.
            "ALTER TABLE borrow_requests ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP",
            "ALTER TABLE borrow_requests ADD COLUMN IF NOT EXISTS deleted_by BIGINT REFERENCES users(user_id)",
            "ALTER TABLE borrow_requests ADD COLUMN IF NOT EXISTS deletion_reason TEXT",
            "ALTER TABLE borrow_requests ADD COLUMN IF NOT EXISTS status_before_delete VARCHAR(30)",
            "CREATE INDEX IF NOT EXISTS idx_requests_deleted_at ON borrow_requests(deleted_at)",

            // Password recovery: registered email plus elevated reviewer verification
            "ALTER TABLE password_reset_requests ADD COLUMN IF NOT EXISTS registered_email_match BOOLEAN NOT NULL DEFAULT FALSE",
            "ALTER TABLE password_reset_requests ADD COLUMN IF NOT EXISTS identity_verified BOOLEAN NOT NULL DEFAULT FALSE",
            "CREATE INDEX IF NOT EXISTS idx_history_latest ON request_history(history_id)",

            // INDEXES
            """
                    CREATE INDEX IF NOT EXISTS idx_requests_borrower
                    ON borrow_requests(borrower_id)
                    """,

            """
                    CREATE INDEX IF NOT EXISTS idx_requests_status
                    ON borrow_requests(status)
                    """,

            """
                    CREATE INDEX IF NOT EXISTS idx_groups_status_role
                    ON request_groups(custodian_role, status)
                    """,

            """
                    CREATE INDEX IF NOT EXISTS idx_notifications_user_read
                    ON notifications(user_id, is_read)
                    """,

            """
                    CREATE INDEX IF NOT EXISTS idx_history_request
                    ON request_history(request_id, created_at)
                    """
            ).stream()).toList();
}
