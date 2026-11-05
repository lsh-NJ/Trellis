package dev.trellis.activity

import java.sql.Connection
import java.sql.DriverManager

/**
 * 打开本地 SQLite。
 *
 * 和 `:features:learning-process` 里那份是同一个判断、同一段原因：
 * 桌面用 sqlite-jdbc，安卓用 SQLDroid（JDBC → android.database.sqlite）。
 *
 * 为什么复制一份而不是抽公共模块：这 20 行是「平台最后一公里」，
 * 抽成模块要为一个 `DriverManager.getConnection` 增加一条模块依赖边，
 * 而两条边的维护成本比这 20 行高。
 */
internal object Sqlite {

    private val isAndroid: Boolean =
        System.getProperty("java.vm.vendor").orEmpty().contains("Android", ignoreCase = true) ||
            System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true)

    fun open(path: String): Connection {
        val url = if (isAndroid) {
            Class.forName("org.sqldroid.SQLDroidDriver")
            "jdbc:sqldroid:$path"
        } else {
            "jdbc:sqlite:$path"
        }
        return DriverManager.getConnection(url)
    }

    fun openInMemory(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
}
