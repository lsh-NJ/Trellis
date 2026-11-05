package dev.trellis.feature.learning

/**
 * 契约级 DDL（对应 `design/plan_schema.sql`，v1）。
 *
 * 相对设计稿只做了「向后兼容的加列」，语义未变：
 * - `occurrence` 的 `commitment_id` / `unit_id` 改为可空，并新增 `todo_id` ——
 *   `scheduleTodo` 需要「只有 todo、没有 unit」的行；否则这一档功能无法落库。
 * - `event` 新增 `plan_id` —— 乐观并发要按「计划内最后事件 seq」比较，聚合根之外的事件
 *   （occurrence/session）也要能归到计划。这是加列，旧数据 `plan_id` 为 NULL。
 *
 * 执行方式是一条条执行（SQLite JDBC 不支持单次多语句），触发器里的 `;` 因此不会被误切。
 */
internal val PLAN_SCHEMA_V1: List<String> = listOf(
    "CREATE TABLE IF NOT EXISTS schema_migration (" +
        "version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS material (" +
        "id TEXT PRIMARY KEY, source TEXT NOT NULL, title TEXT NOT NULL, url TEXT NULL, " +
        "license TEXT NULL, fetched_at TEXT NULL, created_at TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS material_version (" +
        "id TEXT PRIMARY KEY, material_id TEXT NOT NULL REFERENCES material(id), " +
        "version_no INTEGER NOT NULL CHECK (version_no >= 1), raw_hash TEXT NOT NULL, " +
        "created_at TEXT NOT NULL, UNIQUE (material_id, version_no))",
    "CREATE TABLE IF NOT EXISTS segment (" +
        "id TEXT PRIMARY KEY, material_version_id TEXT NOT NULL REFERENCES material_version(id), " +
        "locator TEXT NOT NULL, excerpt_hash TEXT NOT NULL, heading TEXT NULL, created_at TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS process (" +
        "id TEXT PRIMARY KEY, title TEXT NOT NULL, goal TEXT NOT NULL, deadline TEXT NULL, " +
        "weekly_capacity_minutes INTEGER NULL CHECK (weekly_capacity_minutes IS NULL OR " +
        "weekly_capacity_minutes BETWEEN 30 AND 3000), " +
        "status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','paused','ended')), " +
        "created_at TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS plan (" +
        "id TEXT PRIMARY KEY, process_id TEXT NOT NULL REFERENCES process(id), " +
        "status TEXT NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','active','paused','ended')), " +
        "current_version_id TEXT NULL, created_at TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS plan_version (" +
        "id TEXT PRIMARY KEY, plan_id TEXT NOT NULL REFERENCES plan(id), " +
        "parent_id TEXT NULL REFERENCES plan_version(id), " +
        "reason TEXT NOT NULL CHECK (length(reason) >= 1), " +
        "diff_json TEXT NULL CHECK (diff_json IS NULL OR json_valid(diff_json)), " +
        "effective_from_week TEXT NOT NULL CHECK (effective_from_week GLOB '[0-9][0-9][0-9][0-9]-W[0-9][0-9]'), " +
        "status TEXT NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','active','archived')), " +
        "created_at TEXT NOT NULL)",
    "CREATE INDEX IF NOT EXISTS idx_plan_version_plan ON plan_version(plan_id, created_at)",
    "CREATE TABLE IF NOT EXISTS plan_unit (" +
        "id TEXT PRIMARY KEY, plan_version_id TEXT NOT NULL REFERENCES plan_version(id), " +
        "chapter TEXT NOT NULL, title TEXT NOT NULL, sort_order INTEGER NOT NULL, " +
        "estimate_minutes INTEGER NOT NULL DEFAULT 25 CHECK (estimate_minutes BETWEEN 10 AND 45), " +
        "min_version_minutes INTEGER NOT NULL DEFAULT 10 CHECK (min_version_minutes BETWEEN 5 AND 15), " +
        "material_segment_id TEXT NULL REFERENCES segment(id), created_at TEXT NOT NULL, " +
        "UNIQUE (plan_version_id, sort_order))",
    "CREATE TABLE IF NOT EXISTS commitment (" +
        "id TEXT PRIMARY KEY, plan_version_id TEXT NOT NULL REFERENCES plan_version(id), " +
        "week TEXT NOT NULL CHECK (week GLOB '[0-9][0-9][0-9][0-9]-W[0-9][0-9]'), " +
        "created_at TEXT NOT NULL, UNIQUE (plan_version_id, week))",
    "CREATE TABLE IF NOT EXISTS commitment_unit (" +
        "commitment_id TEXT NOT NULL REFERENCES commitment(id), " +
        "unit_id TEXT NOT NULL REFERENCES plan_unit(id), sort_order INTEGER NOT NULL, " +
        "PRIMARY KEY (commitment_id, unit_id))",
    "CREATE TABLE IF NOT EXISTS occurrence (" +
        "id TEXT PRIMARY KEY, " +
        "commitment_id TEXT NULL REFERENCES commitment(id), " +
        "unit_id TEXT NULL REFERENCES plan_unit(id), " +
        "todo_id TEXT NULL REFERENCES todo(id), " +
        "day TEXT NOT NULL CHECK (day GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'), " +
        "estimate_minutes INTEGER NOT NULL CHECK (estimate_minutes BETWEEN 5 AND 60), " +
        "mode TEXT NOT NULL DEFAULT 'full' CHECK (mode IN ('full','minimal')), " +
        "status TEXT NOT NULL DEFAULT 'planned' CHECK (status IN ('planned','done','missed','skipped','superseded')), " +
        "note TEXT NULL CHECK (note IS NULL OR length(note) <= 200), " +
        "superseded_by TEXT NULL REFERENCES occurrence(id), " +
        "moved_from TEXT NULL REFERENCES occurrence(id), " +
        "carried_from TEXT NULL REFERENCES occurrence(id), " +
        "created_at TEXT NOT NULL, completed_at TEXT NULL, " +
        "CHECK ((status = 'superseded') = (superseded_by IS NOT NULL)), " +
        "CHECK ((status = 'done') = (completed_at IS NOT NULL)), " +
        "CHECK (unit_id IS NOT NULL OR todo_id IS NOT NULL))",
    "CREATE INDEX IF NOT EXISTS idx_occurrence_day ON occurrence(day, status)",
    "CREATE INDEX IF NOT EXISTS idx_occurrence_commitment ON occurrence(commitment_id)",
    "CREATE INDEX IF NOT EXISTS idx_occurrence_unit ON occurrence(unit_id)",
    "CREATE TABLE IF NOT EXISTS session (" +
        "id TEXT PRIMARY KEY, occurrence_id TEXT NOT NULL REFERENCES occurrence(id), " +
        "started_at TEXT NOT NULL, ended_at TEXT NULL, " +
        "source TEXT NOT NULL DEFAULT 'manual' CHECK (source IN ('manual','timer','activity')), " +
        "created_at TEXT NOT NULL, CHECK (ended_at IS NULL OR ended_at >= started_at))",
    "CREATE UNIQUE INDEX IF NOT EXISTS ux_session_open ON session(occurrence_id) WHERE ended_at IS NULL",
    "CREATE TABLE IF NOT EXISTS todo (" +
        "id TEXT PRIMARY KEY, title TEXT NOT NULL, " +
        "status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open','scheduled','done','closed')), " +
        "estimate_minutes INTEGER NULL CHECK (estimate_minutes IS NULL OR estimate_minutes BETWEEN 5 AND 120), " +
        "created_at TEXT NOT NULL, completed_at TEXT NULL, " +
        "CHECK ((status = 'done') = (completed_at IS NOT NULL)))",
    "CREATE INDEX IF NOT EXISTS idx_todo_status ON todo(status)",
    "CREATE TABLE IF NOT EXISTS todo_occurrence (" +
        "todo_id TEXT NOT NULL REFERENCES todo(id), " +
        "occurrence_id TEXT NOT NULL REFERENCES occurrence(id), " +
        "relation TEXT NOT NULL DEFAULT 'scheduled_from' CHECK (relation IN ('scheduled_from','derived')), " +
        "PRIMARY KEY (todo_id, occurrence_id))",
    "CREATE TABLE IF NOT EXISTS event (" +
        "seq INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT NOT NULL UNIQUE, " +
        "type TEXT NOT NULL, entity TEXT NOT NULL, entity_id TEXT NULL, " +
        "payload_json TEXT NOT NULL CHECK (json_valid(payload_json)), " +
        "occurred_at TEXT NOT NULL, recorded_at TEXT NOT NULL, " +
        "actor TEXT NOT NULL CHECK (actor IN ('user','agent','system')), " +
        "command_id TEXT NULL, undo_of TEXT NULL, plan_id TEXT NULL)",
    "CREATE INDEX IF NOT EXISTS idx_event_entity ON event(entity, entity_id, seq)",
    "CREATE INDEX IF NOT EXISTS idx_event_time ON event(occurred_at)",
    "CREATE INDEX IF NOT EXISTS idx_event_command ON event(command_id)",
    "CREATE INDEX IF NOT EXISTS idx_event_plan ON event(plan_id, seq)",
    "CREATE TRIGGER IF NOT EXISTS event_no_update BEFORE UPDATE ON event BEGIN " +
        "SELECT RAISE(ABORT, 'event is append-only: append a correction instead'); END",
    "CREATE TRIGGER IF NOT EXISTS event_no_delete BEFORE DELETE ON event BEGIN " +
        "SELECT RAISE(ABORT, 'event is append-only: never delete history'); END",
    "CREATE TABLE IF NOT EXISTS job (" +
        "id TEXT PRIMARY KEY, kind TEXT NOT NULL, " +
        "state TEXT NOT NULL DEFAULT 'created' CHECK (state IN ('created','running','awaiting_user','done','failed','cancelled')), " +
        "payload_json TEXT NULL CHECK (payload_json IS NULL OR json_valid(payload_json)), " +
        "created_at TEXT NOT NULL, updated_at TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS job_step (" +
        "job_id TEXT NOT NULL REFERENCES job(id), step TEXT NOT NULL, " +
        "state TEXT NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','running','done','failed','skipped')), " +
        "output_ref TEXT NULL, error TEXT NULL, updated_at TEXT NOT NULL, PRIMARY KEY (job_id, step))",
    "CREATE TRIGGER IF NOT EXISTS plan_version_no_rewrite BEFORE UPDATE OF id, plan_id, parent_id, " +
        "reason, diff_json, effective_from_week, created_at ON plan_version BEGIN " +
        "SELECT RAISE(ABORT, 'plan_version is immutable'); END",
    "CREATE TRIGGER IF NOT EXISTS plan_version_no_delete BEFORE DELETE ON plan_version " +
        "WHEN OLD.status <> 'draft' BEGIN " +
        "SELECT RAISE(ABORT, 'only draft plan_version can be replaced'); END",
    "CREATE TRIGGER IF NOT EXISTS plan_unit_no_rewrite BEFORE UPDATE ON plan_unit " +
        "WHEN (SELECT status FROM plan_version WHERE id = OLD.plan_version_id) <> 'draft' BEGIN " +
        "SELECT RAISE(ABORT, 'plan_unit is immutable once version is not draft'); END",
    "CREATE TRIGGER IF NOT EXISTS plan_unit_no_delete BEFORE DELETE ON plan_unit " +
        "WHEN (SELECT status FROM plan_version WHERE id = OLD.plan_version_id) <> 'draft' BEGIN " +
        "SELECT RAISE(ABORT, 'plan_unit is immutable once version is not draft'); END",
    "CREATE TRIGGER IF NOT EXISTS commitment_no_rewrite BEFORE UPDATE ON commitment " +
        "WHEN (SELECT status FROM plan_version WHERE id = OLD.plan_version_id) <> 'draft' BEGIN " +
        "SELECT RAISE(ABORT, 'commitment is immutable once version is not draft'); END",
    "CREATE TRIGGER IF NOT EXISTS commitment_no_delete BEFORE DELETE ON commitment " +
        "WHEN (SELECT status FROM plan_version WHERE id = OLD.plan_version_id) <> 'draft' BEGIN " +
        "SELECT RAISE(ABORT, 'commitment is immutable once version is not draft'); END",
    "CREATE TRIGGER IF NOT EXISTS commitment_unit_no_rewrite BEFORE UPDATE ON commitment_unit " +
        "WHEN (SELECT pv.status FROM plan_version pv JOIN commitment c ON c.plan_version_id = pv.id " +
        "WHERE c.id = OLD.commitment_id) <> 'draft' BEGIN " +
        "SELECT RAISE(ABORT, 'commitment_unit is immutable once version is not draft'); END",
    "CREATE TRIGGER IF NOT EXISTS commitment_unit_no_delete BEFORE DELETE ON commitment_unit " +
        "WHEN (SELECT pv.status FROM plan_version pv JOIN commitment c ON c.plan_version_id = pv.id " +
        "WHERE c.id = OLD.commitment_id) <> 'draft' BEGIN " +
        "SELECT RAISE(ABORT, 'commitment_unit is immutable once version is not draft'); END",
    "CREATE TRIGGER IF NOT EXISTS occurrence_no_remap BEFORE UPDATE OF id, commitment_id, unit_id, day, " +
        "estimate_minutes, mode, moved_from, carried_from, created_at ON occurrence BEGIN " +
        "SELECT RAISE(ABORT, 'occurrence identity is immutable; create a new occurrence to move/split'); END",
    "CREATE TRIGGER IF NOT EXISTS occurrence_no_delete BEFORE DELETE ON occurrence BEGIN " +
        "SELECT RAISE(ABORT, 'occurrence is never deleted; settle it as missed/skipped'); END",
    "CREATE TRIGGER IF NOT EXISTS occurrence_status_transition BEFORE UPDATE OF status ON occurrence " +
        "WHEN NOT ((OLD.status = 'planned' AND NEW.status IN ('done','missed','skipped','superseded')) " +
        "OR (OLD.status = 'done' AND NEW.status = 'planned')) BEGIN " +
        "SELECT RAISE(ABORT, 'illegal occurrence status transition'); END",
    "CREATE TRIGGER IF NOT EXISTS session_no_rewrite_when_closed BEFORE UPDATE ON session " +
        "WHEN OLD.ended_at IS NOT NULL BEGIN SELECT RAISE(ABORT, 'closed session is immutable'); END",
    "CREATE VIEW IF NOT EXISTS v_occurrence_detail AS SELECT " +
        "o.id, o.day, o.status, o.mode, o.estimate_minutes, o.note, o.moved_from, o.carried_from, " +
        "o.superseded_by, o.todo_id, o.unit_id, o.commitment_id, o.created_at, " +
        "u.title AS unit_title, u.chapter, u.min_version_minutes, " +
        "c.week, pv.plan_id, pv.id AS plan_version_id, t.title AS todo_title " +
        "FROM occurrence o " +
        "LEFT JOIN plan_unit u ON u.id = o.unit_id " +
        "LEFT JOIN commitment c ON c.id = o.commitment_id " +
        "LEFT JOIN plan_version pv ON pv.id = c.plan_version_id " +
        "LEFT JOIN todo t ON t.id = o.todo_id",
    "CREATE VIEW IF NOT EXISTS v_week_commitment AS SELECT " +
        "c.id AS commitment_id, c.week, pv.plan_id, pv.id AS plan_version_id, " +
        "cu.unit_id, cu.sort_order, u.chapter, u.title, u.estimate_minutes " +
        "FROM commitment c JOIN commitment_unit cu ON cu.commitment_id = c.id " +
        "JOIN plan_unit u ON u.id = cu.unit_id JOIN plan_version pv ON pv.id = c.plan_version_id",
    "PRAGMA user_version = 1",
)
