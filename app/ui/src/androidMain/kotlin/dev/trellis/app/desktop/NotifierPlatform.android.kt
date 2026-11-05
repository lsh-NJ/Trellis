package dev.trellis.app.desktop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

    fun notify(title: String, body: String, target: NotifyTarget) {
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
                    .setContentIntent(pendingIntentFor(ctx, target))
                    .build(),
            )
        }
    }

    /**
     * 点通知要打开的界面。
     *
     * 用 `getLaunchIntentForPackage` 而不是直接 `Intent(ctx, MainActivity::class.java)`：
     * 这个模块（`:app:ui`）不该认识安卓壳里的类 —— 反过来依赖会让「界面在共享层」
     * 这件事破功。启动 Intent 由系统按清单给，加上 extra 就够了。
     *
     * `requestCode` 用 target 的序号：两条通知（会话 / 计划）各挂各的目标，
     * 不会因为 PendingIntent 被复用而跳错页。
     */
    private fun pendingIntentFor(ctx: Context, target: NotifyTarget): PendingIntent? {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        val intent = Intent(launch).apply {
            putExtra(AndroidNav.EXTRA, target.wire)
            // 已有实例就复用它（manifest 里 MainActivity 是 singleTop）：
            // 否则点通知会再起一个 Activity，内核得重建一遍，用户看到的是几秒白屏。
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(ctx, target.ordinal, intent, flags)
    }
}

/**
 * 安卓壳与共享层之间的 deeplink 约定。
 *
 * 只有两个字符串和一个映射，故意做得很小：壳负责「把 extra 读出来并选页签」，
 * 共享层负责「页签序号是多少」。两边都不用认识对方的类。
 */
object AndroidNav {
    /** 通知里带的 extra 名。 */
    const val EXTRA = "dev.trellis.nav"

    /** 读 extra 并翻成页签序号；没有或认不出就返回 null。 */
    fun tabIndexFrom(intent: Intent?): Int? =
        NotifyTarget.fromWire(intent?.getStringExtra(EXTRA))?.tabIndex
}

/**
 * 宿主注入点：建 channel、记住 Context。
 *
 * 单独一个 public 函数，是因为 `AndroidNotifications` 本身是 internal ——
 * 安卓壳在另一个模块，只需要这一句。
 */
fun installAndroidNotifications(context: Context) {
    AndroidNotifications.install(context)
    Notifier.platformFactory = { AndroidNotifier }
}

/**
 * `Notifier` 的安卓实现。
 *
 * 同时是 [TargetedNotifier]：点通知能跳到对应页签。没给目标时按「会话」处理 ——
 * 那是用户最可能想看的落点。
 */
private object AndroidNotifier : TargetedNotifier {
    override fun notify(title: String, body: String) {
        notify(title, body, NotifyTarget.Chat)
    }

    override fun notify(title: String, body: String, target: NotifyTarget) {
        AndroidNotifications.notify(title, body, target)
    }
}
