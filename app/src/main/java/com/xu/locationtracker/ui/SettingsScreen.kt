package com.xu.locationtracker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xu.locationtracker.data.Prefs
import com.xu.locationtracker.data.dayKeyOf

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(Prefs.mode) }

    // 参数本地状态（编辑后写回 Prefs 并通知服务）
    var accuracy by remember { mutableIntStateOf(Prefs.filterAccuracyM) }
    var fastInt by remember { mutableIntStateOf(Prefs.fastIntervalSec) }
    var minDist by remember { mutableIntStateOf(Prefs.minDistM) }
    var staticAfter by remember { mutableIntStateOf(Prefs.staticAfterMin) }
    var staticInt by remember { mutableIntStateOf(Prefs.staticIntervalSec) }
    var gpsSilent by remember { mutableIntStateOf(Prefs.gpsSilentAfterMin) }
    var keepAlive by remember { mutableIntStateOf(Prefs.keepAliveMin) }
    var showAdvanced by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------- 记录模式 ----------
        SectionCard("记录模式") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == Prefs.MODE_AUTO, onClick = {
                    mode = Prefs.MODE_AUTO
                    vm.setMode(Prefs.MODE_AUTO)
                })
                Text("全天自动记录")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == Prefs.MODE_MANUAL, onClick = {
                    mode = Prefs.MODE_MANUAL
                    vm.setMode(Prefs.MODE_MANUAL)
                })
                Text("手动开始/停止")
            }
        }

        // ---------- 记录参数 ----------
        SectionCard("记录参数") {
            // 主参数：轨迹密度 + 采样频率（用户最常调的两项）
            ParamRow("记录触发位移", "米", minDist, 3..200) {
                minDist = it; Prefs.minDistM = it; vm.applyParams()
            }
            ParamRow("采样间隔", "秒", fastInt, 3..600) {
                fastInt = it; Prefs.fastIntervalSec = it; vm.applyParams()
            }
            Text(
                "位移阈值 = 轨迹密度（越小越精细）；采样间隔 = 定位频率与耗电。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            // 高级参数：默认折叠，普通使用无需调整
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showAdvanced = !showAdvanced }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (showAdvanced) "▾ 高级参数" else "▸ 高级参数",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (showAdvanced) {
                ParamRow("静止判定时间", "分钟", staticAfter, 1..120) {
                    staticAfter = it; Prefs.staticAfterMin = it; vm.applyParams()
                }
                ParamRow("静止采样间隔", "秒", staticInt, 10..600) {
                    staticInt = it; Prefs.staticIntervalSec = it; vm.applyParams()
                }
                ParamRow("GPS 失效进静止", "分钟", gpsSilent, 1..60) {
                    gpsSilent = it; Prefs.gpsSilentAfterMin = it; vm.applyParams()
                }
                ParamRow("在位心跳", "分钟", keepAlive, 0..120) {
                    keepAlive = it; Prefs.keepAliveMin = it; vm.applyParams()
                }
                ParamRow("精度过滤", "米", accuracy, 5..200) {
                    accuracy = it; Prefs.filterAccuracyM = it; vm.applyParams()
                }
                Text(
                    "静止时自动降频省电（判定时长/静止采样间隔）；心跳保证静止时每 N 分钟留一个点（0=关闭）；精度过滤丢弃 acc 超标的定位。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        // ---------- 导出 ----------
        SectionCard("数据") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    vm.exportGpx { uri ->
                        toast(context, if (uri != null) "GPX 已导出到 下载/行程轨迹" else "没有可导出的数据")
                    }
                }) { Text("导出 GPX") }
                Button(onClick = {
                    vm.exportCsv { uri ->
                        toast(context, if (uri != null) "CSV 已导出到 下载/行程轨迹" else "没有可导出的数据")
                    }
                }) { Text("导出 CSV") }
            }
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { confirmDelete = true },
                modifier = Modifier.padding(top = 4.dp),
            ) { Text("清空全部轨迹数据") }
        }

        // ---------- 系统 ----------
        SectionCard("后台与权限") {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val exempted = pm.isIgnoringBatteryOptimizations(context.packageName)
            if (exempted) {
                Text("✓ 已在电池优化白名单", color = MaterialTheme.colorScheme.primary)
            } else {
                Button(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }.onFailure {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            )
                        }
                    }
                }) { Text("加入电池优化白名单") }
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                }
            }) { Text("应用权限设置") }
            Spacer(Modifier.height(4.dp))
            Text(
                "提醒：在最近任务里上滑关闭应用会彻底停止记录，请改用返回桌面或息屏。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Text(
            "行程轨迹 v0.1.0 · ${dayKeyOf(System.currentTimeMillis())}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("清空全部轨迹数据？") },
            text = { Text("将删除所有日期的轨迹文件，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteAll { toast(context, "已清空") }
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ParamRow(
    label: String,
    unit: String,
    value: Int,
    range: IntRange,
    onConfirm: (Int) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value.toString()) }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                text = value.toString()
                editing = true
            }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            if (value == 0 && unit == "分钟" && label == "在位心跳") "关闭" else "$value $unit",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("$label（${range.first}-${range.last}）") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { t -> text = t.filter { it.isDigit() } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    suffix = { Text(unit) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = text.toIntOrNull()
                    if (v != null && v in range) {
                        onConfirm(v)
                        editing = false
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            content()
        }
    }
}

private fun toast(context: Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
}