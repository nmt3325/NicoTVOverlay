package dev.nicotv.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import dev.nicotv.core.*
import dev.nicotv.overlay.DanmakuView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Remote-first native UI. Demo contains only local synthetic comments. */
@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private lateinit var repository: SettingsRepository
    private lateinit var vault: EncryptedPskStore
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var permissionText: TextView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var page = "視聴"
    private var visible = false
    private var notificationStartPending = false
    private var demoJob: Job? = null
    private var preview: DanmakuView? = null
    private val white = Color.WHITE
    private val secondary = Color.rgb(195, 198, 203)
    private val blue = Color.rgb(94, 159, 232)
    private val canvas = Color.rgb(25, 25, 25)
    private val surface = Color.rgb(34, 35, 38)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SettingsRepository(this); vault = EncryptedPskStore(this)
        val root = column().apply { setPadding(dp(32), dp(16), dp(32), dp(16)); setBackgroundColor(canvas) }
        val header = row()
        val titles = column()
        titles.addView(text("NicoTVOverlay", 34f, true))
        titles.addView(text("テレビに、実況を。", 18f, color = secondary))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("開始", primary = true) { requestStart() }, LinearLayout.LayoutParams(dp(128), dp(60)).apply { marginStart = dp(12) })
        header.addView(button("■ 停止", risk = true) {
            stopDemo(); ServiceCommands.stop(this); showHint("停止しました。自動では再開しません")
        }, LinearLayout.LayoutParams(dp(128), dp(60)).apply { marginStart = dp(12) })
        root.addView(header)
        status = text("停止中", 20f, true).apply { setPadding(0, dp(10), 0, dp(2)); minHeight = dp(38) }
        root.addView(status)
        hint = text("手動選局が基本です。自動検出は機種・テレビアプリごとに校正が必要です。", 18f, color = secondary).apply { minHeight = dp(30) }
        root.addView(hint)
        val tabs = row()
        for (title in listOf("視聴", "表示設定", "詳細・権限", "デモ")) {
            val tab = button(title) { showPage(title) }.apply { tag = "tab:$title" }
            tabs.addView(tab, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(8)) })
        }
        root.addView(tabs)
        scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        body = column().apply { setPadding(dp(4), dp(4), dp(4), dp(24)) }
        scroll.addView(body, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        showPage("視聴")
        scope.launch { RuntimeSession.state.collect { updateStatus(it) } }
        scope.launch { while (isActive) { delay(250); checkActivityDisplay() } }
    }
    override fun onResume() {
        super.onResume(); visible = true
        if (!PlatformPermissions.defaultTarget(this) && RuntimeSession.state.value.active) ServiceCommands.stop(this)
        updateStatus(RuntimeSession.state.value); updatePermissionText()
    }
    override fun onConfigurationChanged(configuration: Configuration) {
        super.onConfigurationChanged(configuration)
        checkActivityDisplay()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        checkActivityDisplay()
    }
    private fun checkActivityDisplay() {
        if (::repository.isInitialized && visible && RuntimeSession.state.value.active && !PlatformPermissions.defaultTarget(this)) {
            ServiceCommands.stop(this); showHint("標準画面以外は表示先を許可しません")
        }
    }
    override fun onPause() { visible = false; notificationStartPending = false; stopDemo(); super.onPause() }
    override fun onDestroy() { stopDemo(); scope.cancel(); super.onDestroy() }

    private fun updateStatus(s: SessionUiState) {
        if (!::status.isInitialized) return
        val config = repository.read()
        val mode = if (s.active) when (s.mode) {
            PreferenceContract.MODE_ACCESSIBILITY -> "自動OSD"
            PreferenceContract.MODE_BRAVIA -> "BRAVIA・実験"
            else -> "手動固定"
        } else config.modeLabel
        val station = StationCatalog.find(s.stationId)?.name ?: if (s.active) "局未検出" else "指定：${StationCatalog.find(config.stationId)?.name ?: "なし"}"
        val backend = if (s.active) s.backend else config.backend
        status.text = "${if (s.active) "●" else "■"} ${s.message}  ·  $station  ·  $mode  ·  ${backend.label}"
        status.setTextColor(if (s.active && s.connection == ConnectionState.LIVE) Color.rgb(114, 188, 143) else white)
    }
    internal fun showPage(name: String) {
        val oldFocus = currentFocus?.tag
        stopDemo(clearPreviewReference = true); page = name; body.removeAllViews(); scroll.scrollTo(0, 0)
        when (name) {
            "表示設定" -> appearancePage()
            "詳細・権限" -> advancedPage()
            "デモ" -> demoPage()
            else -> viewingPage()
        }
        updatePermissionText()
        if (oldFocus != null) body.findViewWithTag<View>(oldFocus)?.requestFocus()
    }
    private fun viewingPage() {
        val s = repository.read()
        section("局の指定方法")
        choices(listOf("手動" to PreferenceContract.MODE_MANUAL, "自動OSD" to PreferenceContract.MODE_ACCESSIBILITY, "BRAVIA・実験" to PreferenceContract.MODE_BRAVIA), s.mode) { mode ->
            val save = { persist(repository.read().copy(mode = mode)); showPage("視聴") }
            when (mode) {
                PreferenceContract.MODE_ACCESSIBILITY -> confirm("自動OSDを有効にしますか？", "選択したテレビアプリの局ラベルだけを読みます。OSDとライブ表示IDの校正、および端末のユーザー補助許可が必要です。EPG・複数局・30秒経過は非表示になります。", save)
                PreferenceContract.MODE_BRAVIA -> confirm("BRAVIA連携（実験）", "このテレビ本体の私有IPv4と一致するホストのみ対応します。ユーザー補助とTV_PACKAGES・LIVE_RESOURCE_IDSの校正が必須です。新鮮な前面証拠がある間だけ接続し、Homeや期限切れで停止します。HTTPは平文・実機未検証です。", save)
                else -> save()
            }
        }
        section("コメントの取得元")
        choices(Backend.entries.map { it.label to it.name }, s.backend.name) { value ->
            val next = Backend.valueOf(value)
            val save = { persist(repository.read().copy(backend = next)); showPage("視聴") }
            if (next == Backend.NX) confirm("NX-Jikkyoを選択", "ニコニコ公式とは別サービスのコメントです。公式の接続失敗時も自動では切り替わりません。選択した取得元を常に表示します。", save) else save()
        }
        section("手動の実況局  ·  ${StationCatalog.find(s.stationId)?.name ?: "未選択"}")
        val columns = if (resources.configuration.screenWidthDp >= 800) 5 else 2
        StationCatalog.stations.chunked(columns).forEach { stations ->
            val r = row()
            stations.forEach { station ->
                val selected = station.id == s.stationId
                val b = button((if (selected) "✓ " else "") + station.name, selected = selected) {
                    persist(repository.read().copy(stationId = station.id, mode = PreferenceContract.MODE_MANUAL)); showPage("視聴")
                }.apply { tag = "station:${station.id}"; contentDescription = "${station.name}を手動指定" }
                r.addView(b, LinearLayout.LayoutParams(0, dp(64), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            repeat(columns - stations.size) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            body.addView(r)
        }
        paragraph("局カードを選ぶと手動固定になります。地域局は詳細の別名対応表で設定してください。番号だけから放送局を推測することはありません。")
        if (s.mode == PreferenceContract.MODE_ACCESSIBILITY && !s.calibrated) paragraph("校正が必要：OSD_RESOURCE_IDS と LIVE_RESOURCE_IDS は初期状態で空です。詳細・権限で登録するまで局未検出です。", warning = true)
        paragraph("テレビ本体にインストールしてください。外付けTVボックスからテレビ内蔵チューナーへ重ねることはできません。放送映像上の表示は実機確認が必要です。")
    }
    private fun appearancePage() {
        var options = repository.read().overlay
        section("読みやすい表示に")
        paragraph("変更は保存後に反映します。遅延は0〜30秒。透明度はAndroidのタッチ透過上限を超えません。")
        slider("文字サイズ", 60, 200, (options.fontScale * 100).toInt(), "%") { options = options.copy(fontScale = it / 100f) }
        slider("透明度", 10, 80, (options.opacity * 100).toInt(), "%") { options = options.copy(opacity = it / 100f) }
        slider("流れる速度", 50, 200, (options.speed * 100).toInt(), "%") { options = options.copy(speed = it / 100f) }
        slider("表示を遅らせる", 0, 30, (options.delayMs / 1000).toInt(), "秒") { options = options.copy(delayMs = it * 1000L) }
        val fixed = Switch(this).apply {
            text = "上・下の固定コメントを表示"; textSize = 20f; isChecked = options.showFixed; minHeight = dp(56)
            setPadding(dp(12), 0, dp(12), 0); background = inputDrawable(); isFocusable = true
        }
        body.addView(fixed, LinearLayout.LayoutParams(-1, -2))
        val ng = field("NGワード（改行で区切る・最大100件）", options.ngWords.joinToString("\n"), multiline = true)
        body.addView(button("表示設定を保存", primary = true) {
            val next = options.copy(showFixed = fixed.isChecked, ngWords = ng.text.toString().lines().map(String::trim).filter(String::isNotEmpty))
            if (persist(repository.read().copy(overlay = next))) showHint("表示設定を保存しました。古いコメントは消去されます")
        }, fullButton())
    }
    private fun advancedPage() {
        section("端末の許可")
        permissionText = text("", 18f, color = secondary)
        body.addView(permissionText)
        val permissions = row()
        permissions.addView(button("重ね合わせ表示の許可") {
            openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, weighted())
        permissions.addView(button("ユーザー補助の設定") { openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, weighted())
        permissions.addView(button("通知の許可") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_ONLY)
            else showHint("このAndroidでは通知の追加許可は不要です")
        }, weighted())
        body.addView(permissions)
        paragraph("通知を拒否してもOS上は起動できます。通知欄がないTVでも、この画面上部の「停止」で全通信と表示を終了できます。許可の画面がない機種は手動モードまたは非対応として扱います。")
        section("OSD検出プロファイル")
        paragraph("対象テレビアプリの完全一致IDを登録します。全アプリの画面文字やEPG全局を検索しません。初期パッケージ候補は対応確認済みを意味しません。")
        val s = repository.read()
        val packages = field("TV_PACKAGES（カンマ・改行区切り）", s.tvPackages, multiline = true)
        val osd = field("OSD_RESOURCE_IDS（package:id/station_label）", s.osdIds, multiline = true)
        val live = field("LIVE_RESOURCE_IDS（package:id/live_player）", s.liveIds, multiline = true)
        val aliases = field("CUSTOM_ALIASES JSON（例：{\"地域局名\":\"jk4\"}）", s.aliasesJson, multiline = true)
        section("BRAVIA LAN連携  ·  実験")
        paragraph("テレビ本体にインストールし、この端末自身と確認できた私有IPv4のみを指定してください。ユーザー補助とTV_PACKAGES・LIVE_RESOURCE_IDS（局OSD不要）の校正が必須です。前面証拠が2.5秒以内の間だけ接続します。IP・標準画面が確認できない機種は未対応です。HTTPは平文・実機未検証です。", warning = true)
        val host = field("BRAVIAホスト（このテレビ自身の私有IPv4、別端末不可）", s.braviaHost)
        val psk = field("PSK（空欄は保存済みの値を維持）", "", secret = true)
        psk.hint = if (vault.contains()) "暗号化して保存済み・未変更" else "未登録"
        val map = field("BRAVIA_CHANNEL_MAP JSON（{\"tv:…\":\"jk4\"}）", s.braviaMapJson, multiline = true)
        body.addView(button("詳細設定を保存", primary = true) {
            val next = repository.read().copy(tvPackages = packages.text.toString().trim(), osdIds = osd.text.toString().trim(),
                liveIds = live.text.toString().trim(), aliasesJson = aliases.text.toString().trim(),
                braviaHost = host.text.toString().trim(), braviaMapJson = map.text.toString().trim())
            val errors = SettingsValidator.validate(next)
            if (errors.isNotEmpty()) { showErrors(errors); return@button }
            try {
                if (psk.text.isNotEmpty()) { vault.save(psk.text.toString()); psk.text.clear(); psk.hint = "暗号化して保存済み・未変更" }
                if (persist(next)) showHint("詳細設定を保存しました。空のOSDプロファイルは局未検出になります")
            } catch (_: SecretStorageException) { showErrors(listOf("PSKを保存できません。端末のAndroidKeyStoreを確認してください")) }
            catch (_: IllegalArgumentException) { showErrors(listOf("PSKは制御文字を除く1〜128文字で入力してください")) }
        }, fullButton())
        body.addView(button("保存済みPSKを削除", risk = true) {
            confirm("PSKを削除しますか？", "BRAVIA連携は再登録するまで局未検出になります。", {
                vault.save(null); psk.text.clear(); psk.hint = "未登録"; ServiceCommands.reload(this); showHint("PSKを削除しました")
            })
        }, fullButton())
        paragraph("PSKはAndroidKeyStoreのAES-GCMで暗号化します。画面復元・バックアップ・通知・ログへは含めません。自動起動・無断の取得元切替は行いません。")
    }
    private fun demoPage() {
        section("デモ / 通信なし")
        paragraph("以下は合成コメントだけの表示プレビューです。実放送の取得・局検出テストではありません。ライブ接続失敗時の代替表示には使いません。", warning = true)
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.rgb(15, 18, 23)); background = rounded(Color.rgb(15, 18, 23), Color.rgb(61, 68, 80)) }
        frame.addView(text("LOCAL PREVIEW\nデモ / 通信なし", 28f, true, secondary).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        val view = DanmakuView(this)
        frame.addView(view, FrameLayout.LayoutParams(-1, -1)); preview = view
        body.addView(frame, LinearLayout.LayoutParams(-1, dp(230)).apply { topMargin = dp(12); bottomMargin = dp(12) })
        val controls = row()
        controls.addView(button("合成コメントを流す", primary = true) {
            if (RuntimeSession.state.value.active) { showHint("ライブを「停止」してからデモを開始してください"); return@button }
            stopDemo(clearPreviewReference = false)
            view.updatePreferences(repository.read().overlay)
            demoJob = scope.launch {
                val words = listOf("デモ / 通信なし", "実況をテレビに重ねるプレビュー", "このコメントは合成データです", "文字サイズ・速度・NGワードを設定できます", "いつでも停止できます")
                var i = 0
                while (isActive && visible && page == "デモ" && !RuntimeSession.state.value.active) {
                    view.addComment(LiveComment("demo-${i++}", words[(i - 1) % words.size], System.currentTimeMillis(),
                        position = when (i % 9) { 0 -> CommentPosition.TOP; 4 -> CommentPosition.BOTTOM; else -> CommentPosition.SCROLL },
                        origin = CommentOrigin.DEMO))
                    delay(1400)
                }
            }
            showHint("デモ表示中 / 通信なし / 合成コメント")
        }, weighted())
        controls.addView(button("デモを消去") { stopDemo(clearPreviewReference = false); showHint("デモを消去しました") }, weighted())
        body.addView(controls)
    }
    private fun stopDemo(clearPreviewReference: Boolean = false) {
        demoJob?.cancel(); demoJob = null; preview?.clearComments()
        if (clearPreviewReference) preview = null
    }
    private fun requestStart() {
        stopDemo()
        val s = repository.read()
        val block = PlatformPermissions.block(this, s, visible && hasWindowFocus())
        if (block != null) { showHint(block); showPage("詳細・権限"); return }
        val errors = SettingsValidator.validate(s)
        if (errors.isNotEmpty()) { showErrors(errors); return }
        if (s.mode == PreferenceContract.MODE_BRAVIA && (!SettingsValidator.isPrivateIpv4(s.braviaHost) || !vault.contains() || SettingsValidator.stationMap(s.braviaMapJson, true).isEmpty())) {
            showHint("BRAVIAの私有IP・PSK・局URI対応表を登録してください"); showPage("詳細・権限"); return
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !repository.preferences.getBoolean("notification_asked", false)) {
            repository.preferences.edit().putBoolean("notification_asked", true).apply()
            notificationStartPending = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_START)
            return
        }
        startAuthorized()
    }
    private fun startAuthorized() {
        if (!ServiceCommands.start(this, visible && hasWindowFocus())) showHint("開始できませんでした。画面を開いて許可を確認してください")
        else showHint("開始しました。自動検出で局が不明な間は表示しません。停止は画面上部から")
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionText()
        if (requestCode == NOTIFICATION_START) {
            val pending = notificationStartPending; notificationStartPending = false
            if (pending && visible) startAuthorized()
            else showHint("通知設定を反映しました。開始ボタンで表示を開始してください")
        }
    }
    private fun updatePermissionText() {
        if (!::permissionText.isInitialized || page != "詳細・権限") return
        val notifications = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        permissionText.text = "重ね合わせ：${if (PlatformPermissions.overlays(this)) "許可済み" else "未許可"}  /  ユーザー補助：${if (PlatformPermissions.accessibility(this)) "接続済み" else "未接続"}\n通知：${if (notifications) "許可済み" else "未許可（FGSの起動自体は可能）"}"
    }
    private fun openSettings(intent: Intent) {
        if (!PlatformPermissions.open(this, intent)) showHint("この端末には対応する設定画面がありません。端末の設定を確認してください")
    }
    private fun persist(s: AppSettings): Boolean {
        val errors = SettingsValidator.validate(s)
        if (errors.isNotEmpty()) { showErrors(errors); return false }
        return try { repository.save(s); ServiceCommands.reload(this); updateStatus(RuntimeSession.state.value); true }
        catch (_: IllegalStateException) { showHint("設定を保存できませんでした"); false }
    }
    private fun showErrors(errors: List<String>) {
        AlertDialog.Builder(this).setTitle("設定を確認してください").setMessage(errors.joinToString("\n")).setPositiveButton("閉じる", null).show()
    }
    private fun showHint(message: String) { hint.text = message; hint.announceForAccessibility(message) }
    private fun confirm(title: String, message: String, accepted: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("キャンセル", null).setPositiveButton("確認して選択") { _, _ -> accepted() }.show()
    }
    private fun section(label: String) { body.addView(text(label, 24f, true).apply { setPadding(0, dp(12), 0, dp(8)) }) }
    private fun paragraph(value: String, warning: Boolean = false) {
        body.addView(text(value, 18f, color = if (warning) Color.rgb(241, 184, 118) else secondary).apply { setPadding(dp(8), dp(10), dp(8), dp(8)); setLineSpacing(dp(3).toFloat(), 1f) })
    }
    private fun choices(items: List<Pair<String, String>>, selected: String, action: (String) -> Unit) {
        val r = row()
        items.forEach { (label, value) -> r.addView(button((if (selected == value) "✓ " else "") + label, selected = selected == value) { action(value) }, weighted()) }
        body.addView(r)
    }
    private fun slider(label: String, min: Int, max: Int, initial: Int, suffix: String, change: (Int) -> Unit) {
        val value = text("$label  $initial$suffix", 20f)
        body.addView(value)
        body.addView(SeekBar(this).apply {
            this.max = max - min; progress = initial.coerceIn(min, max) - min; minimumHeight = dp(56)
            contentDescription = label; isFocusable = true; background = inputDrawable()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    value.text = "$label  ${progress + min}$suffix"; if (fromUser) change(progress + min)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(12) })
    }
    private fun field(label: String, initial: String, multiline: Boolean = false, secret: Boolean = false): EditText {
        body.addView(text(label, 18f, true).apply { setPadding(0, dp(12), 0, dp(6)) })
        return EditText(this).apply {
            textSize = 20f; setTextColor(white); setHintTextColor(secondary); setPadding(dp(14), dp(12), dp(14), dp(12))
            minHeight = dp(56); background = inputDrawable(); isSingleLine = !multiline
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(initial); contentDescription = label; maxLines = if (multiline) 5 else 1
            if (secret) { isSaveEnabled = false; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS }
            body.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }
    private fun button(label: String, primary: Boolean = false, risk: Boolean = false, selected: Boolean = false, action: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 20f; isAllCaps = false; minHeight = dp(52); minimumHeight = dp(52); minimumWidth = dp(48)
        isFocusable = true; isFocusableInTouchMode = false; maxLines = 2; gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6)); stateListAnimator = null
        val focused = intArrayOf(android.R.attr.state_focused)
        setTextColor(ColorStateList(arrayOf(focused, intArrayOf()), intArrayOf(canvas, if (primary) canvas else white)))
        background = focusDrawable(if (primary) blue else surface, if (risk) Color.rgb(233, 115, 102) else if (selected) blue else Color.rgb(112, 115, 122), selected)
        setOnClickListener { action() }
    }
    private fun inputDrawable() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(surface, white, 3))
        addState(intArrayOf(), rounded(surface, Color.rgb(112, 115, 122)))
    }
    private fun focusDrawable(base: Int = surface, border: Int = Color.rgb(112, 115, 122), selected: Boolean = false) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(white, blue, 4))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(Color.rgb(190, 214, 245), blue, 4))
        addState(intArrayOf(), rounded(base, border, if (selected) 3 else 1))
    }
    private fun rounded(fill: Int, stroke: Int, width: Int = 1) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(8).toFloat(); setStroke(dp(width), stroke) }
    private fun text(value: String, size: Float = 20f, bold: Boolean = false, color: Int = white) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun weighted() = LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
    private fun fullButton() = LinearLayout.LayoutParams(-1, dp(60)).apply { topMargin = dp(12); bottomMargin = dp(8) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    companion object { private const val NOTIFICATION_START = 101; private const val NOTIFICATION_ONLY = 102 }
}
