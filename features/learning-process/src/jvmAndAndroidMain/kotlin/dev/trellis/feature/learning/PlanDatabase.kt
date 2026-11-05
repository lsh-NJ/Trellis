package dev.trellis.feature.learning

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/**
 * 本地 SQLite 连接与迁移。
 *
 * 一个学习计划的数据库就是一个文件（`<trellis home>/plans/plan.db`）。
 * 迁移只增不减：`schema_migration` 记版本，DDL 是幂等的 `IF NOT EXISTS`，
 * 所以重复打开不会炸。
 */
internal class PlanDatabase private constructor(val connection: Connection) : AutoCloseable {

    companion object {
        fun open(path: Path): PlanDatabase {
            Files.createDirectories(path.toAbsolutePath().parent)
            val db = PlanDatabase(Sqlite.open(path.toAbsolutePath().toString()))
            db.apply()
            return db
        }

        fun inMemory(): PlanDatabase {
            val db = PlanDatabase(Sqlite.openInMemory())
            db.apply()
            return db
        }
    }

    private fun apply() {
        connection.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
            st.execute("PRAGMA busy_timeout = 3000")
            st.execute("PRAGMA synchronous = NORMAL")
            PLAN_SCHEMA_V1.forEach { sql -> st.execute(sql) }
        }
        migrate()
    }

    private fun migrate() {
        val applied = connection.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM schema_migration").use { rs ->
                rs.next(); rs.getInt(1)
            }
        }
        if (applied == 0) {
            // `INSERT OR IGNORE` 而不是先查再插：手机上启动时计划库会被开两次
            // （运行时创建与管家的首个 tick 撞在一起），两个连接都读到「还没迁移」，
            // 后到的那个插入 version 1 就会撞主键 —— 表现是「计划库打开失败」，
            // 而库里其实已经有表了。OR IGNORE 让这种竞争变成无害的重复写入。
            connection.prepareStatement(
                "INSERT OR IGNORE INTO schema_migration(version, applied_at) VALUES (1, ?)",
            ).use { ps ->
                ps.setString(1, java.time.Instant.now().toString())
                ps.executeUpdate()
            }
        }
    }

    override fun close() = connection.close()
}
