package dev.trellis.feature.learning

import java.sql.Connection
import java.sql.DriverManager

/**
 * 打开一个本地 SQLite 文件。
 *
 * 这里只有一个平台判断，因为整块后端的 SQL（`PlanQueries` / `PlanCommands` / 迁移）
 * 都是标准 SQL，**不该为了换平台重写一遍**。
 *
 * - 桌面：`sqlite-jdbc`（`jdbc:sqlite:`）；
 * - 安卓：没有 sqlite-jdbc（那是带本地库的桌面 jar），走 SQLDroid ——
 *   它把 JDBC 调用翻译成 `android.database.sqlite`，URL 前缀是 `jdbc:sqldroid:`。
 *
 * 判断方式是运行时看虚拟机厂商，而不是编译期 `expect/actual`：
 * 两个平台编的是同一份字节码，驱动由谁提供是**运行时**的事，
 * 用 `expect` 反而会把这个共享源集劈成两半。
 */
internal object Sqlite {

    /** Android 的 `java.vm.vendor` 是 "The Android Project"，桌面是 Oracle/Temurin/… */
    private val isAndroid: Boolean =
        System.getProperty("java.vm.vendor").orEmpty().contains("Android", ignoreCase = true) ||
            System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true)

    fun open(path: String): Connection {
        val url = if (isAndroid) {
            // 驱动必须显式注册：安卓上没有 `META-INF/services/java.sql.Driver` 那套自动发现。
            Class.forName("org.sqldroid.SQLDroidDriver")
            "jdbc:sqldroid:$path"
        } else {
            "jdbc:sqlite:$path"
        }
        return DriverManager.getConnection(url)
    }

    /** 内存库：只有测试用（桌面），安卓上不会走到。 */
    fun openInMemory(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
}
