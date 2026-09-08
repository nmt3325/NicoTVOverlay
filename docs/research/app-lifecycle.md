# App lifecycle, permissions and TV UI

## Contract and capability boundary

The app uses the shared core API, NicoLiveCommentSource, NxJikkyoCommentSource, StationDetectionBus, BraviaStationDetector and a single DanmakuView overlay. Manual selection remains available for all ten catalog entries. Catalog jk IDs are commentary identifiers, not remote-control key numbers. Generic current-channel detection is not promised for every TV. Install on the television itself: an external TV box cannot overlay the TV internal tuner on another input.

Official Niconico is the default backend. NX-Jikkyo is a separate explicitly confirmed service with continuously visible provenance. There is no automatic fallback between backends, and live errors never substitute synthetic comments.

## Explicit session lifetime

NicoTvApplication resets PreferenceContract.SESSION_ACTIVE=false on process creation. RuntimeSession holds only an in-memory, single-use start ticket issued by an Activity start action while visible and focused; it is never restored from preferences or instance state. OverlayService is not exported. Accessibility binding alone cannot start a session.

The Activity checks SYSTEM_ALERT_WINDOW. Accessibility mode also checks the explicitly enabled service. BRAVIA requires a configured private numeric IPv4, PSK and exact channel URI map. API33+ POST_NOTIFICATIONS is requested, but denial is not described as an OS prohibition on starting an FGS. A foreground notification is still supplied, while ordinary notification updates check permission. Missing or denied settings Activities are caught and explained.

Foreground promotion is immediate and precedes SESSION_ACTIVE=true and any detector/client work. API34+ uses specialUse, FOREGROUND_SERVICE_SPECIAL_USE and an explicit subtype property. The notification has an immutable explicit Stop PendingIntent. A large Stop button stays at the top of the TV launcher, including devices without a useful notification shade.

START_NOT_STICKY, null intent stops, no boot receiver, no alarm/worker restart. Stop, screen-off, task removal, overlay permission revocation and accessibility disconnection invalidate the session, cancel detector/network/watchdog jobs, clear delayed/rendered comments, remove the Window and stop the FGS. Leaving the Activity for a television app does not by itself end an explicitly requested overlay session.

## Generation, cancellation and fail-closed observation

SessionController serializes updates on the service main scope. Changing mode, station, backend or saved display settings invalidates the generation, cancels the old stream and clears the renderer/queue. Captured stream generations are checked on every event; events from an old generation, another backend or DEMO are rejected by the live path. Free-form network/detection messages are not surfaced in UI or notifications because they may contain URLs, tokens or raw screen text. Local Japanese status labels are used instead.

Automatic observations require the selected origin, a known station, watchingTv=true, a timestamp at least as new as the current session/configuration epoch, no future time, and less than 30 seconds since fresh evidence. Older out-of-order observations cannot overwrite newer evidence. Timer ticks never refresh evidence timestamps. Unknown, non-TV, stale and invalid observations cancel and clear. Identical fresh station observations do not reconnect. Manual selection deliberately has no automatic 30-second TTL.

OSD profile defaults are uncalibrated: OSD_RESOURCE_IDS and LIVE_RESOURCE_IDS are empty. TV_PACKAGES candidates are not verified device support. The detector must recognize calibrated live/player markers and exact station OSD IDs, not broad screen text or all EPG rows. Empty profiles remain Unknown even if a bus observation claims a station. Numeric-only aliases are rejected. The detection module owns its 750ms reread/debounce and foreground-exit checks.

## Settings and validation

SharedPreferences store: nicotv. Shared names follow PreferenceContract. Mode values are manual, accessibility, bravia. TV_PACKAGES, OSD_RESOURCE_IDS and LIVE_RESOURCE_IDS are comma/newline-separated complete names; resource IDs use package:id/name. CUSTOM_ALIASES is a JSON object mapping exact label to catalog jk ID. BRAVIA_CHANNEL_MAP maps only tv: URIs to known jk IDs. JSON is capped at 16KB/100 entries; packages at 32 and resource IDs at 64. Invalid settings are not saved. Empty OSD profile may be saved but never implies supported automatic detection.

Display controls: font 60-200%, opacity 10-80%, speed 50-200%, delay 0-30 seconds, up to 100 NG words of 100 characters each, fixed top/bottom comments on/off. Saving configuration does not independently activate a stopped session.

BRAVIA host accepts only unambiguous numeric RFC1918 IPv4 (10/8,172.16/12,192.168/16). URLs, ports, hostnames, public IPs, loopback and leading-zero octets are rejected. No network scan or discovery is performed. Mode selection includes an experimental-feature warning and cleartext disclosure. The adapter is invoked only after an explicit session start.

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

BRAVIA foreground limitation (parent integration task): currently there is no independent foreground/display-target check in BRAVIA mode. BraviaStationDetector.watchingTv describes the authenticated REST response, not the local foreground app or the actual visible display plane. A television may still report a tv: source while Home or another app is in front. The current Accessibility contract runs only in MODE_ACCESSIBILITY and therefore cannot guard the BRAVIA path. Screen-off, explicit Stop, task removal, overlay revocation, REST Unknown/non-TV and 30-second freshness remain guarded, but Home-with-tv:-REST is NOT covered. The experimental mode's confirmation and advanced settings now explicitly warn users to Stop before leaving TV viewing.

Parent should either add an independently authorized fresh foreground/display-target signal and require it together with the REST station observation (including Home/non-TV/stale fail-closed tests), or keep BRAVIA automatic overlay disabled until that safety gate is integrated. Do not describe the present experimental mode as universally or foreground-safely automatic. This child does not change core/detection contracts. Emulator/visual QA, including real Danmaku rendering, is assigned to parent after integration; saved Robolectric snapshots are not a substitute.
