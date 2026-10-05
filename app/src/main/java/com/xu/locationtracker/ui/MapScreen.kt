package com.xu.locationtracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xu.locationtracker.data.Prefs
import com.xu.locationtracker.data.ReplayModel
import com.xu.locationtracker.data.dayKeyOf
import com.xu.locationtracker.util.Gcj
import com.xu.locationtracker.util.fmtDur
import com.xu.locationtracker.util.geoDistM
import com.xu.locationtracker.util.lastFixAgeText
import com.xu.locationtracker.util.trackStats
import kotlinx.coroutines.delay
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SOURCE_TRACK = "track-line"
private const val LAYER_TRACK = "track-line-layer"
private const val SOURCE_REPLAY_BASE = "replay-base"
private const val LAYER_REPLAY_BASE = "replay-base-layer"
private const val SOURCE_NOW = "now-point"
private const val LAYER_NOW = "now-point-layer"

private val SPEEDS = intArrayOf(1, 2, 4, 8, 16, 32)
private const val COLOR_TODAY = "#1565D8"
private const val COLOR_REPLAY = "#E67E22"
private const val COLOR_REPLAY_BASE = "#B7B7B7"

private class MapHolder {
    var map: MapLibreMap? = null
    var style: Style? = null
    var cameraMoved = false
    var replayShownDay: String? = null
    var lastSegIdx = -1
    /** 线尾当前绘制的插值位置；null 表示尚未绘制 */
    var lastTail: Pair<Double, Double>? = null
}

