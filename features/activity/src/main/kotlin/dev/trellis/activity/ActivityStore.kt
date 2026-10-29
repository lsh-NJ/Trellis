package dev.trellis.activity

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/**
 * 原始事件的本地库（`<trellis home>/activity/activity.db`）。
 *
 * 定位：**程序访问活动数据的唯一入口**。上层（整理、聚合、给 AI 看什么）全部走这里，
 * 谁也不直连 aw-server —— 这样 AW 没开、AW 升级改了 schema、或者哪天换一个采集器，
 * 都只影响 `ActivitySync` 这一个文件。
 *
 * 表只有两张（bucket 元信息 + 原始事件），刻意**不存分类结果**：
 * 分类规则会反复改，但原始事件不能因为改规则而需要重新采集。
 */
class ActivityStore private constructor(val connection: Connection) : AutoCloseable {

    fun upsertBucket(bucket: AwBucket) {
        connection.prepareStatement(
            """
            INSERT INTO aw_bucket(bucket, type, hostname, created_at, last_synced_ms)
            VALUES(?,?,?,?,0)
            ON CONFLICT(bucket) DO UPDATE SET
                type = excluded.type,
                hostname = excluded.hostname,
                created_at = excluded.created_at
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, bucket.id)
            ps.setString(2, bucket.type)
            ps.setString(3, bucket.hostname)
            ps.setString(4, bucket.createdAt)
            ps.executeUpdate()
        }
    }

    /**
     * 幂等写入。主键是 `(bucket, aw_id)`。
     *
     * 为什么要 UPDATE 而不是 INSERT OR IGNORE：aw-server 对**仍在进行中**的窗口会不断延长
     * 同一条事件的 `duration`。同步时故意重拉尾部几分钟，靠这里的 UPDATE 把变长的时长接上。
     *
     * 为什么时长取 `MAX(旧值, 新值)` 而不是直接覆盖：aw-server 会把**跨越查询区间边界**的事件
     * 裁剪到边界上（实测：同一个 589 分钟的 AFK 事件，`?start=05:00&end=06:00` 只返回 3600 秒，
     * 换成 `end=12:00` 返回 17347 秒）。同步是按天切窗拉的，如果直接覆盖，一条跨窗口的长事件
     * 会被反复削短 —— 最后库里剩下的时长取决于「最后一个窗口切在哪里」，那毫无意义。
     * 裁剪只会变短，所以取大值既保住完整时长，也不妨碍「正在进行的窗口」继续变长。
     * 代价：如果 aw-server 真的把某条事件改短了（纠正错误），这里看不见。接受这个代价，
     * 因为「少算」比「算错」更难发现。
     */
    fun upsertEvents(events: List<RawEvent>): Int {
        if (events.isEmpty()) return 0
        val previous = connection.autoCommit
        connection.autoCommit = false
        try {
            val touched = connection.prepareStatement(
                """
                INSERT INTO raw_event(bucket, aw_id, ts_ms, dur_ms, app, title, url, status)
                VALUES(?,?,?,?,?,?,?,?)
                ON CONFLICT(bucket, aw_id) DO UPDATE SET
                    ts_ms = MIN(raw_event.ts_ms, excluded.ts_ms),
                    dur_ms = MAX(raw_event.dur_ms, excluded.dur_ms),
                    app = excluded.app,
                    title = excluded.title,
                    -- url 只在浏览器 bucket 里有；window bucket 的同一行不该把已有的 url 抹成 null。
                    url = COALESCE(excluded.url, raw_event.url),
                    status = excluded.status
                """.trimIndent(),
            ).use { ps ->
                for (e in events) {
                    ps.setString(1, e.bucket)
                    ps.setLong(2, e.awId)
                    ps.setLong(3, e.tsMs)
                    ps.setLong(4, e.durMs)
                    ps.setString(5, e.app)
                    ps.setString(6, e.title)
                    ps.setString(7, e.url)
                    ps.setString(8, e.status)
                    ps.addBatch()
                }
                ps.executeBatch().count { it != Statement.EXECUTE_FAILED }
            }
            connection.commit()
            return touched
        } catch (t: Throwable) {
            connection.rollback()
            throw t
        } finally {
            connection.autoCommit = previous
        }
    }

    /**
     * 这个 bucket 里最长的一条事件有多长。
     *
     * 同步用它当「补拉余量」：aw-server 裁剪边界事件，所以想完整拿到一条长事件，
     * 查询区间必须在它两侧都留出空间。余量取这个值，就等于「至少能装下我见过的最长事件」。
     */
    fun maxDurMs(bucket: String): Long =
        connection.prepareStatement("SELECT COALESCE(MAX(dur_ms), 0) FROM raw_event WHERE bucket = ?")
            .use { ps ->
                ps.setString(1, bucket)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }

    /** 这个 bucket 已经收进来的最晚结束时刻；0 = 还没有任何数据。 */
    fun maxEndMs(bucket: String): Long =
        connection.prepareStatement("SELECT COALESCE(MAX(ts_ms + dur_ms), 0) FROM raw_event WHERE bucket = ?")
            .use { ps ->
                ps.setString(1, bucket)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }

    fun lastSyncedMs(bucket: String): Long =
        connection.prepareStatement("SELECT last_synced_ms FROM aw_bucket WHERE bucket = ?").use { ps ->
            ps.setString(1, bucket)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }

    fun setLastSyncedMs(bucket: String, ms: Long) {
        connection.prepareStatement("UPDATE aw_bucket SET last_synced_ms = ? WHERE bucket = ?").use { ps ->
            ps.setLong(1, ms)
            ps.setString(2, bucket)
            ps.executeUpdate()
        }
    }

    /** 按时间升序读原始事件。`bucket` 传 null = 所有 bucket 混在一起（window + afk）。 */
    fun eventsByRange(fromMs: Long, toMs: Long, bucket: String? = null): List<RawEvent> {
        val sql = buildString {
            append("SELECT bucket, aw_id, ts_ms, dur_ms, app, title, url, status FROM raw_event")
            append(" WHERE ts_ms < ? AND ts_ms + dur_ms > ?")
            if (bucket != null) append(" AND bucket = ?")
            append(" ORDER BY ts_ms ASC")
        }
        return connection.prepareStatement(sql).use { ps ->
            ps.setLong(1, toMs)
            ps.setLong(2, fromMs)
            if (bucket != null) ps.setString(3, bucket)
            ps.executeQuery().use { rs ->
                val out = ArrayList<RawEvent>()
                while (rs.next()) {
                    out += RawEvent(
                        bucket = rs.getString(1),
                        awId = rs.getLong(2),
                        tsMs = rs.getLong(3),
                        durMs = rs.getLong(4),
                        app = rs.getString(5),
                        title = rs.getString(6),
                        url = rs.getString(7),
                        status = rs.getString(8),
                    )
                }
                out
            }
        }
    }

    fun buckets(): List<AwBucket> =
        connection.prepareStatement("SELECT bucket, type, hostname, created_at FROM aw_bucket ORDER BY bucket")
            .use { ps ->
                ps.executeQuery().use { rs ->
                    val out = ArrayList<AwBucket>()
                    while (rs.next()) {
                        out += AwBucket(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))
                    }
                    out
                }
            }

    fun count(): Int =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM raw_event").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    fun countOf(bucket: String): Int =
        connection.prepareStatement("SELECT COUNT(*) FROM raw_event WHERE bucket = ?").use { ps ->
            ps.setString(1, bucket)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    /** 保留策略：删掉结束时间早于 `ms` 的事件。返回删掉的行数。 */
    fun pruneBefore(ms: Long): Int =
        connection.prepareStatement("DELETE FROM raw_event WHERE ts_ms + dur_ms < ?").use { ps ->
            ps.setLong(1, ms)
            ps.executeUpdate()
        }

    override fun close() = connection.close()

    companion object {
        fun open(path: Path): ActivityStore {
            Files.createDirectories(path.toAbsolutePath().parent)
            val db = ActivityStore(DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}"))
            db.apply()
            return db
        }

        fun inMemory(): ActivityStore {
            val db = ActivityStore(DriverManager.getConnection("jdbc:sqlite::memory:"))
            db.apply()
            return db
        }

        private val SCHEMA_V1: List<String> = listOf(
            """
            CREATE TABLE IF NOT EXISTS schema_migration(
                version    INTEGER PRIMARY KEY,
                applied_at TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS aw_bucket(
                bucket         TEXT PRIMARY KEY,
                type           TEXT NOT NULL,
                hostname       TEXT,
                created_at     TEXT,
                last_synced_ms INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS raw_event(
                bucket  TEXT    NOT NULL,
                aw_id   INTEGER NOT NULL,
                ts_ms   INTEGER NOT NULL,
                dur_ms  INTEGER NOT NULL,
                app     TEXT,
                title   TEXT,
                status  TEXT,
                PRIMARY KEY (bucket, aw_id)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS idx_raw_event_ts ON raw_event(ts_ms)",
            "CREATE INDEX IF NOT EXISTS idx_raw_event_bucket_ts ON raw_event(bucket, ts_ms)",
        )
    }

    private fun apply() {
        connection.createStatement().use { st ->
            st.execute("PRAGMA journal_mode = WAL")
            st.execute("PRAGMA busy_timeout = 3000")
            st.execute("PRAGMA synchronous = NORMAL")
            SCHEMA_V1.forEach { sql -> st.execute(sql) }
            migrate(st)
        }
    }

    /**
     * 逐版升级，新库和旧库走同一条路：V1 建表（不含 url），V2 给 `raw_event` 补 url。
     *
     * 为什么新库也不直接把 url 写进 V1 的 CREATE TABLE：那样「新建的库」和「升级来的库」
     * 会走两条 DDL 路径，以后加第三个字段时很容易只改到其中一条。统一走迁移，只有一处事实。
     */
    private fun migrate(st: Statement) {
        var version = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migration").use { rs ->
            if (rs.next()) rs.getInt(1) else 0
        }
        if (version < 1) {
            record(st, 1)
            version = 1
        }
        if (version < 2) {
            // 先查列再加：迁移中途崩溃时重跑，不能因为「列已经存在」而报错卡死启动。
            if (!hasColumn(st, "raw_event", "url")) {
                st.execute("ALTER TABLE raw_event ADD COLUMN url TEXT")
            }
            record(st, 2)
        }
    }

    private fun record(st: Statement, version: Int) {
        st.execute(
            "INSERT INTO schema_migration(version, applied_at) VALUES ($version, '${java.time.Instant.now()}')",
        )
    }

    private fun hasColumn(st: Statement, table: String, column: String): Boolean =
        st.executeQuery("PRAGMA table_info($table)").use { rs ->
            generateSequence { if (rs.next()) rs.getString("name") else null }.any { it == column }
        }
}