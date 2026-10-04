# 行程轨迹 (LocationTracker)

一个为国内环境设计的**离线轨迹记录 Android 应用**：无 GMS 依赖、原生 `LocationManager` 双源定位、高德瓦片底图（GCJ-02 坐标纠偏）、按日 JSONL 存储、自带历史回放与 GPX/CSV 导出。

> 适用场景：无 Google 服务的国产手机（华为/小米等）、校园/园区轨迹记录、电量敏感的长时记录。

---

## 功能特性

- **定位与记录**
  - GPS + 网络双源定位，无 GMS/华为 HMS 依赖
  - 移动触发保存（位移 ≥ 阈值才记录，天然过滤 GPS 抖动）
  - 静止自动降频省电，移动立即恢复；静止期间按"在位心跳"留点（默认 5 分钟一个）
  - 精度过滤（默认 50 m）、双源去重（4 秒内 3 米内视为同一位置）
- **地图与坐标**
  - 高德在线瓦片底图；支持 `mbtiles` 离线底图包（10–18 级，全国/单城市可裁剪）
  - WGS-84 → GCJ-02 火星坐标转换（`util/Gcj.kt`，公开算法）
- **历史回放**
  - 按日查看轨迹，时间轴压缩（静止间隔最多记 30 秒）
  - 1x–32x 变速播放、进度条拖动、实时模拟时间显示
  - 轨迹线尾跟随当前位置标记（含长断档插值场景）
- **导出与数据**
  - 一键导出 GPX / CSV 到"下载/行程轨迹"（Android 10+ 无需权限）
  - 数据为纯文本 JSONL，直接 `adb pull` 可拷贝
- **通知与诊断**
  - 前台服务常驻通知显示里程/时长/点数/**"上次定位 x 秒前"**（超 60 秒变灰警示）
  - 地图页顶部卡片同步显示定位新鲜度
- **后台与自启**
  - `foregroundServiceType="location"` 前台服务 + 开机自启接收器
  - 支持一键申请电池优化白名单

## 技术栈

| 组件 | 说明 |
|---|---|
| Kotlin 2.0 / Compose Material3 | UI |
| MapLibre Android 11 | 地图渲染（原生，非 GMS） |
| LocationManager | GPS + NETWORK 双源定位 |
| kotlinx.coroutines / StateFlow | 服务与 UI 状态共享 |
| 共享偏好 + 按日 JSONL 文件 | 配置与轨迹存储 |

## 项目结构

```
app/src/main/java/com/xu/locationtracker/
├── LocationApp.kt          (35)  Application 初始化：Pre 配置/Store/MapLibre/通知渠道
├── MainActivity.kt         (17)  Compose 入口
├── data/                         存储与领域模型
│   ├── TrackPoint.kt      (49)   JSONL 行模型 + dayKeyOf
│   ├── TrackStore.kt      (75)   按日 JSONL 读写（Mutex 串行）
│   ├── ReplayModel.kt     (50)   回放时间轴压缩与插值（纯逻辑，可单测）
│   ├── Prefs.kt           (83)   设置项封装
│   └── TrackerState.kt    (34)   进程内共享 StateFlow（服务写、UI 读）
├── service/                      后台
│   ├── TrackingService.kt (305)  前台服务：定位订阅 + 保存策略 + 通知
│   ├── SavePolicy.kt      (17)   去重/保存/静止判定（纯逻辑，可单测）
│   └── BootReceiver.kt    (29)   开机自启
├── ui/                            界面
│   ├── MainScreen.kt      (78)   Scaffold + 底部导航
│   ├── MapScreen.kt       (553)  地图 + 回放 + 状态卡片
│   ├── HistoryScreen.kt   (71)   历史日期列表
│   ├── SettingsScreen.kt  (266)  设置
│   ├── MainViewModel.kt   (163)  ViewModel
│   └── MapStyles.kt       (77)   在线/离线样式
└── util/
    ├── Gcj.kt             (46)   WGS-84 → GCJ-02
    ├── Exporter.kt        (107)  GPX/CSV 导出
    ├── Stats.kt           (20)   轨迹统计 / 球面距离
    └── Fmt.kt             (20)   时长/定位新鲜度文案
```

## 构建与安装

```bash
# 需要 Android SDK（local.properties 中 sdk.dir 指向本机 SDK）
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 运行单元测试（纯逻辑，无需设备）
./gradlew :app:testDebugUnitTest
```

运行测试输出示例：

```
GcjTest          5 tests, 0 failures
ReplayModelTest  6 tests, 0 failures
SavePolicyTest   4 tests, 0 failures
```

## 数据存储

| 内容 | 位置 |
|---|---|
| 轨迹点（JSONL，每天一个文件） | `/sdcard/Android/data/com.xu.locationtracker/files/tracks/yyyy-MM-dd.jsonl` |
| 离线地图 | `/sdcard/Android/data/com.xu.locationtracker/files/maps/shanghai.mbtiles` |
| 导出文件 | 系统"下载/行程轨迹/" |

单行格式：`{"t":毫秒时间戳,"lat":纬度,"lon":经度,"acc":精度米,"spd":速度m/s}`

## 离线地图（可选）

应用默认使用高德在线瓦片。需要离线底图时：

```bash
# 生成上海 10-18 级离线包（无网络瓦片缓存时用）
python3 tools/download_tiles.py maps/shanghai.mbtiles

# 推送到手机后重启应用
adb push maps/shanghai.mbtiles /sdcard/Android/data/com.xu.locationtracker/files/maps/
```

## 设备后台注意事项（华为等国产 ROM）

国产 ROM 对后台应用管控严格，无 GMS 时尤其需要手动放行。本项目已做的兜底：

- 前台服务（`foregroundServiceType="location"`）+ 电池优化白名单申请；
- 定位订阅参数（`minDistance=0`）保证静止时系统仍按间隔回调，让"在位心跳"可用；
- 已知限制（建议在设置里手动处理）：
  - 华为"应用启动管理"需允许**自启动/关联启动/后台活动**，否则被杀后开机自启与回调都会静默失败；
  - 定位权限在华为上选择"始终允许"（不要选"仅使用期间"）；
  - 若历史轨迹频繁出现长断档，先看状态栏通知的"上次定位 x 秒前"确认是否为系统管控断流。

## 常见问题

- **回放时位置标记与轨迹线分离** → 已修复：线尾现在跟随标记（段内位移 > 0.5 m 即重绘），拖动进度条/重建地图时同步重置。
- **静止时长时间没有新点** → 心跳依赖系统回调，若通知显示"上次定位"一直在刷新但没有新点，通常是 ROM 冻结了进程。
- **轨迹出现直线大跳变** → 表示两记录点之间无可用定位（如电梯、楼内），是数据缺失的真实表现，非渲染错误。

## 许可

仅供个人学习使用。地图瓦片版权归高德；轨迹数据归记录者本人。