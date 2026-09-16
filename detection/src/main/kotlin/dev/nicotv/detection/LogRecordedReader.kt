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
 * - 放送局: 再生中ストリームのPMT（CapEngineSystem の SiPmt.iServiceId）を最優先。
 *   出ない場面では ISDB service_id（ShDbHelper の serviceId / DB の svcId）で補う。
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
    // 再生中ストリームのPMT。裏で走るEPG更新と違い、いま映っている番組のIDだけが出る。
    private val pmtService = Regex("iServiceId ?[:=] ?([0-9]{1,5})")
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

    /** 再生中ストリームのPMTから読む放送局。候補ではなく確定値として扱う。 */
    fun pmtStationId(line: String): String? {
        if (line.length > DetectionLimits.MAX_TEXT_CHARS) return null
        val serviceId = pmtService.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
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
    @Volatile private var spawns = 0
    @Volatile private var lastPositionAtMs = 0L
    @Volatile private var candidate: String? = null
    @Volatile private var candidateAtMs = 0L
    @Volatile private var candidateHits = 0
    @Volatile private var stationResetAtMs = 0L
    @Volatile private var pmtStation: String? = null
    @Volatile private var pmtAtMs = 0L
    private var worker: Thread? = null
    private var process: Process? = null

    /** dumpsys に出す現在の状態。読めない時は原因と対処を日本語で残す。 */
    val state: String
        get() = when {
            !active -> STOPPED
            failure != null -> failure ?: STOPPED
            matched > 0L -> "anchor=$anchorWallMs station=${station ?: configured} lines=$lines"
            lines > 0L -> "TVログ受信中・録画の再生位置を待機中 lines=$lines"
            spawns > 1 -> CONSENT_FOREGROUND_HINT
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
            spawns = 0
            lastPositionAtMs = 0L
            stationResetAtMs = 0L
            pmtStation = null
            pmtAtMs = 0L
            clearCandidate()
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

    /**
     * ログアクセスの要求は前面のアプリだけが許可される（Android 13 以降は背面からの要求を確認なしで拒否する）。
     * まだ1行も読めていない時に限り、アプリ画面が前面に出た時点で接続を作り直して確認を出し直す。
     */
    fun reacquire(): Boolean {
        synchronized(this) {
            if (!active || failure != null || lines > 0L) return false
            restarts = 0
            process?.destroy()
            worker?.interrupt()
            return true
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
            spawns += 1
            val before = lines
            // 読めている接続は切らない。まだ1行も読めていない握手の間だけ作り直して確認を出し直す。
            val guard = if (before == 0L) consentGuard(current) else null
            read(current)
            guard?.interrupt()
            if (!active) return
            if (lines > before) restarts = 0 // 読めた1本は再接続の回数に数えない
            if (restarts >= MAX_RESTARTS) {
                failure = "TVログを読み続けられません（再接続を $restarts 回で打ち切り）"
                return
            }
            restarts += 1
            try {
                // 許可が取れていない間は連打しない（背面からの要求は拒否されるだけなので待つ）。
                Thread.sleep(if (lines == 0L) CONSENT_BACKOFF_MS else RESTART_DELAY_MS)
            } catch (_: InterruptedException) {
                if (!active) return
            }
        }
    }

    /**
     * 1行も来ないまま無音が続く＝ログアクセスが未承認。logcat を作り直して確認を出し直す。
     * 一度でも読めた接続には仕掛けない（無音は再生していないだけのことが多く、切ると取り直せない）。
     */
    private fun consentGuard(current: Process): Thread {
        val guard = Thread({
            try {
                Thread.sleep(CONSENT_RETRY_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (active && lines == 0L) current.destroy()
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
        val wall = LogRecordedParser.positionWallMs(line)
        if (wall == null) {
            val pmt = LogRecordedParser.pmtStationId(line)
            if (pmt != null) adoptPmtStation(pmt)
            else LogRecordedParser.stationId(line)?.let { offerStation(it) }
            return
        }
        matched += 1
        val now = nowElapsed()
        val drift = if (anchorWallMs == 0L) Long.MAX_VALUE else abs(wall - (anchorWallMs + (now - anchorAtMs)))
        // 別の番組に切り替わった。切替と同時にPMTで局が確定している時は手放さない。
        if (anchorWallMs != 0L && drift > SWITCH_TOLERANCE_MS && !pmtFresh(now)) forgetStation(now)
        if (drift > DRIFT_TOLERANCE_MS) {
            anchorWallMs = wall
            anchorAtMs = now
        }
        lastPositionAtMs = now
        adoptCandidate(now)
        // 放送局を取り直している間は、前の番組の局でコメントを流さない
        if (station == null && stationResetAtMs != 0L && now - stationResetAtMs < STATION_GRACE_MS) return
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

    /** 放送局は再生位置ログと時間的に近いサービスIDだけを採用する（裏で走るEPG更新のIDで局が動かないように）。 */
    private fun offerStation(value: String) {
        // 再生中ストリームの局が分かっている間は、裏のDB読み出しで局を動かさない
        if (pmtStation != null) return
        val now = nowElapsed()
        val playing = lastPositionAtMs != 0L && now - lastPositionAtMs <= STATION_WINDOW_MS
        val known = station
        if (known == null) {
            if (playing) {
                station = value
                clearCandidate()
            } else rememberCandidate(value, now, 0)
            return
        }
        if (!playing) return
        if (value == known) {
            clearCandidate()
            return
        }
        if (value == candidate) {
            candidateHits += 1
            if (candidateHits >= STATION_SWITCH_HITS) {
                station = value
                clearCandidate()
            }
        } else rememberCandidate(value, now, 1)
    }

    /** 再生中ストリームのPMTに出た局。いま映っている番組そのものなので即採用する。 */
    private fun adoptPmtStation(value: String) {
        val now = nowElapsed()
        pmtStation = value
        pmtAtMs = now
        station = value
        stationResetAtMs = 0L
        clearCandidate()
    }

    /** 切替とほぼ同時にPMTで局が確定したか。 */
    private fun pmtFresh(now: Long): Boolean = pmtStation != null && now - pmtAtMs <= STATION_WINDOW_MS

    /** 再生開始の直前に出たサービスIDは、最初の再生位置が来た時に採用する。 */
    private fun adoptCandidate(now: Long) {
        if (station != null) return
        val pending = candidate ?: return
        if (now - candidateAtMs <= STATION_WINDOW_MS) {
            station = pending
            clearCandidate()
        }
    }

    private fun forgetStation(now: Long) {
        station = null
        pmtStation = null
        pmtAtMs = 0L
        clearCandidate()
        stationResetAtMs = now
    }

    private fun rememberCandidate(value: String, now: Long, hits: Int) {
        candidate = value
        candidateAtMs = now
        candidateHits = hits
    }

    private fun clearCandidate() {
        candidate = null
        candidateAtMs = 0L
        candidateHits = 0
    }

    private companion object {
        const val THREAD_NAME = "nicotv-log-evidence"
        const val STOPPED = "TVログ監視は停止中"
        const val CONSENT_HINT =
            "テレビのログアクセス確認を自動承認して再接続中（確認が画面に残る場合は「1回限りのアクセスを許可」を選んでください）"
        const val CONSENT_FOREGROUND_HINT =
            "TVログの許可を取得できません（NicoTVOverlayの画面を開くと自動で取り直します）"
        const val CONSENT_HINT_MS = 8_000L
        const val GUARD_NAME = "nicotv-log-consent"
        const val CONSENT_RETRY_MS = 20_000L
        const val CONSENT_BACKOFF_MS = 20_000L
        const val MAX_RESTARTS = 30
        const val RESTART_DELAY_MS = 2_000L
        const val DRIFT_TOLERANCE_MS = 5_000L
        const val SWITCH_TOLERANCE_MS = 60_000L
        const val STATION_WINDOW_MS = 15_000L
        const val STATION_GRACE_MS = 6_000L
        const val STATION_SWITCH_HITS = 2

        fun spawnLogcat(): Process = ProcessBuilder(
            listOf(
                "logcat", "-v", "brief", "-T", "1",
                "TunableTvView(MainView):D", "CapEngineSystem:V",
                "[DTVBG]ShDbHelper:I", "DB:W", "*:S",
            ),
        ).redirectErrorStream(true).start()
    }
}
