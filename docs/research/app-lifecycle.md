# App lifecycle, permissions and TV UI

## Contract and capability boundary

The app uses the shared core API, NicoLiveCommentSource, NxJikkyoCommentSource, StationDetectionBus, BraviaStationDetector and a single DanmakuView overlay. Manual selection remains available for all ten catalog entries. Catalog jk IDs are commentary identifiers, not remote-control key numbers. Generic current-channel detection is not promised for every TV. Install on the television itself: an external TV box cannot overlay the TV internal tuner on another input.

Official Niconico is the default backend. NX-Jikkyo is a separate explicitly confirmed service with continuously visible provenance. There is no automatic fallback between backends, and live errors never substitute synthetic comments.

## Explicit session lifetime

NicoTvApplication resets PreferenceContract.SESSION_ACTIVE=false on process creation. RuntimeSession holds only an in-memory, single-use start ticket issued by an Activity start action while visible and focused; it is never restored from preferences or instance state. OverlayService is not exported. Accessibility binding alone cannot start a session.

The Activity checks SYSTEM_ALERT_WINDOW. Accessibility and BRAVIA modes also check the explicitly enabled service. BRAVIA additionally requires the same-device private IPv4, calibrated live marker, PSK and exact channel URI map. API33+ POST_NOTIFICATIONS is requested, but denial is not described as an OS prohibition on starting an FGS. A foreground notification is still supplied, while ordinary notification updates check permission. Missing or denied settings Activities are caught and explained.

Foreground promotion is immediate and precedes SESSION_ACTIVE=true and any detector/client work. API34+ uses specialUse, FOREGROUND_SERVICE_SPECIAL_USE and an explicit subtype property. The notification has an immutable explicit Stop PendingIntent. A large Stop button stays at the top of the TV launcher, including devices without a useful notification shade.

START_NOT_STICKY, null intent stops, no boot receiver, no alarm/worker restart. Stop, screen-off, task removal, overlay permission revocation and accessibility disconnection invalidate the session, cancel detector/network/watchdog jobs, clear delayed/rendered comments, remove the Window and stop the FGS. Leaving the Activity for a television app does not by itself end an explicitly requested overlay session.

## Generation, cancellation and fail-closed observation

SessionController serializes updates on the service main scope. Changing mode, station, backend or saved display settings invalidates the generation, cancels the old stream and clears the renderer/queue. Captured stream generations are checked on every event; events from an old generation, another backend or DEMO are rejected by the live path. Free-form network/detection messages are not surfaced in UI or notifications because they may contain URLs, tokens or raw screen text. Local Japanese status labels are used instead.

Automatic observations require the selected origin, a known station, watchingTv=true, a timestamp at least as new as the current session/configuration epoch, no future time, and less than 30 seconds since fresh evidence. Older out-of-order observations cannot overwrite newer evidence. Timer ticks never refresh evidence timestamps. Unknown, non-TV, stale and invalid observations cancel and clear. Identical fresh station observations do not reconnect. Manual selection deliberately has no automatic 30-second TTL.

OSD profile defaults are uncalibrated: OSD_RESOURCE_IDS and LIVE_RESOURCE_IDS are empty. TV_PACKAGES candidates are not verified device support. The detector must recognize calibrated live/player markers and exact station OSD IDs, not broad screen text or all EPG rows. Empty profiles remain Unknown even if a bus observation claims a station. Numeric-only aliases are rejected. The detection module owns its 750ms reread/debounce and foreground-exit checks.

## Settings and validation

SharedPreferences store: nicotv. Shared names follow PreferenceContract. Mode values are manual, accessibility, bravia. TV_PACKAGES, OSD_RESOURCE_IDS and LIVE_RESOURCE_IDS are comma/newline-separated complete names; resource IDs use package:id/name. CUSTOM_ALIASES is a JSON object mapping exact label to catalog jk ID. BRAVIA_CHANNEL_MAP maps only tv: URIs to known jk IDs. JSON is capped at 16KB/100 entries; packages at 32 and resource IDs at 64. Invalid settings are not saved. Empty OSD profile may be saved but never implies supported automatic detection.

