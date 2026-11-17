package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.habit.HabitAnalysisManager
import me.rerere.rikkahub.data.ai.habit.HabitAnalysisResult
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * 习惯分析设置页。
 *
 * 做两件事：控制自动分析的节奏，以及手动触发一次。
 * 分析结果写到 `my-habits` 技能里，用户可以自己去看、去改。
 */
@Composable
fun SettingHabitPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.habitAnalysisConfig
    val manager = koinInject<HabitAnalysisManager>()
    val scope = rememberCoroutineScope()

    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<HabitAnalysisResult?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_habit_page_title)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                CardGroup {
                    switchItem(
                        checked = config.enabled,
                        onCheckedChange = { enabled ->
                            vm.updateSettings { latest ->
                                latest.copy(
                                    habitAnalysisConfig = latest.habitAnalysisConfig.copy(enabled = enabled)
                                )
                            }
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_habit_page_enable_desc))
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_habit_page_enable))
                        },
                    )
                }
            }

            if (config.enabled) {
                item {
                    CardGroup(title = { Text(stringResource(R.string.setting_habit_page_range)) }) {
                        formItem(
                            headlineContent = { Text(stringResource(R.string.setting_habit_page_range_days)) },
                            supportingContent = {
                                Text(stringResource(R.string.setting_habit_page_range_days_desc))
                            },
                            trailingContent = { CardGroupValue("${config.rangeDays} 天") },
                        ) {
                            Slider(
                                value = config.rangeDays.toFloat(),
                                onValueChange = { raw ->
                                    // 只给 7/14/30/90 四档：太细的粒度对结果没影响，反而难选
                                    val snapped = listOf(7, 14, 30, 90)
                                        .minByOrNull { kotlin.math.abs(it - raw) } ?: 30
                                    vm.updateSettings { latest ->
                                        latest.copy(
                                            habitAnalysisConfig = latest.habitAnalysisConfig.copy(rangeDays = snapped)
                                        )
                                    }
                                },
                                valueRange = 7f..90f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        formItem(
                            headlineContent = { Text(stringResource(R.string.setting_habit_page_interval)) },
                            supportingContent = {
                                Text(stringResource(R.string.setting_habit_page_interval_desc))
                            },
                            trailingContent = { CardGroupValue("${config.intervalDays} 天") },
                        ) {
                            Slider(
                                value = config.intervalDays.toFloat(),
                                onValueChange = { raw ->
                                    val snapped = listOf(7, 14, 30).minByOrNull { kotlin.math.abs(it - raw) } ?: 14
                                    vm.updateSettings { latest ->
                                        latest.copy(
                                            habitAnalysisConfig = latest.habitAnalysisConfig.copy(intervalDays = snapped)
                                        )
                                    }
                                },
                                valueRange = 7f..30f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            item {
                CardGroup(title = { Text(stringResource(R.string.setting_habit_page_manual)) }) {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_habit_page_run_now)) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.setting_habit_page_run_now_desc))
                                if (config.lastRunTime > 0) {
                                    Text(
                                        text = stringResource(
                                            R.string.setting_habit_page_last_run,
                                            formatLastRun(config.lastRunTime),
                                            config.lastMessageCount,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                when (val r = result) {
                                    is HabitAnalysisResult.Success -> Text(
                                        text = stringResource(
                                            R.string.setting_habit_page_result_ok,
                                            r.messageCount,
                                            r.skillChars,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    is HabitAnalysisResult.Failed -> Text(
                                        text = r.reason,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    else -> Unit
                                }
                            }
                        },
                        trailingContent = {
                            if (running) {
                                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                            }
                        },
                    ) {
                        TextButton(onClick = {
                            if (running) return@TextButton
                            running = true
                            result = null
                            scope.launch {
                                result = manager.run(force = true)
                                running = false
                            }
                        }) {
                            Text(stringResource(R.string.setting_habit_page_run_button))
                        }
                    }
                }
            }

            // 分析结果预览：直接读 my-habits 的内容显示出来，
            // 不用切到技能页就能看到这次生成了什么
            item {
                val scope2 = rememberCoroutineScope()
                var preview by remember { mutableStateOf<String?>(null) }
                var expanded by remember { mutableStateOf(false) }

                val ctx = androidx.compose.ui.platform.LocalContext.current
                androidx.compose.runtime.LaunchedEffect(config.lastRunTime, ctx) {
                    preview = withContext(Dispatchers.IO) { readHabitSkill(ctx) }
                }

                CardGroup(title = { Text(stringResource(R.string.setting_habit_page_preview)) }) {
                    item(
                        headlineContent = {
                            Text(
                                if (preview == null) stringResource(R.string.setting_habit_page_preview_empty)
                                else stringResource(R.string.setting_habit_page_preview_title)
                            )
                        },
                        supportingContent = {
                            Column {
                                val text = preview
                                if (text == null) {
                                    Text(
                                        text = stringResource(R.string.setting_habit_page_preview_hint),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                } else {
                                    Text(
                                        text = if (expanded) text else text.take(400),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                    if (text.length > 400) {
                                        TextButton(onClick = { expanded = !expanded }) {
                                            Text(
                                                if (expanded) stringResource(R.string.setting_habit_page_collapse)
                                                else stringResource(R.string.setting_habit_page_expand)
                                            )
                                        }
                                    }
                                }
                            }
                        },
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.setting_habit_page_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** 把时间戳格式化成 "3 天前" 这种，比绝对时间好读。 */
private fun formatLastRun(timestamp: Long): String {
    val elapsed = System.currentTimeMillis() - timestamp
    val days = elapsed / (24L * 60 * 60 * 1000)
    return when {
        days <= 0 -> "今天"
        days == 1L -> "昨天"
        else -> "$days 天前"
    }
}

/**
 * 读出 my-habits 技能正文，供界面预览。
 *
 * 直接读文件而不是走 SkillManager：这里只要正文，不需要 frontmatter，
 * 也不想因为技能未启用而读不到。
 */
private fun readHabitSkill(context: android.content.Context): String? = runCatching {
    val file = java.io.File(context.filesDir, "skills/my-habits/SKILL.md")
    if (!file.isFile) return null
    val raw = file.readText()
    // 去掉 frontmatter，只留正文
    val end = raw.indexOf("---", startIndex = 3)
    if (end < 0) raw.trim() else raw.substring(end + 3).trim()
}.getOrNull()
