package dev.trellis.feature.learning

import java.io.File
import java.time.Instant

/**
 * 建计划工作流的状态（`job` / `job_step` 表，和计划库同一个 SQLite）。
 *
 * 为什么落库而不是放内存：四步流程跨多轮对话，模型换一轮、用户关一次应用都得能接着走。
 * 表在 v1 契约里就建好了（一直没人用），字段刚好够：job.state 记「等用户点」，
 * job_step 记每一步做了什么、输出是什么。
 *
 * 这里只做状态读写，不碰计划数据；真正的写命令仍在 LearningPlanService 里。
 */
class PlanWorkflowStore(home: File) : AutoCloseable {

    private val database = PlanDatabase.open(File(File(home, "plans"), "plan.db").toPath())
    private val connection get() = database.connection

    data class JobStep(
        val step: String,
        val state: String,
        val output: String?,
        val error: String?,
        val updatedAt: String,
    )

    data class Job(
        val id: String,
        val kind: String,
        val state: String,
        val payload: String,
        val createdAt: String,
        val updatedAt: String,
        val steps: List<JobStep>,
    ) {
        fun step(name: String): JobStep? = steps.firstOrNull { it.step == name }
        fun isAwaiting(step: String): Boolean = state == "awaiting_user" && step(step)?.state == "awaiting_user"
    }

