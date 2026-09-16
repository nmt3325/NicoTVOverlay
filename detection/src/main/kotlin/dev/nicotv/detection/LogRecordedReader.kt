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
 * 画面表示（OSD）に依存せず、テレビ内部ログから録画再生の情報を読む経路。
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

/**
 * logcat を1本だけ長く保ち、録画再生の放送日時・放送局を検出バスへ公開する。
 *
 * Android 14 では logcat を開くたびに「すべてのデバイスログへのアクセスを許可しますか？」の確認が出る。
 * 許可は1回限りなので、無音のまま待ち続けても読めるようにはならない。
 * 読めている1本は絶対に手放さず、1行も来ないまま一定時間が過ぎた時だけ作り直して確認を出し直す
 * （確認はユーザー補助側が自動で承認する）。
 */
internal class LogRecordedReader(
    private val nowElapsed: () -> Long,
    private val publish: (RecordedObservation) -> Unit,
    private val spawn: () -> Process = { spawnLogcat() },
) {
    @Volatile private var active = false
    @Volatile private var lines = 0L
    @Volatile private var matched = 0L
    @Volatile private var restarts = 0
    @Volatile private var failure: String? = null
    @Volatile private var startedAt = 0L
    @Volatile private var configured: String? = null
    @Volatile private var station: String? = null
    @Volatile private var anchorWallMs = 0L
    private var anchorAtMs = 0L
    private var worker: Thread? = null
    private var process: Process? = null

    /** dumpsys に出す現在の状態。読めない時は原因と対処を日本語で残す。 */
    val state: String
        get() = when {
            !active -> STOPPED
            failure != null -> failure ?: STOPPED
            matched > 0L -> "anchor=$anchorWallMs station=${station ?: configured} lines=$lines"
            lines > 0L -> "TVログ受信中・録画の再生位置を待機中 lines=$lines"
            nowElapsed() - startedAt > CONSENT_HINT_MS -> CONSENT_HINT
            else -> "TVログの放送日時を待機中"
        }

    /** まだ1行も読めていない＝ログアクセスの確認を承認すべき状態。 */
    val waitingForConsent: Boolean
        get() = active && failure == null && lines == 0L

    fun start(configuredStation: String?) {
        synchronized(this) {
            configured = configuredStation?.takeIf { StationCatalog.find(it) != null }
            if (active) return
            active = true
            station = null
            anchorWallMs = 0L
            lines = 0L
            matched = 0L
            restarts = 0
            failure = null
            startedAt = nowElapsed()
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
        }
    }

    private fun pump() {
        while (active) {
            val current = try {
                spawn()
            } catch (_: Exception) {
                failure = "logcat を起動できません（READ_LOGS 未付与の可能性）"
                return
            }
            synchronized(this) {
                if (!active) {
                    current.destroy()
                    return
                }
                process = current
            }
            val before = lines
            val guard = consentGuard(current, before)
            read(current)
            guard.interrupt()
            if (!active) return
            if (lines > before) restarts = 0 // 読めた1本は再接続の回数に数えない
            if (restarts >= MAX_RESTARTS) {
                failure = "TVログを読み続けられません（再接続を $restarts 回で打ち切り）"
                return
            }
            restarts += 1
            try {
                Thread.sleep(RESTART_DELAY_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /**
     * 1行も来ないまま無音が続く＝ログアクセスが未承認。logcat を作り直して確認を出し直す。
     * 読めている間は切らない（作り直すと確認が再度必要になり取りこぼすため）。
     */
    private fun consentGuard(current: Process, before: Long): Thread {
        val guard = Thread({
            try {
                Thread.sleep(CONSENT_RETRY_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (active && lines == before) current.destroy()
        }, GUARD_NAME)
        guard.isDaemon = true
        guard.start()
        return guard
    }

    /** 開いた1本は読み切るまで手放さない（許可ダイアログの再確認を招かないため）。 */
    private fun read(current: Process) {
        try {
            BufferedReader(InputStreamReader(current.inputStream)).use { reader ->
                while (active) {
                    val line = reader.readLine() ?: break
                    lines += 1
                    consume(line)
                }
            }
        } catch (_: Exception) {
            // 読み取りが途切れた場合は呼び出し元で作り直す
        } finally {
            current.destroy()
        }
    }

    /**
     * 放送時刻が想定からずれた時（頭出し・シーク・番組切替）だけ再アンカーし、
     * 以降はアンカーからの経過を再生位置として公開する（毎秒の再同期を起こさない）。
     */
    private fun consume(line: String) {
        LogRecordedParser.stationId(line)?.let { station = it }
        val wall = LogRecordedParser.positionWallMs(line) ?: return
        matched += 1
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
        publish(
            RecordedObservation(
                chosen, anchorWallMs, (now - anchorAtMs).coerceAtLeast(0L),
                DetectionOrigin.ACCESSIBILITY, now, detail,
            ),
        )
    }

    private companion object {
        const val THREAD_NAME = "nicotv-log-evidence"
        const val STOPPED = "TVログ監視は停止中"
        const val CONSENT_HINT =
            "テレビのログアクセス確認を自動承認して再接続中（確認が画面に残る場合は「1回限りのアクセスを許可」を選んでください）"
        const val CONSENT_HINT_MS = 8_000L
        const val GUARD_NAME = "nicotv-log-consent"
        const val CONSENT_RETRY_MS = 12_000L
        const val MAX_RESTARTS = 30
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
