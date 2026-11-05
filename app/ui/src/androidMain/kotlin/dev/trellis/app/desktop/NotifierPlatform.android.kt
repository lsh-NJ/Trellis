package dev.trellis.app.desktop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * 安卓通知。
 *
 * 和桌面那三套命令行不是一回事：安卓要在系统里建一个 channel，然后交给
 * `NotificationManager` 发。权限（API 33+ 的 `POST_NOTIFICATIONS`）没给时**不崩** ——
 * 系统会静默丢弃，这正是「通知是尽力而为」想要的语义。
 *
 * Context 由 `MainActivity` 注入（和 `HostPlatform` 同一个注入点）：
 * 为了一条通知把 Context 传遍整个运行时不值得。
 */
internal object AndroidNotifications {
    var context: Context? = null

    private const val CHANNEL_ID = "trellis-default"
    private const val CHANNEL_NAME = "Trellis 提醒"

    fun install(ctx: Context) {
        context = ctx.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "管家早晚问候、笔记自动完成计划节点"
                },
            )
        }
    }

    fun notify(title: String, body: String) {
        val ctx = context ?: return
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val icon = ctx.applicationInfo?.icon ?: android.R.drawable.ic_dialog_info
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(ctx, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(ctx)
        }
        runCatching {
            manager.notify(
                System.currentTimeMillis().toInt(),
                notification
                    .setSmallIcon(icon)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(android.app.Notification.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}



/**
 * 宿主注入点：建 channel、记住 Context。
 *
 * 单独一个 public 函数，是因为 `AndroidNotifications` 本身是 internal ——
 * 安卓壳在另一个模块，只需要这一句。
 */
fun installAndroidNotifications(context: Context) {
    AndroidNotifications.install(context)
    Notifier.platformFactory = { Notifier { title, body -> AndroidNotifications.notify(title, body) } }
}
