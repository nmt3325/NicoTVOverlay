# Station detection: calibrated, explicit, fail-closed

## Supported boundary

An ordinary sideload APK cannot query the channel tuned by another application's TV Input Framework session through the public SDK. This module uses **no hidden API, reflection into TIF, root, watched-program provider, privileged grant, screen capture, key interception, gesture injection, or LAN discovery**. Manual selection remains a separately labeled application mode, not a silent automatic fallback.

Accessibility is device/app dependent. The user must explicitly enable the service in system settings, select Accessibility station mode or the explicitly selected BRAVIA foreground-guard mode below, calibrate its IDs, and press Start. Connection alone does not start an overlay, foreground service, comment client, or network request. `StationDetectionBus` is an in-process StateFlow, initially Unknown. The app owns Start/Stop and resets persisted `SESSION_ACTIVE` on fresh process startup; this module never grants that authorization itself.

## Calibrating a live profile

All settings use `PreferenceContract.STORE`. Both `SESSION_ACTIVE=true` and `DETECTION_MODE=MODE_ACCESSIBILITY` are required. Package names are exact, comma/newline-separated Android package IDs; no suffix, wildcard or substring matching. The shared default package list is only a starting suggestion when the preference is absent. An explicitly empty list **disables** events and scanning, never means “all apps.” Invalid configuration fails closed rather than partially applying.

1. Confirm the television application's exact package on the actual TV. External Android boxes cannot infer or overlay an internal tuner when their HDMI output is not displayed.
2. Configure `OSD_RESOURCE_IDS` with fully qualified station-label IDs, e.g. `your.tv.package:id/current_station`. Configure `LIVE_RESOURCE_IDS` with separate fully qualified marker IDs that are visible **only during live TV**, not a generic video player/container. Both defaults are empty. IDs must belong to an allowed package; overlapping label/marker IDs are rejected.
3. Test live, EPG (including focused one-station cells), recordings, search, input changes and channel lists. A marker reused by those screens is **not a valid calibration**. Resource IDs alone cannot prove semantics on an unverified OEM profile. If the distinction is not exposed, automatic detection is unsupported; explicitly choose manual mode instead.
4. The label must match a catalog alias or a user-confirmed `CUSTOM_ALIASES` JSON object such as `{"地元テレビ":"jk4"}`. Mapping a local affiliate to a key network's commentary is the user's choice, not an inferred affiliation. Exact NFKC/whitespace normalization only; no remote-number guesses, case-insensitive/substring matching, title extraction or EPG first-match heuristics. Conflicting aliases and unknown jk IDs disable the profile.

Only one current active root is traversed, never all windows. Root package is checked before any child/text access. Within it, the reader inspects bounded node metadata and reads text/content descriptions **only from visible calibrated station-label nodes**. Live-marker text and arbitrary titles are not read. Foreign-package subtrees are not entered. Visible collection/list/grid nodes cause rejection, even if focused or selected. Unknown labels, inconsistent text/description, multiple station candidates, missing live marker and any truncation/inaccessibility produce Unknown, never a best guess. EPG/recording identification depends on correct live-only calibration; it is not implemented by scanning other screen strings.

Limits: 256 nodes, depth 16, 256 characters per label field, 4,096 total label characters per scan, at most 16 IDs per role, 32 packages, 128 custom aliases, 16 KiB per setting. Scans are at most four per second with trailing coalescing. No raw screen text, tree, event contents or event-source node is retained, logged, persisted, exported or sent over a network. Only station ID, foreground identity, monotonic timestamps and static reason strings survive a scan.

## Switching and freshness

A new sole station candidate clears the previous observation immediately, then waits at least **750 ms**. A newly acquired root must still yield the same sole station and package/window identity. The rate limiter can defer the confirmation by up to another scan interval; a due event scan performs the confirmation so event storms cannot starve it. Generations reject old delayed callbacks, including A→B→C and Stop/restart races. Delayed accessibility events trigger fresh reads; their old text/source is not used.

While an authorized ACCESSIBILITY station session is active, a **1 s foreground heartbeat** acquires the active root and inspects only package/window identity, never station strings. It catches Home/other-app departure even though their accessibility events are package-filtered out. Root-null, off-list foreground, screen-off broadcast, service interruption/disconnection, Stop and mode changes clear selection. Lock/unusable-display conditions are rechecked on every scan and heartbeat. Foreground departures/lock without a delivered event are bounded by the heartbeat interval, not claimed to be instant OS notifications.

