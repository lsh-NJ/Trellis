package dev.trellis.feature.learning

import java.sql.Connection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 事件 payload 的读取：**不用 SQL 的 `json_extract`**。
 *
 * 为什么：安卓自带的 SQLite 没有 JSON1。实测（MuMu / Android 12，minSdk 26 的真机同理）
 * `libsqlite.so` 里连 `json_valid` 都没有，于是
 *
 *  - `CREATE TABLE ... CHECK (json_valid(payload_json))` 直接抛
 *    `no such function: json_valid` —— 计划库根本建不起来，管家每分钟 tick 抛一次；
 *  - 所有 `json_extract(payload_json, '$.week')` 的查询同样会挂。
 *
 * 桌面由 sqlite-jdbc 提供，是带 JSON1 的 —— 「桌面能跑、手机建表就崩」这类差异
 * 不该藏在 SQL 里。事件量很小（一个计划几百到几千条），按类型取出来在 Kotlin 侧解析
 * 完全够用，换平台也不会再踩一次。
 */
internal object EventPayloads {

    private val json = Json { isLenient = true }

    /**
     * 取某个计划下某类事件的全部 payload，按 seq 升序（[newestFirst] 则降序）。
     *
     * [maxSeq] 用于「按事件序号回看当时的状态」这类查询。
     * 解析失败的行直接跳过：事件是只追加的流水，一条坏数据不该让整页挂掉。
     */
    fun of(
        connection: Connection,
        type: String,
        planId: String,
        newestFirst: Boolean = false,
        maxSeq: Long? = null,
    ): List<JsonObject> {
        val order = if (newestFirst) "DESC" else "ASC"
        val seqClause = if (maxSeq != null) " AND seq <= ?" else ""
        val sql = "SELECT payload_json FROM event WHERE type = ? AND plan_id = ?" + seqClause +
            " ORDER BY seq " + order
        return connection.prepareStatement(sql).use { ps ->
            ps.setString(1, type)
            ps.setString(2, planId)
            if (maxSeq != null) ps.setLong(3, maxSeq)
            ps.executeQuery().use { rs ->
                val out = ArrayList<JsonObject>()
                while (rs.next()) {
                    val raw = rs.getString(1) ?: continue
                    runCatching { json.parseToJsonElement(raw) as? JsonObject }
                        .getOrNull()
                        ?.let { out += it }
                }
                out
            }
        }
    }
}

/** 字符串字段；不是字符串或空串都返回 null，调用方不用再判空两次。 */
internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

/** 整数字段；数字被写成字符串（`"3"`）也认。 */
internal fun JsonObject.num(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()
