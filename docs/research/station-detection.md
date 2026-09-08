# Station detection: calibrated, explicit, fail-closed

## Supported boundary

An ordinary sideload APK cannot query the channel tuned by another application's TV Input Framework session through the public SDK. This module uses **no hidden API, reflection into TIF, root, watched-program provider, privileged grant, screen capture, key interception, gesture injection, or LAN discovery**. Manual selection remains a separately labeled application mode, not a silent automatic fallback.

Accessibility is device/app dependent. The user must explicitly enable the service in system settings, select Accessibility mode, calibrate its IDs, and press Start. Connection alone does not start an overlay, foreground service, comment client, or network request. `StationDetectionBus` is an in-process StateFlow, initially Unknown. The app owns Start/Stop and resets persisted `SESSION_ACTIVE` on fresh process startup; this module never grants that authorization itself.

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

While an authorized session is active, a **1 s foreground heartbeat** acquires the active root and inspects only package/window identity, never station strings. It catches Home/other-app departure even though their accessibility events are package-filtered out. Root-null, off-list foreground, screen-off broadcast, service interruption/disconnection, Stop and mode changes clear selection. Lock/unusable-display conditions are rechecked on every scan and heartbeat. Foreground departures/lock without a delivered event are bounded by the heartbeat interval, not claimed to be instant OS notifications.

`observedAtMs` is `SystemClock.elapsedRealtime()`, not wall clock. A station has a **30 s evidence TTL**. Foreground heartbeat does not extend that timestamp; only a fresh calibrated station read can. A separate deadline clears evidence at 30 s without a root read or waiting for the next heartbeat (subject to normal Android main-thread scheduling). A transient OSD therefore gives at most bounded inference; it does not guarantee continuous channel knowledge after it disappears. A scan that sees the missing OSD/live marker clears immediately. Multi-display/PiP and OEM hardware-plane behavior are unverified; no claim of support is made.

The manifest has the system binding permission `BIND_ACCESSIBILITY_SERVICE`, exported service plus accessibility intent/metadata, and `isAccessibilityTool=false`. The XML starts with no event types and a disabled sentinel package. Runtime service information selects only window-state/content/windows events and `FLAG_REPORT_VIEW_IDS`. No interactive-windows, screenshot, gesture, touch exploration or key-filter capability is requested. API33+ active-root reads disable descendant prefetch; older Android versions control their own prefetch even though this reader does not inspect off-list contents.

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

Tests include deterministic fake-clock policy and bounded synthetic-tree fixtures, API28 Robolectric service lifecycle/root fixtures, and local MockWebServer transport. They cover opt-in/empty package/calibration gates, exact normalization/custom aliases, titles/recordings/EPG ambiguity, limits and privacy of node reads, rapid A→B→C and old generations, root-null/Home, Stop/mode change, screen/lock, event storms, evidence expiry, URI/auth/error/input/host validation, redirects, oversized/slow response and cancellation. These are synthetic fixtures, **not actual tuner or Sony interoperability tests**.

Before declaring a model supported, validate the real package/resource IDs and live-only semantics, transient OSD behavior, EPG/recording/input/PiP/app departure, accessibility/restricted-settings UI, permission/service loss, actual station→commentary mapping, and (Sony) supported method/field fixtures and lack of stale TV evidence while in another input/app/standby. Overlay composition above tuner/HDMI/HDR/DRM surfaces and remote controls belong to integrated physical-TV testing.

## Primary documentation supporting the boundary

- [AOSP TvInputManager (Android 15)](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/media/java/android/media/tv/TvInputManager.java): current tuned information is hidden/system API.
- [AOSP platform permissions](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/res/AndroidManifest.xml): tuned-info privilege and accessibility binding boundary.
- [Android accessibility service guide](https://developer.android.com/guide/topics/ui/accessibility/service) and [AccessibilityServiceInfo](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo): user enablement, capability and package filters.
- [Sony REST API reference](https://pro-bravia.sony.net/remote-display-control/rest-api/reference/), [guide](https://pro-bravia.sony.net/remote-display-control/rest-api/guide/) and [structure](https://pro-bravia.sony.net/remote-display-control/rest-api/structure/): getPlayingContentInfo, PSK and error/result envelope. Professional-display documentation does not prove all Japanese consumer-model fields.
