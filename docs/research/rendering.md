# Native live comment rendering

## Responsibility boundary and reference

`DanmakuView` owns a transparent, non-interactive Android Canvas and animation lifecycle. `DanmakuEngine` is Android-free Kotlin: bounded input preparation, monotonic scheduling, measured geometry, lane admission and expiry. It receives a `TextMeasurer` rather than knowing about `Paint`. No Flow, transport, HTML/WebView, WindowManager, foreground service or screen capture is present in this module. App owns permission, overlay window flags, service lifecycle and station-generation filtering; call `clearComments()` at every station/mode transition.

Reference inspected: [NCOverlay renderer.ts, pinned a41a03be8443ce850780e1121fe9f4e6fbf73e59](https://github.com/Midra429/NCOverlay/blob/a41a03be8443ce850780e1121fe9f4e6fbf73e59/src/ncoverlay/renderer.ts). Its separation of renderer from source/video integration, `performance.now()` interpolation, offset/opacity control and frame start/stop informed the design. This is an independent native implementation, not a port/copy of its renderer or NiconiComments library. Live reception is not a video/vpos/archive replay timeline.

## Display and admission

- Native bold sans-serif with Japanese system fallback, anti-aliasing, white by default, opaque comment RGB with a black rounded outline. Text alpha follows sanitized overlay opacity. Caller-provided HTML-looking text is drawn literally, not interpreted.
- At 1080p, normal text starts around 45 px; base size is `max(24 * density, height / 24) * systemFontScale`. Small/normal/large ratios are 0.75/1/1.4. Respect bounded system and application font scaling. Font metrics and text bounds include fallback glyphs, descenders and overhang; measurements are cached per visible glyph, not per frame. No per-comment bitmap cache.
- At least 3% safe inset on all four edges. Clip drawing to the safe rectangle. TOP scans downward; BOTTOM scans upward on the same grid and aligns within its reserved row. The grid uses conservative largest-font spacing and expands reservations if a measured glyph requires multiple rows.
- All positions share lane occupancy. Fixed-versus-fixed and fixed-versus-scrolling reservations cannot occupy the same vertical band. A new scrolling follower needs a safe horizontal gap both now and at the earlier expiry. Since relative motion is linear, checking both endpoints prevents a wider/faster follower from catching a leader. Insufficient space means drop, not overlap or indefinite wait. This intentionally trades density for legibility.
- Scroll travels right to left in 8 seconds at speed 1; duration scales inversely with speed. TOP/BOTTOM remain for 4.5 seconds. Frames sample position from absolute monotonic times, so frame drops do not slow the stream.

## Time, limits and settings

`SystemClock.elapsedRealtime()` is the only View timeline. `postedAtMs` may be a server epoch, absent or malformed and is intentionally ignored for scheduling; it is never subtracted from elapsed time. `addComment` records reception time under the View's serialization lock. Positive delay is reception-relative and capped at 30 seconds. Delayed items keep that due time as their start even if the UI wakes late. Items more than 5 seconds late after their due time are dropped; visible glyphs expire at their absolute end. Reattach/visibility loss always clears stale state instead of replaying it. Saturating time addition and monotonic sampling avoid arithmetic overflow/backward movement.

| Input | Policy |
| --- | --- |
| fontScale | 0.75–2; non-finite → 1 |
| opacity | 0–1; non-finite → 0.8; zero admits no comments |
| speed | 0.5–3; non-finite → 1 |
| maxVisible | 0–120; zero disables admission |
| delayMs | 0–30,000 ms |
| showFixed | false rejects TOP/BOTTOM |
| pending | 500 maximum; pressure removes oldest, keeping latest live reception |
| dedupe | 1,024 origin+ID keys, 60-second receipt-relative TTL; blank IDs not deduped |
| ID | reject more than 256 UTF-16 units |
| raw text | reject more than 2,048 UTF-16 units before expensive work |
| displayed text | first 160 Unicode code points plus ellipsis; never split surrogate pairs |
| measured width | reject scroll wider than 3 safe widths; fixed wider than one safe width |
| NG | first 64 entries; bounded normalized 64-code-point terms |

Text normalization collapses whitespace/newlines into spaces, removes control and format characters (including bidi controls), preserves emoji ZWJ/ZWNJ and variation/combining marks, replaces invalid isolated surrogates, and uses Unicode NFC. NG is **case-sensitive, NFC-normalized, literal substring matching** against displayed normalized text. No regex, locale-dependent case folding, NFKC/full-width folding or HTML parsing. Empty normalized NG entries are ignored. Text processing, matching, queue sizes and geometry work are bounded. Already-allocated upstream strings/lists are outside this module, but huge inputs are neither copied wholesale nor retained here.

Changing sanitized preferences clears displayed, pending and dedupe state when values differ, rather than moving existing comments into possible collisions. A resolution, density or system font-scale change clears/recomputes geometry. `clearComments()` unconditionally clears all three stores.

## Animation/lifecycle and integration

Only attached, shown, window-visible Views accept comments. Hidden/detached input is dropped. While scrolling is active, one Choreographer callback drives invalidation; only fixed comments or delayed pending work use one earliest-deadline Handler wake. Empty/detached/hidden Views have neither callback and cannot spin. The last expiry invalidates once to remove the previous display list.

Public ingress is serialized. Worker-thread comment bursts create at most one UI refresh message, not an unbounded Handler message per comment. All delayed comments reside in the bounded engine queue; callbacks never capture a `LiveComment`. `clearComments`, preference/lifecycle changes advance a generation, and callbacks from old generations cannot restore old content. Do not treat this as a substitute for app-level latest-stream generation checks on network deliveries occurring after a station switch.

The renderer sets no layer/window alpha; the app must separately obey Android's obscuring-opacity rules for `TYPE_APPLICATION_OVERLAY`. Per-glyph alpha is not equivalent to the window alpha restriction. The app must stop the overlay on screen-off per the service contract. This renderer does not claim to detect the currently watched TV program.

## Verification boundary

Pure JUnit exercises lifetime, entry/catch-up collision, dense mixed-position traffic, color/size, TOP/BOTTOM competition, Unicode/oversize text, bounded queues/dedupe, receive-relative delay, late TTL, clear, malformed preferences/times and safe resize. Robolectric uses a native Canvas to verify transparent pixels, white fill/black outline and safe top inset; lifecycle tests cover empty/expired no-loop, fixed one-shot wakes, hidden/detached states, delayed clear, worker ingress and font/resolution changes.

Automated pixel/geometry tests are not human visual verification. This implementer has not operated the shared TV emulator or verified OEM/physical-TV typography, compositor behavior, frame pacing or viewing-distance readability. Parent integration must visually inspect Japanese mixed-size scrolling, TOP/BOTTOM, white and colored text over both bright/dark video at TV resolution, and confirm no clipping/overlap, adequate breathing room, transparent idle frames and no stale burst after hide/reattach. No screenshots or fixture UI are presented as real tuner validation.