`observedAtMs` is `SystemClock.elapsedRealtime()`, not wall clock. A station has a **30 s evidence TTL**. Foreground heartbeat does not extend that timestamp; only a fresh calibrated station read can. A separate deadline clears evidence at 30 s without a root read or waiting for the next heartbeat (subject to normal Android main-thread scheduling). A transient OSD therefore gives at most bounded inference; it does not guarantee continuous channel knowledge after it disappears. A scan that sees the missing OSD/live marker clears immediately. Multi-display/PiP and OEM hardware-plane behavior are unverified; no claim of support is made.

The manifest has the system binding permission `BIND_ACCESSIBILITY_SERVICE`, exported service plus accessibility intent/metadata, and `isAccessibilityTool=false`. The XML starts with no event types and a disabled sentinel package. Runtime service information selects only window-state/content/windows events and `FLAG_REPORT_VIEW_IDS`. ACCESSIBILITY station mode does not request interactive windows. Only the explicitly started/calibrated BRAVIA guard adds `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` to verify default-display window metadata; other roots/titles are never read. No screenshot, gesture, touch exploration or key-filter capability is requested. API33+ active-root reads disable descendant prefetch; older Android versions control their own prefetch even though this reader does not inspect off-list contents.

A feedback `onInterrupt` invalidates every evidence/pending generation and latches observation off, but does **not** invent a system unbind. Events, screen broadcasts, settings repair and passage of time cannot revive it. An observed `SESSION_ACTIVE=false` (Stop), then an explicit `true` (Start), safely resumes on the existing binding; `onUnbind`/`onDestroy` additionally clear the actual binding. Malformed settings cannot masquerade as Stop.

## Independent foreground live-screen guard in BRAVIA mode

The existing `MODE_BRAVIA` has a separately authorized local guard in the Accessibility service. It requires `SESSION_ACTIVE=true`, a **saved explicit nonempty** exact `TV_PACKAGES` list (no suggested default fallback), and calibrated nonempty fully-qualified `LIVE_RESOURCE_IDS`. This mode does not read or parse OSD/alias settings, station text, marker text, content descriptions, titles or other applications' text. It cannot identify a station. The visible live-only marker must have nonempty screen geometry; missing/invisible/blank ID/zero-area markers, collection/list/guide views and recording players lacking that live-only marker fail closed. A generic container or marker reused for recordings/guide is not valid calibration: there is no public semantic tuner/recording oracle, and such a profile is unsupported rather than guessed.

A fresh active root must have an exactly allowed package. Public `AccessibilityService.getWindows()` returns interactive window metadata for the **default display**, in descending layer order. The root window must have a known matching ID and be the first interactive window, the sole active window, focused, application-type, non-PiP, and nonempty on-screen bounds. API30+ additionally checks `AccessibilityWindowInfo.getDisplayId()==Display.DEFAULT_DISPLAY`. Empty/inaccessible/ambiguous metadata or another higher interactive/modal window is Unknown. At most 32 window records and one bounded root tree (256 nodes/depth16) are inspected; no other window root/title is retrieved. This conservative default-display-only profile can reject legitimate OEM arrangements and does not claim physical-TV/sideband/external-box display equivalence.

Success publishes `StationObservation(stationId=null, origin=ACCESSIBILITY, observedAtMs=elapsedRealtime(), watchingTv=true, ...)` through the existing bus. That is **local live-screen guard evidence**, not an Accessibility station detection and not a substitute for a BRAVIA REST station response. About every 1 s, the window and actual visible live marker are reread; event-triggered scans remain limited to one per250ms. Only a new complete check renews the guard timestamp. A separate **2.5 s guard deadline** fails closed if evidence is not renewed (normal main-thread scheduling applies). The app must independently reject stale/wrong-mode/wrong-session guard values and REST replies, require BOTH fresh guard and explicit REST URI→jk evidence, and clear/cancel REST/comments before accepting another generation. The guard cannot itself authenticate that the user-configured LAN endpoint is this physical display.

Screen-off, interrupt, disconnect, Stop, mode/profile loss and delivered departure invalidate immediately on handling; unreported Home/lock/marker changes are discovered at the next approximately1s check, not magically at the unseen transition. No REST, FGS or comment request is initiated by the Accessibility service. The existing ACCESSIBILITY station mode's metadata-only heartbeat and separate30s station TTL are unchanged; a BRAVIA guard never extends a station's evidence age or silently switches modes.

## BRAVIA adapter (experimental, not model verified)