    /** 新建一个工作流；初始状态 running，所有步骤 pending。 */
    fun create(kind: String, payload: String, steps: List<String>): String {
        val now = Instant.now().toString()
        val id = Ids.next("job_")
        connection.prepareStatement(
            "INSERT INTO job(id,kind,state,payload_json,created_at,updated_at) VALUES(?,?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, kind)
            ps.setString(3, "running")
            ps.setString(4, payload)
            ps.setString(5, now)
            ps.setString(6, now)
            ps.executeUpdate()
        }
        steps.forEach { step ->
            connection.prepareStatement(
                "INSERT INTO job_step(job_id,step,state,output_ref,error,updated_at) VALUES(?,?,?,?,?,?)",
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, step)
                ps.setString(3, "pending")
                ps.setNull(4, java.sql.Types.VARCHAR)
                ps.setNull(5, java.sql.Types.VARCHAR)
                ps.setString(6, now)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun load(jobId: String): Job? {
        val row = connection.prepareStatement(
            "SELECT id,kind,state,payload_json,created_at,updated_at FROM job WHERE id = ?",
        ).use { ps ->
            ps.setString(1, jobId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null else Job(
                    id = rs.getString(1),
                    kind = rs.getString(2),
                    state = rs.getString(3),
                    payload = rs.getString(4) ?: "",
                    createdAt = rs.getString(5),
                    updatedAt = rs.getString(6),
                    steps = emptyList(),
                )
            }
        } ?: return null
        return row.copy(steps = stepsOf(jobId))
    }

    /** 最近一个还没结束的工作流（`kind` 省略 = 任意）。 */
    fun latestOpen(kind: String? = null): Job? {
        val sql = if (kind == null) {
            "SELECT id FROM job WHERE state NOT IN ('done','failed','cancelled') ORDER BY created_at DESC, rowid DESC LIMIT 1"
        } else {
            "SELECT id FROM job WHERE kind = ? AND state NOT IN ('done','failed','cancelled') ORDER BY created_at DESC, rowid DESC LIMIT 1"
        }
        val id = connection.prepareStatement(sql).use { ps ->
            if (kind != null) ps.setString(1, kind)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        } ?: return null
        return load(id)
    }

    /** 所有未结束的工作流（按创建时间倒序）。 */
    fun openJobs(kind: String? = null): List<Job> {
        val sql = if (kind == null) {
            "SELECT id FROM job WHERE state NOT IN ('done','failed','cancelled') ORDER BY created_at DESC, rowid DESC"
        } else {
            "SELECT id FROM job WHERE kind = ? AND state NOT IN ('done','failed','cancelled') " +
                "ORDER BY created_at DESC, rowid DESC"
        }
        val ids = connection.prepareStatement(sql).use { ps ->
            if (kind != null) ps.setString(1, kind)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
        return ids.mapNotNull { load(it) }
    }

    /**
     * 计划被删/放弃之后，把绑在这份计划上的未结流程取消。
     *
     * 为什么需要：流程把 planId 存在 payload 里，计划删掉了流程还「开着」，
     * 下次 plan_workflow start 会被「已经有一段流程在进行中」挡死（2026-11-17 真实事故）。
     * 只按 payload 里出现过的 planId 精确匹配，不误伤别的计划。
     */
    fun cancelOpenForPlan(planId: String): Int {
        if (planId.isBlank()) return 0
        val ids = connection.prepareStatement(
            "SELECT id FROM job WHERE state NOT IN ('done','failed','cancelled') " +
                "AND payload_json LIKE ? ORDER BY created_at DESC, rowid DESC",
        ).use { ps ->
            ps.setString(1, "%\"planId\":\"" + planId + "\"%")
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
        val now = Instant.now().toString()
        ids.forEach { id ->
            connection.prepareStatement("UPDATE job SET state = 'cancelled', updated_at = ? WHERE id = ?").use { ps ->
                ps.setString(1, now)
                ps.setString(2, id)
                ps.executeUpdate()
            }
        }
        return ids.size
    }

    private fun stepsOf(jobId: String): List<JobStep> =
        connection.prepareStatement(
            "SELECT step,state,output_ref,error,updated_at FROM job_step WHERE job_id = ? ORDER BY rowid",
        ).use { ps ->
            ps.setString(1, jobId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(JobStep(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
                    }
                }
            }
        }

    fun updatePayload(jobId: String, payload: String) {
        connection.prepareStatement(
            "UPDATE job SET payload_json = ?, updated_at = ? WHERE id = ?",
        ).use { ps ->
            ps.setString(1, payload)
            ps.setString(2, Instant.now().toString())
            ps.setString(3, jobId)
            ps.executeUpdate()
        }
    }

    fun setJobState(jobId: String, state: String) {
        connection.prepareStatement(
            "UPDATE job SET state = ?, updated_at = ? WHERE id = ?",
        ).use { ps ->
            ps.setString(1, state)
            ps.setString(2, Instant.now().toString())
            ps.setString(3, jobId)
            ps.executeUpdate()
        }
    }

    /** 写一步的状态。output 存给人看的结果（也是下一次的上下文），error 存失败原因。 */
    fun setStep(jobId: String, step: String, state: String, output: String? = null, error: String? = null) {
        val now = Instant.now().toString()
        val updated = connection.prepareStatement(
            "UPDATE job_step SET state = ?, output_ref = COALESCE(?, output_ref), " +
                "error = COALESCE(?, error), updated_at = ? WHERE job_id = ? AND step = ?",
        ).use { ps ->
            ps.setString(1, state)
            ps.setString(2, output)
            ps.setString(3, error)
            ps.setString(4, now)
            ps.setString(5, jobId)
            ps.setString(6, step)
            ps.executeUpdate()
        }
        if (updated == 0) {
            connection.prepareStatement(
                "INSERT INTO job_step(job_id,step,state,output_ref,error,updated_at) VALUES(?,?,?,?,?,?)",
            ).use { ps ->
                ps.setString(1, jobId)
                ps.setString(2, step)
                ps.setString(3, state)
                ps.setString(4, output)
                ps.setString(5, error)
                ps.setString(6, now)
                ps.executeUpdate()
            }
        }
        connection.prepareStatement("UPDATE job SET updated_at = ? WHERE id = ?").use { ps ->
            ps.setString(1, now)
            ps.setString(2, jobId)
            ps.executeUpdate()
        }
    }

    override fun close() = database.close()
}
