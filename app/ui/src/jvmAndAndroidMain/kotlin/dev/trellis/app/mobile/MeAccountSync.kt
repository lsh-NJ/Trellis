package dev.trellis.app.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.trellis.app.desktop.AppRuntime
import dev.trellis.sync.SyncConfig
import kotlinx.coroutines.launch

/**
 * 「我的」页里的账号与同步总览。
 *
 * 桌面端把这两块放在设置页；手机端是独立排版，所以单独一张卡 + 两个底部弹层。
 * 逻辑全部复用 AppRuntime：登录/注册/退出、applySyncConfig、syncNow。
 */
@Composable
fun AccountSyncCard(runtime: AppRuntime, onOpenAccount: () -> Unit, onOpenSync: () -> Unit) {
    val account = runtime.account
    CardBox {
        SectionLabel("账号与同步")
        Spacer(Modifier.height(Gap.s))
        Text(
            if (account == null) "未登录" else account.displayName + "（" + account.username + "）",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(Gap.xs))
        Text(
            syncStatusText(runtime),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Gap.m))
        Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
            PhoneButton("账号", onOpenAccount, modifier = Modifier.weight(1f), filled = false)
            PhoneButton("多端同步", onOpenSync, modifier = Modifier.weight(1f), filled = false)
        }
    }
}

private fun syncStatusText(runtime: AppRuntime): String {
    val config = runtime.syncConfig
    val error = runtime.syncError
    return when {
        error != null -> "同步出错：" + error
        runtime.syncRunning -> "同步中…"
        !config.isValid() -> "同步未开启"
        config.transport == SyncConfig.TRANSPORT_HTTP && runtime.account == null -> "未登录，HTTP 同步不可用"
        runtime.syncReport != null -> "上次：" + runtime.syncReport!!.summary()
        else -> "已开启 · 等待同步"
    }
}

/** 账号：登录 / 注册 / 退出。首个注册用户由服务端设为管理员。 */
@Composable
fun AccountSheet(runtime: AppRuntime, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val account = runtime.account
    var serverUrl by remember(account?.serverUrl) {
        mutableStateOf(account?.serverUrl ?: runtime.syncConfig.baseUrl)
    }
    var gatewayToken by remember(account?.gatewayToken) {
        mutableStateOf(account?.gatewayToken ?: runtime.syncConfig.token)
    }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(horizontal = Gap.xl).padding(bottom = Gap.xxl)) {
        SheetHeading(
            "账号",
            "HTTP 同步必须先登录。首个注册用户自动成为管理员，之后注册需要邀请码；不同账号的数据互相看不见。",
        )
        Spacer(Modifier.height(Gap.l))

        if (account != null) {
            Text(
                "当前：" + account.displayName + "（" + account.username + "）",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(Gap.xs))
            Text(
                account.serverUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Gap.l))
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(Gap.s))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                PhoneButton("退出登录", {
                    scope.launch { message = runtime.logoutAccount() }
                }, modifier = Modifier.weight(1f), filled = false, danger = true)
                PhoneButton("完成", onDone, modifier = Modifier.weight(1f))
            }
            return@Column
        }

        PhoneField(serverUrl, { serverUrl = it }, placeholder = "服务器地址，例如 https://xxx.trycloudflare.com")
        Spacer(Modifier.height(Gap.s))
        PhoneField(gatewayToken, { gatewayToken = it }, placeholder = "网关 token（没有就留空）", password = true)
        Spacer(Modifier.height(Gap.m))
        Segmented(
            items = listOf("登录", "注册"),
            selected = if (register) 1 else 0,
            onSelect = { register = it == 1; message = null },
        )
        Spacer(Modifier.height(Gap.m))
        PhoneField(username, { username = it }, placeholder = "用户名（3–32 位字母数字）")
        Spacer(Modifier.height(Gap.s))
        PhoneField(password, { password = it }, placeholder = "密码（至少 8 位）", password = true)
        if (register) {
            Spacer(Modifier.height(Gap.s))
            PhoneField(displayName, { displayName = it }, placeholder = "显示名（可留空）")
            Spacer(Modifier.height(Gap.s))
            PhoneField(inviteCode, { inviteCode = it }, placeholder = "邀请码（第一个用户不用填）")
        }
        Spacer(Modifier.height(Gap.l))
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Gap.s))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
            PhoneButton(
                if (busy) "请稍候…" else if (register) "注册并登录" else "登录",
                {
                    scope.launch {
                        busy = true
                        message = if (register) {
                            runtime.registerAccount(serverUrl, gatewayToken, username, password, displayName, inviteCode)
                        } else {
                            runtime.loginAccount(serverUrl, gatewayToken, username, password)
                        }
                        busy = false
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !busy,
            )
            PhoneButton("完成", onDone, modifier = Modifier.weight(1f), filled = false)
        }
    }
}

/** 多端同步：手机端只提供 HTTP 服务（文件夹模式要有桌面端的可写目录）。 */
@Composable
fun SyncSheet(runtime: AppRuntime, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val config = runtime.syncConfig
    val account = runtime.account
    var baseUrl by remember(account?.serverUrl, config.baseUrl) {
        mutableStateOf(config.baseUrl.ifBlank { account?.serverUrl.orEmpty() })
    }
    var token by remember(account?.gatewayToken, config.token) {
        mutableStateOf(config.token.ifBlank { account?.gatewayToken.orEmpty() })
    }
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(horizontal = Gap.xl).padding(bottom = Gap.xxl)) {
        SheetHeading(
            "多端同步",
            "只同步知识库笔记与计划文档；会话、活动、密钥不会同步。手机上只支持 HTTP 服务。",
        )
        Spacer(Modifier.height(Gap.l))

        if (account == null) {
            Text(
                "还没登录账号。HTTP 同步必须带用户 token，先去「账号」登录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(Gap.m))
        }

        PhoneField(baseUrl, { baseUrl = it }, placeholder = "https://网关地址")
        Spacer(Modifier.height(Gap.s))
        PhoneField(token, { token = it }, placeholder = "访问口令（X-Token，没有就留空）", password = true)
        Spacer(Modifier.height(Gap.l))

        if (config.isValid()) {
            Text("HTTP 地址：" + config.baseUrl, style = MaterialTheme.typography.labelSmall)
            val report = runtime.syncReport
            report?.let {
                Spacer(Modifier.height(Gap.xs))
                Text(
                    "上次同步：" + it.summary(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (it.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            runtime.syncError?.let {
                Spacer(Modifier.height(Gap.xs))
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(Gap.m))
            Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                PhoneButton(
                    if (runtime.syncRunning) "同步中…" else "立即同步",
                    { scope.launch { runtime.syncNow() } },
                    modifier = Modifier.weight(1f),
                    enabled = account != null && !runtime.syncRunning,
                )
                PhoneButton("关闭同步", {
                    message = runtime.applySyncConfig(enabled = false)
                }, modifier = Modifier.weight(1f), filled = false)
            }
            Spacer(Modifier.height(Gap.m))
        }

        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Gap.s))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
            PhoneButton("保存并开启", {
                message = runtime.applySyncConfig(
                    enabled = baseUrl.isNotBlank(),
                    transport = SyncConfig.TRANSPORT_HTTP,
                    remotePath = "",
                    baseUrl = baseUrl,
                    token = token,
                )
            }, modifier = Modifier.weight(1f))
            PhoneButton("完成", onDone, modifier = Modifier.weight(1f), filled = false)
        }
    }
}