Display controls: font 60-200%, opacity 10-80%, speed 50-200%, delay 0-30 seconds, up to 100 NG words of 100 characters each, fixed top/bottom comments on/off. Saving configuration does not independently activate a stopped session.

BRAVIA host must match an address on this device itself and accepts only unambiguous numeric RFC1918 IPv4 (10/8,172.16/12,192.168/16). URLs, ports, hostnames, public IPs, loopback and leading-zero octets are rejected. No network scan or discovery is performed. Mode selection includes an experimental-feature warning and cleartext disclosure. The adapter is invoked only after an explicit session start.

## Credentials and cleartext LAN exception

EncryptedPskStore uses AndroidKeyStore AES-256/GCM/NoPadding with fresh randomized 12-byte IVs. Separate private preferences contain only IV and authenticated ciphertext. The non-exportable AndroidKeyStore key never leaves the keystore. A blank PSK edit preserves the stored value; deletion is a separate confirmed action. The field is never populated with the old plaintext and is excluded from saved UI state and autofill. No PSK or exception contents are logged or shown. Keystore/decryption failure prompts re-registration. Backup is disabled; API31 cloud and device-transfer exclusions cover all app data domains.

The BRAVIA detector uses Sony getPlayingContentInfo at /sony/avContent and X-Auth-PSK as specified by its module. This is HTTP, so PSK is exposed to the local network despite encrypted storage. Use only a trusted LAN and no port forwarding. Consumer BRAVIA tuner support is unverified across models.

The network-security-config allows cleartext at app level because configured numeric IPs cannot be enumerated in a static domain allowlist. This is not itself a private-IP security boundary: the BRAVIA adapter's independent RFC1918 validation and redirect refusal are mandatory. Comment-client must retain its HTTPS/WSS and host validation. No public URL may receive the PSK.

## Overlay Window and remote input

One transparent TYPE_APPLICATION_OVERLAY Window uses FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE. It does not consume TV remote focus or keys. API31+ reads InputManager.maximumObscuringOpacityForTouch; Window alpha is capped at that threshold and 0.8. Transparent pixels alone do not satisfy Android's untrusted touch-occlusion limit. Live opacity is applied once at Window level, with DanmakuView opacity=1; embedded demo opacity is applied in the view. Permission/window failure stops safely and removes the surface.

System security windows, other apps' overlay suppression and OEM protected/sideband video planes can hide overlays. Real broadcast display and remote-input behavior must be checked on actual TV hardware.

## Japanese remote-first UI and isolated demo

Native Activity/Views, dark neutral surfaces, 18-24sp body text, 52-64dp focus targets, strong focus outline/contrast, selection checkmarks, persistent Start/Stop and current state/source. Four panels: viewing, display preferences, advanced permissions/profile, demo. Ten catalog station cards select manual mode explicitly. Accessibility and experimental BRAVIA modes include disclosures. Official and NX provenance remain separate.

The Activity demo is permanently labeled デモ / 通信なし and feeds only local synthetic DEMO-origin comments to the real DanmakuView API. It is not a test of live retrieval or detection. An active live session must be stopped first. Pause, panel departure, live start and Stop cancel/clear the demo. No real user comments or authentication data are fixtures.

## Verification boundary

JUnit/Robolectric API28 covers generation, cancellation, stale/future/replayed/wrong-origin observations, calibration, manual TTL, backend isolation, error/demo separation, settings validation/roundtrip, AES authenticated encryption failure and removal, visible single-use authorization, process-reset, null/unauthorized service start, missing Settings Activity, alpha limits and TV UI structure. Optional native-graphics snapshots are saved only under the assigned temporary directory.

This isolated worktree contains compile-only public-API stubs in comment-client, detection and overlay. Passing app checks is not end-to-end transport, real Danmaku rendering or accessibility QA. Parent integration must test the actual renderer, TV D-pad, overlay grant/revoke, screen-off, task removal, API34/35 FGS rules, real AndroidKeyStore and BRAVIA device behavior. The parent's reserved TV emulator is not touched by this implementer. Exact check commands, exit/EOF evidence and commits are recorded in the implementation report, outside source control.

## Stop-race checkpoint and BRAVIA integration gap