`BraviaStationDetector(client = OkHttpClient(), channelMap = emptyMap()).observations(host, psk)` is a cold cancellable flow, not an automatically started service. It performs only this documented read method:

```http
POST /sony/avContent
Content-Type: application/json; charset=utf-8
X-Auth-PSK: <configured secret>

{"method":"getPlayingContentInfo","id":1,"params":[],"version":"1.0"}
```

The host must be a canonical dotted numeric **RFC1918 IPv4** (10/8, 172.16/12, 192.168/16). URLs, hostnames, ports, userinfo, alternate numeric encodings, IPv6, public/link-local/loopback addresses and whitespace are rejected **before a request**. The endpoint is fixed to HTTP port 80 and `/sony/avContent`; no discovery, power-on, input switch or write-control request exists. PSK is a bounded printable header value, never logged or put in a URL. The module stores no credentials. The app is responsible for AndroidKeyStore-encrypted PSK storage and its explicit local-HTTP exception. This traffic is cleartext on the user's private LAN; it does not weaken HTTPS/WSS for comments.

The adapter clones, not mutates, the supplied client. It disables redirects (including SSL redirects), automatic connection retries, cookies, cache, authentication callbacks and proxies; inherited interceptors/event listeners are removed to avoid credential/body logging. Numeric addresses are reconstructed without DNS and revalidated by a private-only resolver; the socket factory and connection pool are isolated so a caller-supplied DNS/proxy route cannot send the PSK elsewhere. MockWebServer tests explicitly replace that resolver only on their internal test transport. Connect/read/write timeouts are 1.5 s and total call timeout 2.5 s. Responses are capped at 64 KiB; JSON depth is bounded. Bodies always close, and coroutine cancellation cancels the actual OkHttp call, including response-body reading. Tests exercise the internal transport with a loopback-only MockWebServer; that is not a public-host validation bypass.

Success requires matching numeric response id 1, no error property, exactly one result object, `source` beginning `tv:`, and an exact `tv:` URI→known jk mapping supplied by the user. HTTP401/403, other non-2xx (including redirect), HTTP200 error JSON, HDMI/non-TV, malformed/unknown/empty/multiple results, unmapped URI and known non-playing/standby indicators are Unknown. **Title, display number and unverified consumer fields never identify a station.** Optional status fields are conservative rejection hints, not asserted universal BRAVIA response fields. If a device returns a stale TV result in standby or another Android app, the API alone cannot establish foreground viewing.

Polling is sequential: one completed request, then 2 s wait. Failures back off through 2/4/8/16/30 s, capped at 30 s; success resets the wait. Cancellation prevents late emission and stops the wait/request. The application must collect only during the explicitly started BRAVIA mode and cancel on Stop/mode change/screen-off. A BRAVIA observation reports what the configured LAN device API reports, **not proof that the local Android foreground is that TV**. The app must hide/suspend when foreground does not match its selected viewing display/profile; without that independent check, this experimental adapter is insufficient for unattended auto-display. It must not substitute Accessibility or manual evidence silently.

## Validation and remaining device work

Tests include deterministic fake-clock policy and bounded synthetic-tree fixtures, API28 lifecycle fixtures and API28/API35 synthetic root/default-window guard fixtures, and local MockWebServer transport. They additionally exercise feedback-interrupt Stop→Start recovery without rebinding, no automatic revival, mode-generation isolation, missing/invisible/zero-area live markers, default-window/active/focus/PiP/display gates and fresh marker rereads. They cover opt-in/empty package/calibration gates, exact normalization/custom aliases, titles/recordings/EPG ambiguity, limits and privacy of node reads, rapid A→B→C and old generations, root-null/Home, Stop/mode change, screen/lock, event storms, evidence expiry, URI/auth/error/input/host validation, redirects, oversized/slow response and cancellation. These are synthetic fixtures, **not actual tuner or Sony interoperability tests**.

Before declaring a model supported, validate the real package/resource IDs and live-only semantics, transient OSD behavior, EPG/recording/input/PiP/app departure, accessibility/restricted-settings UI, permission/service loss, actual station→commentary mapping, and (Sony) supported method/field fixtures and lack of stale TV evidence while in another input/app/standby. Overlay composition above tuner/HDMI/HDR/DRM surfaces and remote controls belong to integrated physical-TV testing.

## Primary documentation supporting the boundary