@Composable
fun MapScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val points by vm.points.collectAsStateWithLifecycle()
    val historyPoints by vm.historyPoints.collectAsStateWithLifecycle()
    val viewDay by vm.viewDay.collectAsStateWithLifecycle()
    val lastFixAt by vm.lastFixAt.collectAsStateWithLifecycle()
    val lastFixLoc by vm.lastFixLoc.collectAsStateWithLifecycle()
    val isRecording by vm.isRecording.collectAsStateWithLifecycle()
    val isStatic by vm.isStatic.collectAsStateWithLifecycle()

    val holder = remember { MapHolder() }
    var mapReady by remember { mutableStateOf(false) }
    val styleSpec = remember { MapStyles.styleSpec(context) }

    // 底图坐标转换：高德底图（GCJ-02）需 WGS→GCJ 纠偏；OSM 底图（WGS-84）直接使用
    fun toBase(lat: Double, lon: Double): Pair<Double, Double> =
        if (styleSpec.gcj) Gcj.wgsToGcj(lat, lon) else lat to lon

    val manualRec = remember { mutableStateOf(Prefs.manualRecording) }

    // ---------- 回放播放器状态 ----------
    var playing by remember(viewDay) { mutableStateOf(false) }
    var speedIdx by remember { mutableIntStateOf(2) } // 默认 4x
    var elapsed by remember(viewDay) { mutableDoubleStateOf(0.0) }
    var simTimeMs by remember(viewDay) { mutableLongStateOf(0L) }

    val replayGcj = remember(viewDay, historyPoints, styleSpec.gcj) {
        if (viewDay == null) emptyList() else historyPoints.map { toBase(it.lat, it.lon) }
    }
    val replayModel = remember(viewDay, replayGcj) {
        if (viewDay == null || replayGcj.size < 2) null
        else ReplayModel.from(replayGcj, historyPoints.map { it.t }.toLongArray())
    }

    val displayPoints = if (viewDay == null) points else historyPoints
    val (distM, durMs, count) = remember(displayPoints) { trackStats(displayPoints) }

    Box(modifier.fillMaxSize()) {
        MapLibreView(
            modifier = Modifier.fillMaxSize(),
            styleUrl = styleSpec.url,
            onReady = { map, style ->
                holder.map = map
                holder.style = style
                mapReady = true
            },
        )

        // ---------- 今日实时渲染 ----------
        LaunchedEffect(points, viewDay, mapReady) {
            if (viewDay != null) return@LaunchedEffect
            val style = holder.style
            if (!mapReady || style == null) return@LaunchedEffect
            val gcj = points.map { p -> toBase(p.lat, p.lon) }

            if (gcj.isEmpty()) {
                style.getSourceAs<GeoJsonSource>(SOURCE_TRACK)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            } else {
                style.getSourceAs<GeoJsonSource>(SOURCE_TRACK)?.setGeoJson(
                    LineString.fromLngLats(gcj.map { Point.fromLngLat(it.second, it.first) })
                )
            }
            style.getLayerAs<LineLayer>(LAYER_TRACK)?.setProperties(PropertyFactory.lineColor(COLOR_TODAY))
            style.getSourceAs<GeoJsonSource>(SOURCE_REPLAY_BASE)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            style.getLayerAs<CircleLayer>(LAYER_NOW)?.setProperties(PropertyFactory.circleColor(COLOR_TODAY))

            if (gcj.isNotEmpty()) {
                val last = gcj.last()
                style.getSourceAs<GeoJsonSource>(SOURCE_NOW)?.setGeoJson(
                    Feature.fromGeometry(Point.fromLngLat(last.second, last.first))
                )
                if (!holder.cameraMoved) {
                    holder.cameraMoved = true
                    holder.map?.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(last.first, last.second), 16.0), 800
                    )
                }
            }
        }

        // ---------- 回放：静态部分（进入日期时一次） ----------
        LaunchedEffect(replayModel, mapReady) {
            // 进入回放 / 地图重建后重置绘制状态：线尾从第 0 段重新跟随圆点
            holder.lastSegIdx = -1
            holder.lastTail = null
            val style = holder.style
            if (viewDay != null && mapReady && style != null && replayModel != null) {
                style.getSourceAs<GeoJsonSource>(SOURCE_REPLAY_BASE)?.setGeoJson(
                    LineString.fromLngLats(replayModel.gcj.map { Point.fromLngLat(it.second, it.first) })
                )
                style.getLayerAs<LineLayer>(LAYER_TRACK)?.setProperties(PropertyFactory.lineColor(COLOR_REPLAY))
                style.getLayerAs<CircleLayer>(LAYER_NOW)?.setProperties(PropertyFactory.circleColor(COLOR_REPLAY))
                if (holder.replayShownDay != viewDay) {
                    holder.replayShownDay = viewDay
                    val cLat = replayModel.gcj.sumOf { it.first } / replayModel.gcj.size
                    val cLon = replayModel.gcj.sumOf { it.second } / replayModel.gcj.size
                    holder.map?.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(cLat, cLon), 15.5), 800
                    )
                }
            }
        }

        // ---------- 回放：播放时钟 ----------
        LaunchedEffect(viewDay, playing, speedIdx, replayModel) {
            if (viewDay == null || !playing || replayModel == null) return@LaunchedEffect
            var lastNs = System.nanoTime()
            while (true) {
                delay(40)
                val nowNs = System.nanoTime()
                val dt = (nowNs - lastNs) / 1_000_000_000.0
                lastNs = nowNs
                elapsed = (elapsed + dt * SPEEDS[speedIdx]).coerceAtMost(replayModel.totalSec)
                if (elapsed >= replayModel.totalSec) {
                    playing = false
                    break
                }
            }
        }

        // ---------- 回放：按帧绘制标记/走过的线 ----------
        LaunchedEffect(elapsed, viewDay, mapReady, replayModel) {
            val style = holder.style
            if (viewDay == null || replayModel == null || !mapReady || style == null) return@LaunchedEffect
            val (idx, frac, tNow) = replayModel.locate(elapsed)
            val (lat, lon) = replayModel.posAt(idx, frac)

            style.getSourceAs<GeoJsonSource>(SOURCE_NOW)?.setGeoJson(
                Feature.fromGeometry(Point.fromLngLat(lon, lat))
            )
            simTimeMs = tNow

            // 线尾跟随圆点：idx 变化（跨过记录点）或线尾已移动 >0.5 m（长段内插值时）都重画，
            // 避免圆点沿长段（如 GPS 断档）移动时线尾停在上一帧末尾、两者分离
            val tail = lat to lon
            val tailMoved = holder.lastTail == null || geoDistM(holder.lastTail!!, tail) > 0.5
            if (idx != holder.lastSegIdx || tailMoved) {
                holder.lastSegIdx = idx
                holder.lastTail = tail
                val walked = replayModel.gcj.take(idx + 1) + listOf(tail)
                style.getSourceAs<GeoJsonSource>(SOURCE_TRACK)?.setGeoJson(
                    LineString.fromLngLats(walked.map { Point.fromLngLat(it.second, it.first) })
                )
            }
        }

        // ---------- 顶栏 ----------
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            val day = viewDay
            if (day == null) {
                StatusCard(
                    isRecording = isRecording,
                    isStatic = isStatic,
                    distM = distM,
                    durMs = durMs,
                    count = count,
                    lastFixAt = lastFixAt,
                )
            } else {
                ReplayTopCard(
                    day = day,
                    distM = distM,
                    durMs = durMs,
                    count = count,
                    onClose = { vm.showDay(null) },
                )
            }
        }

        // ---------- 底栏 ----------
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (viewDay != null) {
                if (replayModel != null) {
                    ReplayControls(
                        playing = playing,
                        onToggle = { playing = !playing },
                        elapsed = elapsed,
                        total = replayModel.totalSec,
                        onSeek = {
                            // 拖动进度条时让线尾立即跟到新位置
                            holder.lastSegIdx = -1
                            holder.lastTail = null
                            elapsed = it
                        },
                        speed = SPEEDS[speedIdx],
                        onSpeed = { speedIdx = (speedIdx + 1) % SPEEDS.size },
                        simTimeMs = simTimeMs,
                    )
                }
            } else {
                // 今日模式：记录操作按钮相对全屏居中（与底部"历史"Tab 同一竖向），定位圆钮在右端
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (Prefs.mode == Prefs.MODE_MANUAL) {
                        // 手动模式：开始记录 / 停止记录（无暂停）
                        if (isRecording) {
                            Button(onClick = {
                                manualRec.value = false
                                vm.stopManual()
                            }) { Text("停止记录") }
                        } else {
                            Button(onClick = {
                                if (!manualRec.value) manualRec.value = true
                                vm.startManual()
                            }) { Text("开始记录") }
                        }
                    } else {
                        val stoppedToday = Prefs.autoStoppedForDay ==
                            dayKeyOf(System.currentTimeMillis())
                        if (stoppedToday) {
                            Button(onClick = { vm.resumeTodayAuto() }) { Text("恢复今日记录") }
                        } else if (isRecording) {
                            Button(onClick = { vm.endTodayAuto() }) { Text("结束今日记录") }
                        } else {
                            Button(onClick = { vm.autoStartIfNeeded() }) { Text("启动今日记录") }
                        }
                    }
                    FilledIconButton(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        onClick = {
                            // 定位到最新可用 fix（WGS-84 → 底图坐标，自动适配 GCJ/WGS 底图）
                            var target: Pair<Double, Double>? = lastFixLoc
                            if (target == null && displayPoints.isNotEmpty()) {
                                target = toBase(displayPoints.last().lat, displayPoints.last().lon)
                            }
                            if (target != null) {
                                holder.map?.animateCamera(
                                    CameraUpdateFactory.newLatLngZoom(
                                        LatLng(target.first, target.second), 16.0
                                    ), 500
                                )
                            }
                        },
                    ) {
                        Icon(Icons.Filled.MyLocation, contentDescription = "回到当前位置")
                    }
                }
            }
            Spacer(Modifier.size(6.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// 顶栏卡片

@Composable
private fun StatusCard(
    isRecording: Boolean,
    isStatic: Boolean,
    distM: Double,
    durMs: Long,
    count: Int,
    lastFixAt: Long,
) {
    val statusText = when {
        !isRecording -> "未在记录"
        isStatic -> "记录中 · 静止省电"
        else -> "记录中 · 行动中"
    }
    val color = when {
        !isRecording -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(color)
                Text("  $statusText", style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            StatsLine(distM, durMs, count, lastFixAt)
        }
    }
}

@Composable
private fun ReplayTopCard(
    day: String,
    distM: Double,
    durMs: Long,
    count: Int,
    onClose: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(MaterialTheme.colorScheme.secondary)
                Text(
                    "  历史回放 · $day",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "返回今天")
                }
            }
            HorizontalDivider()
            StatsLine(distM, durMs, count)
        }
    }
}