The ImplicitSamInstance warning originally pointed to Context.stopService(Intent(...)), not Handler.removeCallbacks or a Runnable-removal API. Android identifies this explicit Service by component; nevertheless the Activity previously relied on asynchronous Service destruction for controller cancellation. The revised path first revokes the start ticket and SESSION_ACTIVE, synchronously invokes the Service's one stable Runnable to cancel the generation/network/rendering, then asks Android to stop the component using a named Intent. Service creation/destruction registers/unregisters the identical Runnable. Unregistering an old Service cannot detach a replacement Service's handler. Regression tests assert that cancellation/generation invalidation has happened before the platform stop call, stale handler removal is harmless, and Stop invalidates a queued Start. No lint suppression is used.

## BRAVIA independent visibility and same-device guard

The earlier foreground gap is addressed by BraviaVisibilityGate and the updated detector bus behavior, without changing core types. In MODE_BRAVIA the detector independently publishes an ACCESSIBILITY observation with stationId=null, watchingTv based on a freshly inspected allowlisted TV package/live-only marker, and monotonic observedAtMs once per second. Home, guide, missing marker, screen/keyguard or active/default-display ambiguity publishes false. REST watchingTv is never a substitute for this separate visibility evidence. TV_PACKAGES and LIVE_RESOURCE_IDS must be configured; OSD_RESOURCE_IDS is not needed for BRAVIA visibility. Ordinary OSD channel detection retains its separate 30-second evidence TTL.

App start and runtime checks require Accessibility connected, calibrated live profile, overlay permission, screen-on/unlocked state, and a known valid default display. BraviaVisibilityGate accepts only null-station ACCESSIBILITY evidence from at least the gate's start epoch, not in the future, and younger than 2.5 seconds. A 250ms ticker expires evidence even without another event. Only boolean transitions start/stop polling; true heartbeats do not restart REST. A false/expired guard synchronously sends BRAVIA Unknown to invalidate controller generation, window and comment queue BEFORE canceling or joining REST. Reopening waits for prior collectors to finish. Every REST result rechecks generation, current visibility, current platform conditions and observation epoch/time. Closed/reconfigured gates cannot revive selection via late or non-cooperative responses.

The configured host must be an exact, unambiguous RFC1918 IPv4 address found on this Android device itself through public NetworkInterface/Inet4Address APIs (up, non-loopback interfaces). Unknown or another television's IP is rejected, even if it is private. No hidden API, DNS, network scan or discovery is used. The match is checked before starting and while gating/accepting results; inability to enumerate interfaces is unsupported/fail-closed. This requires installation on the actual BRAVIA, not an external box. Models that cannot expose their own address, local REST endpoint or display identity through these public APIs are unsupported. HTTP remains plaintext on the trusted LAN and is still explicitly disclosed.

Start Activity and overlay must target Display.DEFAULT_DISPLAY. Activity start/resume/configuration/focus plus a visible-Activity ticker, service startup/reconfiguration/watchdog and Window attachment validate this boundary. Overlay uses an explicit default-display context (and API30+ overlay window context); unknown/secondary/off displays are rejected rather than guessed. An experimental label remains because TV-specific marker calibration, local endpoint reachability and OEM video-plane behavior require real hardware validation.

Synthetic tests inject clocks, visibility flows, REST flows and platform readiness. They cover REST=0 without guards; Home/TTL clearing before cancellation cleanup; late non-cooperative response rejection; stable true heartbeats; future/old-epoch/wrong-origin/station-bearing evidence; no revival after Stop/replacement; joining old collectors before restart; local-IP equality/mismatch; missing permission/calibration; and default-display rejection. The parent must integrate the corresponding detection implementation and run TV hardware/visual QA; these isolated app tests do not prove OEM support.

The public compile SDK does not expose Activity.onMovedToDisplay. No hidden-API workaround is used: supported configuration/focus/resume callbacks plus a 250ms visible-Activity display check enforce the Activity boundary; overlay attachment independently uses the explicit default-display context. Current StateFlow visibility is read directly again at each REST result, so an already-published Home value cannot be bypassed while its collector is queued.


## First-comment readiness and receipt-relative live delay

