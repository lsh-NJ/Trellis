package dev.trellis.app.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.trellis.domain.Task
import dev.trellis.kernel.slot.Slots
import dev.trellis.ui.SlotArea
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAB_TASKS = 0
private const val TAB_PLUGINS = 1
private const val TAB_DIAG = 2

@Composable
fun AppScreen(runtime: AppRuntime) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(TAB_TASKS) }

    // 每秒刷一次。
    //
    // 不是最终形态：真实产品里应该由数据变更通知驱动（领域层发 Flow，界面订阅）。
    // 先轮询是因为插件也能改数据（比如卡片上的按钮），而「变更通知」
    // 是领域层要新增的一项能力，不在这一轮范围内。
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            runtime.refresh()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Trellis", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    "内核 " + runtime.kernel.currentState,
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Spacer(Modifier.height(12.dp))

            val errorCount = runtime.kernelErrors.size
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == TAB_TASKS, onClick = { tab = TAB_TASKS }, text = { Text("任务") })
                Tab(
                    selected = tab == TAB_PLUGINS,
                    onClick = { tab = TAB_PLUGINS },
                    text = { Text("插件 " + runtime.pluginInfos.size) },
                )
                Tab(
                    selected = tab == TAB_DIAG,
                    onClick = { tab = TAB_DIAG },
                    text = { Text(if (errorCount == 0) "诊断" else "诊断 " + errorCount) },
                )
            }

            Spacer(Modifier.height(16.dp))

            when (tab) {
                TAB_TASKS -> TasksTab(runtime, Modifier.weight(1f))
                TAB_PLUGINS -> PluginsScreen(runtime, Modifier.weight(1f))
                else -> DiagnosticsScreen(runtime, Modifier.weight(1f))
            }

            Spacer(Modifier.height(10.dp))
            StatusBar(runtime)
        }

        // 询问框浮在最上层，切到哪个标签都要弹
        AskDialogs(runtime)
    }
}

@Composable
private fun TasksTab(runtime: AppRuntime, modifier: Modifier) {
    Column(modifier) {
        Header(runtime)
        Spacer(Modifier.height(16.dp))

        // 插件贡献的卡片长在这里  界面上预留的「插座」
        SlotArea(runtime.kernel.extensions, Slots.HomeSection)

        Spacer(Modifier.height(16.dp))
        TaskList(runtime, Modifier.weight(1f))

        Spacer(Modifier.height(12.dp))
        AddTaskRow(runtime)
    }
}

@Composable
private fun Header(runtime: AppRuntime) {
    Column {
        val goal = runtime.goal
        Text(
            text = if (goal != null) "目标：" + goal.title else "还没有计划",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(10.dp))
        val s = runtime.summary
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Stat("待办", (s.total - s.done).toString())
            Stat("今天到期", s.dueToday.toString())
            Stat("逾期", s.overdue.toString())
            Stat("已投入", s.spentMinutes.toString() + " 分钟")
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun TaskList(runtime: AppRuntime, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    Column(modifier) {
        Text("待办", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        val tasks = runtime.tasks
        if (tasks.isEmpty()) {
            Text("还没有任务，在下面加一条试试。", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(tasks, key = { it.id.value }) { task ->
                TaskRow(
                    task = task,
                    onToggle = { scope.launch { runtime.toggle(it) } },
                    onLog = { scope.launch { runtime.logTime(it, 25) } },
                    onDelete = { scope.launch { runtime.delete(it) } },
                )
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: Task,
    onToggle: (Task) -> Unit,
    onLog: (Task) -> Unit,
    onDelete: (Task) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = task.isDone, onCheckedChange = { onToggle(task) })

        Column(Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
            )
            val subtitle = subtitleOf(task)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall)
            }
        }

        TextButton(onClick = { onLog(task) }) { Text("+25 分钟") }
        TextButton(onClick = { onDelete(task) }) { Text("删除") }
    }
}

private fun subtitleOf(task: Task): String? {
    val parts = mutableListOf<String>()
    val dueAt = task.dueAt
    if (dueAt != null) {
        val overdue = dueAt < System.currentTimeMillis() && !task.isDone
        parts += if (overdue) "已逾期" else "有截止时间"
    }
    if (task.spentMinutes > 0) parts += "已投入 " + task.spentMinutes + " 分钟"
    if (task.note.isNotEmpty()) parts += "总结：" + task.note
    return if (parts.isEmpty()) null else parts.joinToString("  ")
}

@Composable
private fun AddTaskRow(runtime: AppRuntime) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("新增一条待办") },
            singleLine = true,
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                val title = text
                text = ""
                scope.launch { runtime.addTask(title) }
            },
            enabled = text.isNotBlank(),
        ) { Text("添加") }
    }
}

@Composable
private fun StatusBar(runtime: AppRuntime) {
    Column {
        HorizontalDivider()
        Spacer(Modifier.height(6.dp))
        Text(
            text = "数据 " + runtime.storeFile.absolutePath,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