@Composable
private fun Dot(color: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color,
        modifier = Modifier.size(width = 10.dp, height = 10.dp),
    ) {}
}

@Composable
private fun StatsLine(distM: Double, durMs: Long, count: Int, lastFixAt: Long = 0) {
    val base = String.format(Locale.getDefault(), "%.2f km · %s · %d 点", distM / 1000.0, fmtDur(durMs), count)
    val text = buildAnnotatedString {
        append(base)
        addStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant), 0, base.length)
        if (lastFixAt > 0) {
            val ageSec = (System.currentTimeMillis() - lastFixAt) / 1000
            append(" · 上次定位 ${lastFixAgeText(ageSec)}")
            if (ageSec >= 60) append(" ⚠")
            addStyle(
                SpanStyle(color = if (ageSec >= 60) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant),
                base.length,
                length,
            )
        }
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

// ---------------------------------------------------------------------------
// 回放控制条

@Composable
private fun ReplayControls(
    playing: Boolean,
    onToggle: () -> Unit,
    elapsed: Double,
    total: Double,
    onSeek: (Double) -> Unit,
    speed: Int,
    onSpeed: () -> Unit,
    simTimeMs: Long,
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Slider(
                value = if (total > 0) (elapsed / total).toFloat() else 0f,
                onValueChange = { v -> onSeek(v.toDouble() * total) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggle) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "暂停" else "播放",
                    )
                }
                Text(
                    if (simTimeMs > 0) timeFmt.format(Date(simTimeMs)) else "--:--:--",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.width(8.dp))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSpeed) { Text("${speed}x") }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// MapLibre 视图