The independent APP-RENDER-02 review reproduced first-comment loss with the real immutable renderer: addView returns before visible attachment/layout, while DanmakuView correctly rejects pre-ready ingress. OverlayWindow now owns one closed-on-clear OverlayIngress instance per Window generation. A single stable main-Handler callback checks attachment, positive width/height, isShown and visible window state before any addComment call. It never captures an individual pending comment in an unguarded View.post. The first sole live comment therefore waits for readiness rather than requiring a second network event.

Pre-ready ingress preserves the first 64 accepted comments and rejects further arrivals; it has a fixed 1500ms deadline from Window creation which incoming traffic cannot extend. Failure to become ready closes the ingress, clears/removes the Window and stops safely. Once ready, hide/detach fails closed instead of buffering for a later reshow. Steady-state delayed ingress has at most 500 entries, favors fresh comments at capacity, bounds raw text/ID to 2048/256 UTF-16 units, and drops entries more than 5000ms late. There is at most one coalesced callback: readiness retries at up to 16ms, delayed work rechecks authorization at most every 250ms, and no callback remains when empty.

To preserve receipt-relative delay without modifying the shared renderer API, live delay is owned by this app queue for ALL live comments. Each due time is the monotonic receipt time plus the saved 0-30 second delay; readiness time counts toward it. The live DanmakuView receives delayMs=0 (and opacity=1, because alpha is on the Window). No per-comment preference toggling occurs: the actual engine clears queued glyphs when preferences change. postedAtMs remains untouched and is never compared to the monotonic clock. Embedded offline demo behavior is unchanged.

clear, Stop, permission loss, reconfiguration and window failure close/empty the ingress and invalidate the Window generation before detach. Canceled-but-already-held callbacks are harmless because the old ingress is permanently closed and the old target/token must still match. Settings changes also clear directly in OverlayWindow. Tests use fake readiness, clocks and callback scheduling for sole-first delivery, each readiness prerequisite, before/after-clear, old-view callback, queue bounds, fixed deadline, permission/hide loss, receipt-relative delay and late-wake dropping. These are app-side contract tests: this worktree still has an overlay stub. The parent owns actual OverlayWindow+DanmakuView Android integration tests under app/src/androidTest and visual/device QA. NG/font mismatch fixes belong to the renderer owner, not this change.


## Ingress clock freshness, strict readiness deadline and fair draining

APP-RENDER-FIX-01 exposed a stale pump-wide clock: a slow first delivery could make the second item more than 5000ms late while the old sample still admitted it. Each item now rechecks the current generation/session/permission and complete readiness, then samples elapsed time again immediately before its due/lateness decision. Work performed by permission/readiness checks is included. The next scheduled wait also uses a fresh clock; a renderer call cannot postpone a later item's original due time by reusing an earlier sample. Items exactly 5000ms late remain permitted; those 5001ms late are discarded.

Ingress offering only queues/coalesces work; it never drains inline, so a synchronous producer burst cannot bypass the main-loop budget. One stable callback drains at most 16 entries (including expired entries), or until 4ms has elapsed between entries, then posts a continuation at least 1ms later. This yields to queued Stop, display and permission events; every resumed batch rechecks all conditions. A single slow synchronous renderer/platform call cannot itself be preempted, so the elapsed budget is a between-items limit, not a hard per-call real-time guarantee. Empty/closed queues retain no callback, and old-generation callbacks remain inert. Immediate-ready zero-delay comments are delivered on the next scheduled callback, not inline.

APP-RENDER-FIX-02 is resolved with a strict pre-first-readiness deadline independent of the ready boolean. Both before and after potentially expensive platform checks, the clock must still be earlier than createdAt + 1500ms before first readiness can be accepted. Observed readiness at 1499ms is accepted; at 1500 or 1501ms it closes fail-safe, even if the surface has just become ready. Once readiness was established on time, normal receipt-relative delays up to 30 seconds still work.

Regression tests include a 6001ms clock jump during the first renderer call, expensive permission/readiness checks, exact lateness/deadline boundaries, 16-item and 4ms yields, Stop/new ingress between batches, permission/readiness loss or close during a delivery, and fresh scheduling of a later due item. The fake scheduler never rewinds time after an expensive callback. These remain isolated app tests; actual renderer and main-loop/device frame-pacing verification belongs to parent integration, including rerunning the parent-reported Android14 first-comment/clear/D-pad tests after this additional fix.
