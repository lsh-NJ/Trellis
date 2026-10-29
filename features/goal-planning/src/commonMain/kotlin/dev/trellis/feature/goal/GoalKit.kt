package dev.trellis.feature.goal

import dev.trellis.kernel.storage.KvStore
import kotlinx.serialization.json.Json

/**
 * 落盘。只有三件事：读、写、清。
 *
 * 所有规则都在 [GoalEngine] 里，这一层刻意不含判断 —— 一旦把「够不够 0.95」
 * 写进读写层，就只能起真内核才能测。
 */
class GoalKit(private val kv: KvStore) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun load(): GoalMirror {
        val raw = kv.get(KEY) ?: return GoalMirror()
        // 坏数据当没存过：留着半份会解不动，而用户看到的是「一片空白」，
        // 与其让他以为是插件坏了，不如重新开始并保留设置。
        return runCatching { json.decodeFromString<GoalMirror>(raw) }.getOrElse { GoalMirror() }
    }

    suspend fun save(mirror: GoalMirror) {
        kv.put(KEY, json.encodeToString(GoalMirror.serializer(), mirror))
    }

    /** 删除这份规划：清数据，但插件本体和设置留在 manifest 那一层。 */
    suspend fun clear() {
        kv.delete(KEY)
    }

    companion object {
        /** 单键存整份：读的时候要么全读到，要么整体退回默认，不留半截状态。 */
        const val KEY = "mirror"
    }
}