@Composable
fun MapLibreView(
    modifier: Modifier = Modifier,
    styleUrl: String,
    onReady: (MapLibreMap, Style) -> Unit,
) {
    val context = LocalContext.current
    val readyCb = rememberUpdatedState(onReady)
    val mapView = remember { MapView(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        var destroyed = false
        fun destroy() {
            if (!destroyed) {
                destroyed = true
                mapView.onDestroy()
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> destroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // 重新进入该页时 Activity 已 RESUMED，事件不会重发，需按当前状态补齐
        val current = lifecycleOwner.lifecycle.currentState
        if (current.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (current.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            destroy()
        }
    }

    AndroidView(
        factory = { _ ->
            mapView.getMapAsync { map ->
                map.setStyle(styleUrl) { style ->
                    setupLayers(style)
                    readyCb.value(map, style)
                }
            }
            mapView
        },
        modifier = modifier,
        update = {},
    )
}

/** 注册图层（幂等）。顺序：回放灰色底 -> 轨迹线 -> 当前位置点 */
private fun setupLayers(style: Style) {
    if (style.getSource(SOURCE_REPLAY_BASE) == null) {
        style.addSource(GeoJsonSource(SOURCE_REPLAY_BASE, FeatureCollection.fromFeatures(emptyList())))
        style.addLayer(
            LineLayer(LAYER_REPLAY_BASE, SOURCE_REPLAY_BASE).withProperties(
                PropertyFactory.lineColor(COLOR_REPLAY_BASE),
                PropertyFactory.lineWidth(4f),
                PropertyFactory.lineOpacity(0.55f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
    }
    if (style.getSource(SOURCE_TRACK) == null) {
        style.addSource(GeoJsonSource(SOURCE_TRACK, FeatureCollection.fromFeatures(emptyList())))
        style.addLayer(
            LineLayer(LAYER_TRACK, SOURCE_TRACK).withProperties(
                PropertyFactory.lineColor(COLOR_TODAY),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineOpacity(0.92f),
            )
        )
    }
    if (style.getSource(SOURCE_NOW) == null) {
        style.addSource(GeoJsonSource(SOURCE_NOW, FeatureCollection.fromFeatures(emptyList())))
        style.addLayer(
            CircleLayer(LAYER_NOW, SOURCE_NOW).withProperties(
                PropertyFactory.circleColor(COLOR_TODAY),
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f),
            )
        )
    }
}