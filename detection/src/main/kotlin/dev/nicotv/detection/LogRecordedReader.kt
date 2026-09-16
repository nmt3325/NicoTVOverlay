package dev.nicotv.detection

import dev.nicotv.core.DetectionOrigin
import dev.nicotv.core.RecordedObservation
import dev.nicotv.core.StationCatalog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * 画面表示（OSD）に依存せず、テレビ内部ログから録画再生の情報を読む補助経路。
 *
 * - 放送絶対時刻: TunableTvView の timeshiftGetCurrentPositionMs（再生位置が放送時刻で出る）
 * - 放送局: ISDB service_id（ShDbHelper の serviceId / DB の svcId）
 *
 * READ_LOGS は adb で明示的に付与された場合だけ有効。ログ本文は保存しない。
 */
internal object LogRecordedParser {
    private const val POSITION_MARK = "timeshiftGetCurrentPositionMs: current position ="
    private val instant = Regex(
        "[A-Za-z]{3} ([A-Za-z]{3}) ([0-9]{1,2}) ([0-9]{2}):([0-9]{2}):([0-9]{2}) " +
            "GMT([+-])([0-9]{2}):([0-9]{2}) ([0-9]{4})",
    )
    private val decimalService = Regex("serviceId[:=] ?([0-9]{1,5})")
    private val hexService = Regex("svcId=0x([0-9A-Fa-f]{1,4})")
    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** 再生中フレームの放送絶対時刻（ミリ秒）。録画・タイムシフト再生中だけ出力される。 */
    fun positionWallMs(line: String): Long? {
        if (line.length > DetectionLimits.MAX_TEXT_CHARS || !line.contains(POSITION_MARK)) return null
        val match = instant.find(line) ?: return null
        val month = months.indexOf(match.groupValues[1]) + 1
        if (month == 0) return null
        val day = match.groupValues[2].toInt()
        val hour = match.groupValues[3].toInt()
        val minute = match.groupValues[4].toInt()
        val second = match.groupValues[5].toInt()
        val offsetHours = match.groupValues[7].toInt()
        val offsetMinutes = match.groupValues[8].toInt()
        val year = match.groupValues[9].toInt()
        if (offsetHours > 18 || offsetMinutes > 59) return null
        val sign = if (match.groupValues[6] == "-") -1 else 1
        return try {
            OffsetDateTime.of(
                year, month, day, hour, minute, second, 0,
                ZoneOffset.ofTotalSeconds(sign * (offsetHours * 3600 + offsetMinutes * 60)),
            ).toInstant().toEpochMilli()
        } catch (_: RuntimeException) {
            null
        }
    }

    /** 実況ch（jk*）。対応表にないサービスIDは不明として扱う。 */
    fun stationId(line: String): String? {
        if (line.length > DetectionLimits.MAX_TEXT_CHARS) return null
        val serviceId = decimalService.find(line)?.groupValues?.get(1)?.toIntOrNull()
            ?: hexService.find(line)?.groupValues?.get(1)?.toIntOrNull(16)
            ?: return null
        return StationCatalog.findByServiceId(serviceId)?.id
    }
}

/** logcat を1本だけ読み、録画再生の放送日時・放送局を検出バスへ公開する。 */
internal class LogRecordedReader(
    private val nowElapsed: () -> Long,
    private val publish: (RecordedObservation) -> Unit,
    private val spawn: () -> Process = { spawnLogcat() },
) {
    @Volatile private var active = false
    @Volatile private var summary = "TVログ監視は停止中"
    private var worker: Thread? = null
    private var process: Process? = null
    private var configured: String? = null
    private var station: String? = null
    private var anchorWallMs = 0L
    private var anchorAtMs = 0L

    val state: String get() = summary

    fun start(configuredStation: String?) {
        synchronized(this) {
            configured = configuredStation?.takeIf { StationCatalog.find(it) != null }
            if (active) return
            active = true
            station = null
            anchorWallMs = 0L
            summary = "TVログの放送日時を待機中"
            val thread = Thread({ pump() }, THREAD_NAME)
            thread.isDaemon = true
            worker = thread
            thread.start()
        }
    }

    fun stop() {
        synchronized(this) {
            active = false
            process?.destroy()
            process = null
            worker?.interrupt()
            worker = null
            summary = "TVログ監視は停止中"
        }
    }

    private fun pump() {
        var restarts = 0
        while (active && restarts <= MAX_RESTARTS) {
            val current = try {
                spawn()
            } catch (_: Exception) {
                summary = "logcat を起動できません（READ_LOGS 未付与の可能性）"
                return
            }
            synchronized(this) {
                if (!active) {
                    current.destroy()
                    return
                }
                process = current
            }
            try {
                BufferedReader(InputStreamReader(current.inputStream)).use { reader ->
                    while (active) consume(reader.readLine() ?: break)
                }
            } catch (_: Exception) {
                // 読み取りが途切れた場合は下で再接続する
            } finally {
                current.destroy()
            }
            if (!active) return
            restarts += 1
            try {
                Thread.sleep(RESTART_DELAY_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
        if (active) summary = "TVログを繰り返し読めないため停止しました"
    }

    /** 放送時刻が想定からずれた時（頭出し・シーク・番組切替）だけ再アンカーする。 */
    private fun consume(line: String) {
        LogRecordedParser.stationId(line)?.let { station = it }
        val wall = LogRecordedParser.positionWallMs(line) ?: return
        val now = nowElapsed()
        val drift = if (anchorWallMs == 0L) Long.MAX_VALUE else abs(wall - (anchorWallMs + (now - anchorAtMs)))
        if (drift > DRIFT_TOLERANCE_MS) {
            anchorWallMs = wall
            anchorAtMs = now
        }
        val chosen = station ?: configured
        val detail = when {
            station != null -> "TVログから放送日時と放送局を自動取得（画面表示は不要）"
            chosen != null -> "TVログから放送日時を自動取得（放送局は設定）"
            else -> "TVログの放送局を特定できません。設定で放送局を選んでください"
        }
        summary = "anchor=$anchorWallMs station=$chosen"
        publish(RecordedObservation(chosen, anchorWallMs, 0L, DetectionOrigin.ACCESSIBILITY, now, detail))
    }

    private companion object {
        const val THREAD_NAME = "nicotv-log-evidence"
        const val MAX_RESTARTS = 8
        const val RESTART_DELAY_MS = 2_000L
        const val DRIFT_TOLERANCE_MS = 5_000L

        fun spawnLogcat(): Process = ProcessBuilder(
            listOf(
                "logcat", "-v", "brief", "-T", "1",
                "TunableTvView(MainView):D", "[DTVBG]ShDbHelper:I", "DB:W", "*:S",
            ),
        ).redirectErrorStream(true).start()
    }
}