- [AOSP TvInputManager (Android 15)](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/media/java/android/media/tv/TvInputManager.java): current tuned information is hidden/system API.
- [AOSP platform permissions](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/res/AndroidManifest.xml): tuned-info privilege and accessibility binding boundary.
- [Android accessibility service guide](https://developer.android.com/guide/topics/ui/accessibility/service) and [AccessibilityServiceInfo](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo): user enablement, capability and package filters.
- [Sony REST API reference](https://pro-bravia.sony.net/remote-display-control/rest-api/reference/), [guide](https://pro-bravia.sony.net/remote-display-control/rest-api/guide/) and [structure](https://pro-bravia.sony.net/remote-display-control/rest-api/structure/): getPlayingContentInfo, PSK and error/result envelope. Professional-display documentation does not prove all Japanese consumer-model fields.

- [AOSP AccessibilityService lifecycle and getWindows contract (Android 15)](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/accessibilityservice/AccessibilityService.java): feedback interruption differs from unbinding; getWindows supplies default-display interactive windows in descending layer order.


## Exact AQUOS transient-OSD profile (0.1.1)

The generic policies above remain unchanged. An explicit built-in profile for the physically inspected AQUOS-4KTVJ25-2 adds a narrowly scoped exception: a fresh full-screen live-marker geometry scan may renew an already confirmed station in the same window while its transient station label is absent. The scan gap must be at most 2.5 seconds, with no pending different station; missing/ambiguous/unknown labels are not interchangeable. It never establishes an initial station from the live marker alone. A 252x140 EPG thumbnail fails the full-screen check. The TV's deep decoration hierarchy is bounded at 32 levels for this exact profile, versus 16 for generic profiles; node and text budgets remain bounded.

During this explicitly selected profile, tuning keys invalidate old station evidence without consuming the event; scanning waits 400ms for a fresh OSD, followed by the existing 750ms re-read confirmation. Key contents are never logged or retained. Other profiles do not request key filtering. Home, guide, interrupted/disconnected authorization, screen off, stale scans and changed windows clear state. A newly returned foreground may require INFO/channel selection for fresh station evidence; no stale station is guessed. Playback/recording/HDMI behavior beyond the documented real-device cases remains unverified.


### Hardware iteration: non-semantic AQUOS views (0.1.2)

The first installed build could not see the live marker. On the real television, a full accessibility dump contained 65 nodes including the 1920x1080 live view and the current station label; a compressed dump exposed only one root. The service therefore requests `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS` while the exact AQUOS profile is explicitly active. Package, resource-ID, text-budget, geometry and Stop gates remain in place; generic profiles do not request the extra tree flag. A service-level regression simulates this one-node compressed tree, then verifies label confirmation, 40-second hidden-OSD continuity, tuning-key pass-through/invalidation and removal of both flags on Stop. Missing-live diagnostics expose only root/marker bounds and node count, never arbitrary screen text.

## 0.1.3: OSDが消える瞬間に確定局が失われる問題（実機で特定）

AQUOS 実機（Android 14 / 1920x1080）で 0.1.2 を動かし、`dumpsys` を約180ms間隔で
サンプリングして OSD が消える瞬間を観測した。

```text
 1.35s station=jk7  reason=校正済み局ラベルで確認           # OSD 表示中は正しく確定
 4.36s station=null reason=局情報が曖昧・一覧表示・読み取り上限超過です  # OSD 消灯の1回だけ
 4.56s station=null reason=AQUOSの全画面ライブを再確認・OSDは非表示  # 以降復帰しない
```

`dumpsys accessibility` を OSD 表示時と非表示時で比較すると、前面ウィンドウは
`TYPE_APPLICATION / 1920x1080` の同一 ID のままで変わらない。つまり OSD が閉じる途中で
アクセシビリティツリーが書き換わり、走査が1回だけ失敗していた。

0.1.2 ではこの1回の失敗が `invalidate()` を呼び、確定局と `confirmedForeground` を消していた。
継続保持は「確定局が残っていること」を前提にしているため、以降は全画面ライブを
再確認できても二度と復帰できない（片方向のラッチ）。

0.1.3 の対応：

- 読み取り失敗の理由を分離した。ツリーの書き換えや走査上限（missing / nodes / depth /
  children / text / package）は一過性、一覧表示（EPG など）と未登録ラベルは
  「別の画面が確実に見えている」ため即クリアのまま。
- 一過性の失敗では、同じ確定ウィンドウ・確定局・2.5秒以内に限り局を保持する。
  証拠時刻は更新しないので、読み取れない状態が続けば 2.5 秒で必ず失効する（fail-closed を維持）。
