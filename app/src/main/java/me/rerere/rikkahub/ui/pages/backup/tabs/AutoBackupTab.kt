package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.CloudUpload
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.AutoBackupConfig
import me.rerere.rikkahub.data.sync.autobackup.AutoBackupScheduler
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.utils.toLocalDateTime
import java.time.Instant

private val AutoBackupIntervals = listOf(6, 12, 24, 48, 72)

/**
 * 自动备份设置页。
 *
 * 改完开关立即同步给 WorkManager，不需要用户重启或再点一次保存。
 */
@Composable
fun AutoBackupTab(vm: BackupVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.autoBackupConfig
    val context = LocalContext.current

    fun updateConfig(update: AutoBackupConfig) {
        vm.updateSettings { it.copy(autoBackupConfig = update) }
        // 设置一变就重新注册任务，保证「开关一打开就生效」
        AutoBackupScheduler.sync(context, settings.copy(autoBackupConfig = update))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CardGroup {
            switchItem(
                checked = config.enabled,
                onCheckedChange = { updateConfig(config.copy(enabled = it)) },
                headlineContent = { Text(stringResource(R.string.backup_page_auto_enable)) },
                supportingContent = { Text(stringResource(R.string.backup_page_auto_enable_desc)) },
            )

            if (config.enabled) {
                formItem(
                    headlineContent = { Text(stringResource(R.string.backup_page_auto_interval)) },
                    trailingContent = {
                        CardGroupValue(
                            stringResource(R.string.backup_page_auto_interval_hours, config.intervalHours)
                        )
                    },
                ) {
                    ConnectedChoiceButtons(
                        options = AutoBackupIntervals,
                        selected = config.intervalHours,
                        onSelect = { updateConfig(config.copy(intervalHours = it)) },
                    ) { hours, selected ->
                        ConnectedChoiceLabel(text = hours.toString(), selected = selected)
                    }
                }

                switchItem(
                    checked = config.onlyOnWifi,
                    onCheckedChange = { updateConfig(config.copy(onlyOnWifi = it)) },
                    headlineContent = { Text(stringResource(R.string.backup_page_auto_only_wifi)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_auto_only_wifi_desc)) },
                )

                switchItem(
                    checked = config.onlyWhenCharging,
                    onCheckedChange = { updateConfig(config.copy(onlyWhenCharging = it)) },
                    headlineContent = { Text(stringResource(R.string.backup_page_auto_only_charging)) },
                    supportingContent = { Text(stringResource(R.string.backup_page_auto_only_charging_desc)) },
                )
            }
        }

        // 上次执行结果：失败原因写在这里，用户不用去翻日志
        if (config.lastAutoBackupTime > 0L || config.lastAutoBackupResult.isNotBlank()) {
            CardGroup(title = { Text(stringResource(R.string.backup_page_auto_status)) }) {
                item(
                    headlineContent = {
                        Text(
                            if (config.lastAutoBackupTime == 0L) {
                                stringResource(R.string.backup_page_auto_never)
                            } else {
                                stringResource(
                                    R.string.backup_page_auto_last_time,
                                    Instant.ofEpochMilli(config.lastAutoBackupTime).toLocalDateTime()
                                )
                            }
                        )
                    },
                    supportingContent = {
                        if (config.lastAutoBackupResult.isNotBlank()) {
                            Text(config.lastAutoBackupResult)
                        }
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.CloudUpload) },
                )

                if (config.lastAutoBackupResult.isNotBlank()) {
                    item(
                        headlineContent = { Text(stringResource(R.string.backup_page_auto_result)) },
                        supportingContent = { Text(config.lastAutoBackupResult) },
                        leadingContent = { CardGroupIcon(HugeIcons.Clock01) },
                    )
                }
            }
        }
    }
}
