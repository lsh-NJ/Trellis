package dev.trellis.activity

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 分类体系的存盘入口：`<trellis home>/activity/taxonomy.json`。
 *
 * 为什么不放 SQLite（原始事件库 `activity.db`）：这份数据是**用户手写的规则**，不是采集来的事实。
 * 放成独立 JSON 有三个好处——能直接看、能进版本管理/备份、坏了能手改；
 * 而原始事件库坏了只能重新从 AW 拉。两者生命周期不同，不要放一个文件里。
 *
 * 写盘用「临时文件 + 原子替换」：分类文件被写坏的后果是「所有时间都变成未分类」，
 * 不能在写到一半时断电/崩溃留下半个 JSON。
 */
class ActivityTaxonomyStore(private val file: Path) {

    /** 文件不存在或读坏了回到出厂分类；读坏了会把坏文件改名留档，方便用户找回手写规则。 */
    fun load(): ActivityTaxonomy {
        if (!Files.exists(file)) return ActivityTaxonomy()
        val text = runCatching { Files.readString(file) }.getOrElse { return ActivityTaxonomy() }
        val parsed = runCatching { json.decodeFromString<ActivityTaxonomy>(text) }.getOrElse {
            quarantine()
            return ActivityTaxonomy()
        }
        // 旧版本文件：目前只有 v1，先走同一条补齐流程；以后字段变了在这里按 version 分支迁移。
        return parsed.normalised()
    }

    /** 保存。写入前先 [ActivityTaxonomy.normalised] 补齐颜色/兜底分类，避免存进一个「永远会显示坏颜色」的文件。 */
    fun save(taxonomy: ActivityTaxonomy) {
        val parent = file.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temp = parent.resolve("taxonomy.json.tmp")
        Files.writeString(temp, json.encodeToString(taxonomy.normalised()))
        runCatching {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            // 某些文件系统（网络盘/WSL 跨盘挂载）不支持原子移动，退化成普通替换，但内容已经是完整的。
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** 把读不动的文件改名成 `taxonomy.json.bad-<时间戳>`，不直接覆盖 —— 用户可能只写错了一个字符。 */
    private fun quarantine() {
        runCatching {
            val stamp = System.currentTimeMillis()
            Files.move(file, file.resolveSibling("taxonomy.json.bad-$stamp"), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** 默认位置：`<home>/activity/taxonomy.json`。 */
        fun defaultFile(home: Path): Path = home.resolve("activity").resolve("taxonomy.json")
    }
}