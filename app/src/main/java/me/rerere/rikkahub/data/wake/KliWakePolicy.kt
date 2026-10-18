package me.rerere.rikkahub.data.wake

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** 确定性随机源（SplitMix64），保证 Theta 与噪声序列可重建。 */
class SplitMix64(seed: Long) {
    private var state: Long = seed

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))

    fun nextGaussian(): Double {
        var u: Double
        var v: Double
        var s: Double
        do {
            u = nextDouble() * 2.0 - 1.0
            v = nextDouble() * 2.0 - 1.0
            s = u * u + v * v
        } while (s >= 1.0 || s == 0.0)
        return u * sqrt(-2.0 * ln(s) / s)
    }
}

/** WakeActivationPolicy v1.0-dev 参数。默认值取自基线文档。 */
data class KliConfig(
    val muDrive: Double = 0.50,
    val driveMin: Double = 0.20,
    val driveMax: Double = 0.80,
    val kRun: Double = 0.10,
    val tauDriveMin: Double = 12.0,
    val muTone: Double = 0.50,
    val toneMin: Double = 0.25,
    val toneMax: Double = 0.75,
    val tauToneMin: Double = 360.0,
    val sigmaTone: Double = 0.10,
    val muDrift: Double = 0.0,
    val driftMin: Double = -0.40,
    val driftMax: Double = 0.40,
    val tauDriftMin: Double = 25.0,
    val sigmaDrift: Double = 0.18,
    val lambdaBase: Double = 0.70,
    val betaDrive: Double = 1.80,
    val betaTone: Double = 1.60,
    val betaDrift: Double = 1.20,
    val lambdaMin: Double = 0.15,
    val lambdaMax: Double = 8.00,
)

/** 可持久化的全部节律状态。 */
data class WakeState(
    val drive: Double = 0.50,
    val tone: Double = 0.50,
    val drift: Double = 0.0,
    val hazard: Double = 0.0,
    val theta: Double = 0.0,
    val cycleId: Long = 0L,
    val entropy: Long = 0L,
)

data class StepResult(val state: WakeState, val woke: Boolean, val lambda: Double)

object KliWakeEngine {

    /** 开一个新 Cycle：重抽一次隐藏门槛 Theta，H 归零。 */
    fun newCycle(seed: Long, prev: WakeState): WakeState {
        val r = SplitMix64(seed)
        var u = r.nextDouble()
        if (u <= 0.0) u = 1e-12
        return prev.copy(hazard = 0.0, theta = -ln(u), cycleId = prev.cycleId + 1, entropy = seed)
    }

    /** 三态随经过时间演化。用 OU 精确离散化，可一次跳算很久。 */
    fun evolve(s: WakeState, deltaMinutes: Double, cfg: KliConfig, rng: SplitMix64): WakeState {
        if (deltaMinutes <= 0.0) return s
        val rD = 2.0.pow(-deltaMinutes / cfg.tauDriveMin)
        val rT = 2.0.pow(-deltaMinutes / cfg.tauToneMin)
        val rX = 2.0.pow(-deltaMinutes / cfg.tauDriftMin)
        val d = (cfg.muDrive + (s.drive - cfg.muDrive) * rD).coerceIn(cfg.driveMin, cfg.driveMax)
        val t = (cfg.muTone + (s.tone - cfg.muTone) * rT +
            cfg.sigmaTone * sqrt(max(0.0, 1.0 - rT * rT)) * rng.nextGaussian())
            .coerceIn(cfg.toneMin, cfg.toneMax)
        val x = (s.drift * rX +
            cfg.sigmaDrift * sqrt(max(0.0, 1.0 - rX * rX)) * rng.nextGaussian())
            .coerceIn(cfg.driftMin, cfg.driftMax)
        return s.copy(drive = d, tone = t, drift = x)
    }

    /** 关机跨度的补算：长间隔分块推进，避免一次跳变吃掉所有随机性。 */
    fun catchUp(s: WakeState, deltaMinutes: Double, cfg: KliConfig, rng: SplitMix64): WakeState {
        if (deltaMinutes <= 0.0) return s
        var cur = s
        var left = deltaMinutes
        while (left > 0.0) {
            val chunk = min(left, 120.0)
            cur = evolve(cur, chunk, cfg, rng)
            left -= chunk
        }
        return cur
    }

    fun lambdaPerHour(s: WakeState, cfg: KliConfig): Double {
        val raw = cfg.lambdaBase * exp(
            cfg.betaDrive * (s.drive - cfg.muDrive) +
                cfg.betaTone * (s.tone - cfg.muTone) +
                cfg.betaDrift * s.drift
        )
        return raw.coerceIn(cfg.lambdaMin, cfg.lambdaMax)
    }

    /** 推进 minutes 分钟：累积 H，越过 Theta 就是一次 Wake Opportunity。 */
    fun step(
        s: WakeState,
        minutes: Double,
        cfg: KliConfig,
        isQuiet: Boolean,
        quietLambda: Double,
        rng: SplitMix64,
    ): StepResult {
        val l = if (isQuiet) quietLambda.coerceIn(0.0, cfg.lambdaMax) else lambdaPerHour(s, cfg)
        val h = s.hazard + l * minutes / 60.0
        return if (h >= s.theta) {
            val kicked = s.copy(drive = max(cfg.driveMin, s.drive - cfg.kRun))
            StepResult(newCycle(rng.nextLong(), kicked), true, l)
        } else {
            StepResult(s.copy(hazard = h), false, l)
        }
    }

    /**
     *估算下一次 Wake 还有多少分钟。
     *只看期望演化、不抽噪声 —— 排程必须稳定，不能每算一次答案都变。
     *返回 -1 表示在 maxLookahead 内不会醒（比如整段都在窗口外）。
     */
    fun nextWakeInMinutes(
        s: WakeState,
        cfg: KliConfig,
        nowMinuteOfDay: Double,
        isQuietAt: (Double) -> Boolean,
        quietLambda: Double,
        activeUntilMinutes: Double,
        maxLookaheadMinutes: Double = 2880.0,
        stepMinutes: Double = 5.0,
    ): Double {
        var h = s.hazard
        var drive = s.drive
        var tone = s.tone
        var drift = s.drift
        var t = 0.0
        var minute = nowMinuteOfDay
        val rD = 2.0.pow(-stepMinutes / cfg.tauDriveMin)
        val rT = 2.0.pow(-stepMinutes / cfg.tauToneMin)
        val rX = 2.0.pow(-stepMinutes / cfg.tauDriftMin)
        while (t < maxLookaheadMinutes && t < activeUntilMinutes) {
            val l = if (isQuietAt(minute)) quietLambda else {
                (cfg.lambdaBase * exp(
                    cfg.betaDrive * (drive - cfg.muDrive) +
                        cfg.betaTone * (tone - cfg.muTone) +
                        cfg.betaDrift * drift
                )).coerceIn(cfg.lambdaMin, cfg.lambdaMax)
            }
            h += l * stepMinutes / 60.0
            if (h >= s.theta) return t + stepMinutes
            drive = cfg.muDrive + (drive - cfg.muDrive) * rD
            tone = cfg.muTone + (tone - cfg.muTone) * rT
            drift *= rX
            t += stepMinutes
            minute = (minute + stepMinutes) % 1440.0
        }
        return -1.0
    }
}
