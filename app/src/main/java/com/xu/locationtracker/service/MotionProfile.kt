package com.xu.locationtracker.service

/**
 * 运动档位：用（平滑后的）速度把"移动中"细分为几档，驱动采样间隔。
 * 纯逻辑、无 Android 依赖，可单测。
 *
 * 设计约定（见重构方案）：
 *  - 速度来源：`loc.speed`（多普勒）优先；缺失时用相邻 fix 的位置差分（限幅 [SPEED_CAP]）
 *  - 平滑：EMA([EMA_ALPHA])，抑制 GPS 抖动
 *  - 抽格：迟滞 ±[HYSTERESIS] + 连续 [CONFIRM_FIXES] 个 fix 确认，避免边界抖动
 *  - 静止降频不在这里：静止由"位移 < 10m 持续 5min"判定（维度 B，保持原语义），
 *    采样间隔另走 staticInterval；本对象只决定"移动中"的采样密度
 */
object MotionProfile {

    /** 记录精度预设（设置项）：决定移动中采样间隔的一档 */
    enum class Quality(val label: String) {
        ECO("省电"),
        STANDARD("标准"),
        FINE("精细"),
    }

    /** 运动档位；label 供地图页/通知的状态文案使用 */
    enum class Level(val label: String) {
        STATIC("静止"),
        WALK("步行"),
        RIDE("骑行"),
        DRIVE("驾车"),
        HIGH_SPEED("高速"),
    }

    // 速度分档边界（m/s）：静止|步行|骑行|驾车|高速
    private const val BOUND_STATIC = 0.5f
    private const val BOUND_WALK = 2.5f
    private const val BOUND_RIDE = 8f
    private const val BOUND_DRIVE = 30f

    private const val HYSTERESIS = 0.15f   // 迟滞比例（±15%）
    private const val CONFIRM_FIXES = 3    // 连续确认次数
    private const val EMA_ALPHA = 0.4f     // 速度平滑系数
    const val SPEED_CAP = 120f             // 差分速度限幅（m/s）

    /**
     * 采样间隔（秒），顺序 [WALK, RIDE, DRIVE, HIGH_SPEED]。
     * 硬下限 3s：`requestLocationUpdates(3000ms)` 是系统最小间隔，点数可控。
     */
    private val INTERVALS = mapOf(
        Quality.ECO to intArrayOf(15, 10, 5, 5),
        Quality.STANDARD to intArrayOf(10, 6, 3, 3),
        Quality.FINE to intArrayOf(5, 3, 3, 3),
    )

    /** 字符串配置 -> 预设（非法值回退标准档） */
    fun qualityOf(value: String?): Quality =
        Quality.entries.find { it.name.equals(value, ignoreCase = true) } ?: Quality.STANDARD

    /**
     * 速度 -> 档位（不含迟滞）。
     * 注意 STATIC 档在调用侧等价于步行间隔：真正的静止降频由 staticInterval 覆盖。
     */
    fun classify(speedMps: Float): Level = when {
        speedMps < BOUND_STATIC -> Level.STATIC
        speedMps < BOUND_WALK -> Level.WALK
        speedMps < BOUND_RIDE -> Level.RIDE
        speedMps < BOUND_DRIVE -> Level.DRIVE
        else -> Level.HIGH_SPEED
    }

    /** 采样间隔（毫秒）。STATIC 沿用 WALK 值（静止时由调用方使用 staticIntervalMs） */
    fun intervalMs(level: Level, quality: Quality): Long {
        val arr = INTERVALS.getValue(quality)
        val idx = when (level) {
            Level.STATIC, Level.WALK -> 0
            Level.RIDE -> 1
            Level.DRIVE -> 2
            Level.HIGH_SPEED -> 3
        }
        return arr[idx] * 1000L
    }

    fun intervalSec(level: Level, quality: Quality): Int = (intervalMs(level, quality) / 1000).toInt()

    /**
     * 有状态跟踪器：维护 EMA 速度、当前档位、待确认档位计数。
     * 每次收到一个通过精度闸门的可用 fix 调用 [onFix]，返回当前档位。
     */
    class Tracker {
        var level: Level = Level.STATIC
            private set

        /** 当前平滑速度（m/s），供毛刺判定等使用；未收到 fix 时为 0 */
        var speedMps: Float = 0f
            private set

        private var ema: Float? = null
        private var pending: Level? = null
        private var pendingCount = 0

        fun reset() {
            level = Level.STATIC
            speedMps = 0f
            ema = null
            pending = null
            pendingCount = 0
        }

        /**
         * @param distM 与上一个可用 fix 的位移（米）；无上一个 fix 时传 null
         * @param dtMs 与上一个可用 fix 的时间差（毫秒）
         * @param reportedSpeed `loc.hasSpeed() ? loc.speed : null`
         */
        fun onFix(distM: Float?, dtMs: Long, reportedSpeed: Float?): Level {
            val raw = when {
                reportedSpeed != null && reportedSpeed >= 0f -> reportedSpeed
                distM != null && dtMs > 0 -> distM / (dtMs / 1000f)
                else -> 0f
            }.coerceIn(0f, SPEED_CAP)

            val prev = ema
            val smoothed = if (prev == null) raw else prev + EMA_ALPHA * (raw - prev)
            ema = smoothed
            speedMps = smoothed

            val target = classifyWithHysteresis(smoothed, level)
            if (target == level) {
                pending = null
                pendingCount = 0
            } else {
                if (pending == target) pendingCount++ else { pending = target; pendingCount = 1 }
                if (pendingCount >= CONFIRM_FIXES) {
                    level = target
                    pending = null
                    pendingCount = 0
                }
            }
            return level
        }

        /** 迟滞：升档需超过当前档上界 +15%，降档需低于当前档下界 -15% */
        private fun classifyWithHysteresis(speed: Float, current: Level): Level {
            val raw = classify(speed)
            if (raw == current) return current
            return if (raw.ordinal > current.ordinal) {
                if (speed >= boundUpper(current) * (1 + HYSTERESIS)) raw else current
            } else {
                if (speed <= boundLower(current) * (1 - HYSTERESIS)) raw else current
            }
        }

        private fun boundUpper(level: Level): Float = when (level) {
            Level.STATIC -> BOUND_STATIC
            Level.WALK -> BOUND_WALK
            Level.RIDE -> BOUND_RIDE
            Level.DRIVE -> BOUND_DRIVE
            Level.HIGH_SPEED -> Float.MAX_VALUE
        }

        private fun boundLower(level: Level): Float = when (level) {
            Level.STATIC -> 0f
            Level.WALK -> BOUND_STATIC
            Level.RIDE -> BOUND_WALK
            Level.DRIVE -> BOUND_RIDE
            Level.HIGH_SPEED -> BOUND_DRIVE
        }
    }
}
