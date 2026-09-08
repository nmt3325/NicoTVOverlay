# Live comment receive implementation

## Public contract and provenance

- `NicoLiveCommentSource(OkHttpClient = OkHttpClient()) : CommentSource` receives anonymously **from official NicoNico services directly**. It never calls NX or a past-log API.
- `NxJikkyoCommentSource(OkHttpClient = OkHttpClient()) : CommentSource` is a separate, explicitly selected backend. All delivered comments use `CommentOrigin.NX_JIKKYO`, including `nicolive:` mirrors. The current core contract does not represent mirror/original as separate origins; neither is presented as official-direct.
- Both expose a cold `Flow<StreamEvent>`. Every collection owns its sockets, HTTP calls, child jobs, timers, duplicate cache and retry state. Stop/cancel closes those resources without shutting down the caller's shared OkHttp dispatcher or connection pool.
- No login, cookie import, posting, archive acquisition or silent fallback is implemented. States contain only fixed local messages, never an upstream exception, opaque URI/token, user id or comment text.

## Official path

1. Resolve the catalog's channel using `https://live.nicovideo.jp/watch/<ch>` on **every reconnect**. Parse only the `script#embedded-data` `data-props` attribute, decoding HTML entities once. Require `program.status=ON_AIR`, a valid `lv` id and a validated watch socket URL.
2. Open the watch WSS and send `{"type":"startWatching","data":{"reconnect":false}}`, without requesting video. Respond to JSON `ping` with `pong`; renew `keepSeat` at the server-provided interval.
3. Use `messageServer.data.viewUri`, not `akashicMessageServer` and not the historical `room` protocol. Start at `at=now`.
4. Read unsigned varint-length-delimited `ChunkedEntry` frames from the HTTP body; HTTP chunk boundaries have no significance. Subscribe to `segment.uri` HTTP streams containing `ChunkedMessage` frames, while continuing to read the view.
5. A next-only first response and normal EOF are valid. Follow the exact `next.at` cursor. Do not synthesize cursor timestamps or hard-code the observed 16-second segment / 32-second view durations. Missing, repeated or cyclic cursors cause a bounded reconnect to the current program.
6. `previous`/`backward`, state/signal messages and unknown non-chat messages are ignored. Handle both `chat` and `overflowed_chat`. Use `meta.at` for posting time (not arrival time or `vpos`), map named/full RGB colors, position and size.
7. Respect refreshed `schedule.end`, `END_PROGRAM`, `NOT_ON_AIR`, watch reconnect/error/disconnect and HTTP failures. Cancel the old session and resolve watch/ch again, so daily program ids are never cached indefinitely. Timers derive from server schedule rather than an assumed daily boundary.

### Schema and license

The 15 `.proto` files in `comment-client/src/main/proto/dwango/` are byte-for-byte copies from:

https://github.com/n-air-app/nicolive-comment-protobuf/tree/2e852e08df016888aefa602ec6c5be6a558eb3d9/proto

Upstream trailing whitespace and final blank lines are intentionally preserved with the official bytes; a full `git diff --check` reports those vendor lines. The authored Kotlin, tests, documentation and license pass the scoped whitespace check.

The upstream MIT license is preserved at `comment-client/src/main/proto/LICENSE`. The existing Gradle protobuf plugin generates Java **lite** classes with protoc 4.31.1 / protobuf-javalite 4.31.1. Generated sources are build output, not hand-written field guesses. Known UTF-8 string validation and unknown-field handling are performed by the generated parser; recursion is bounded to 32.

## NX path

The separately selected NX backend opens `wss://nx-jikkyo.tsukumijima.net/api/v1/channels/<jk>/ws/watch`, maintains the same watch keepSeat/pong lifecycle, and uses `room.data.messageServer.uri`, `threadId` and `yourPostKey` for its JSON comment socket. It sends the observed legacy NicoNico-compatible ping/thread request envelope with `res_from=0` (no initial history) and waits for a successful matching thread response before marking LIVE. Timestamps use `date` + `date_usec`; command colors/size/position come from `mail`. Duplicate thread/comment numbers are bounded and old comments are suppressed. The watch schedule closes quiet comment sockets on rollover; zero comments alone is not treated as a failed connection.

Protocol reference (read-only receive design; posting paths are not used):
https://github.com/tsukumijima/NX-Jikkyo/tree/f1f8594069d563168484fc7c7614e92f62204a78

## Resource, safety and retry policy

- Service-role URL validation occurs before any connection: official HTML is `live.nicovideo.jp`; official watch uses the `.live2.nicovideo.jp` service namespace; NDGR is the observed `mpn.live.nicovideo.jp`; NX is exactly `nx-jikkyo.tsukumijima.net`. Require HTTPS/WSS, standard TLS port, no userinfo, fragment, controls or backslash. The list fails closed on a service migration.
- Automatic HTTP/WSS upgrade redirects are disabled, including same-host redirects; no unvalidated redirect is followed. Caller cookies, authenticators, application/network interceptors and event listeners are not propagated to this anonymous receiver.
- Connect/write timeout 15 seconds, HTTP body read timeout 60 seconds, streaming call timeout disabled. Cancellation actively cancels each Call, including a blocked body/header read. Channel HTML resolution and watch handshake each have a 20-second deadline, and a 90-second watch-message idle deadline coexists with WebSocket protocol pings.
- HTTP/protobuf frames are bounded to 1 MiB before allocation; malformed/truncated/overlong prefixes fail. HTML is bounded to 4 MiB. Invalid protobuf frames are skipped with an eight-consecutive-error budget; unknown fields are allowed. JSON is bounded to 64 Ki UTF-16 units and nesting 48. The WS callback queue is bounded to 64, and oversized/binary/invalid-Unicode messages close it.
- OkHttp 4.12 assembles a WS message before the listener sees it: the listener limit bounds JSON work and queued messages, **not peak allocation inside OkHttp for a malicious giant WS frame**. The incremental pre-allocation frame limit applies to NDGR HTTP. Only validated TLS service hosts may supply these inputs.
- At most four segment subscriptions; a 128-entry URI cache prevents duplicate subscriptions. Overload reconnects to the live edge, rather than creating unbounded waiting jobs. A small minimum inter-view request spacing prevents hot EOF loops.
- A 4,096-entry id cache is scoped by program (or NX thread). Start/reconnect cutoff is one second before attempt start; comments older than 20 seconds or over five seconds in the future are dropped. Accurate device wall-clock time is required. Text is limited to 2,048 UTF-16 units and eight newlines; empty/invalid-Unicode/unsafe control text is dropped.
- The Flow output queue holds at most 256 events and drops oldest entries when a collector is slow. It must not become a delayed backlog on the overlay.
- Retry waits use 2/4/8/16/30 seconds plus up to one second of jitter, reset after a stable minute. Server `waitTimeSec` and numeric/date `Retry-After` are lower bounds. No-permission responses terminate with ERROR; unavailable/no-program/congestion states have slower explicit retry. No upstream diagnostic string is forwarded.

## Verification

Normal CI command: `./gradlew --no-daemon :comment-client:test`. The normal suite uses generated synthetic protobuf, virtual-time sessions and MockWebServer; the live test is skipped unless opted in. Tests cover fragmented framing, malformed lengths/UTF-8, unknown fields/enums, style and timestamp mapping, deduplication, history/future/empty drops, URL restrictions/redirect refusal, blocked HTTP cancellation, concurrent cursor progress while a segment has no response, large malformed HTML/WS inputs, failed WS upgrade Retry-After, watch heartbeats and server wait, normal cursor EOF, rollover, segment/output bounds and distinct NX provenance.

Optional real official smoke: `NICOTV_LIVE_SMOKE=1 ./gradlew --no-daemon :comment-client:test --tests dev.nicotv.comment.LiveSmokeTest --rerun-tasks`. This observes jk4 for 80 seconds and asserts both official LIVE and real comment delivery. Only UTC timestamps, source/station, state counts and comment count are printed. Real comments, user ids and opaque URLs/tokens are never persisted in fixtures or logs. Run Gradle under the orchestration lock when using a shared runner.

Real daily rollover, extended multi-hour operation, all ten channels' streaming bodies, and Android TV/OEM tuner behavior are not claimed by the deterministic tests or a short jk4 smoke. NX is validated against the researched protocol and synthetic sessions; do not equate that with a long-duration NX live test. Renderer timing, filtering preferences and generation switching are the app/overlay modules' responsibilities.

### Observed implementation checks (2026-09-08 UTC)

- Normal final run: **29 deterministic/MockWebServer tests passed**, zero failures/errors; the one opt-in live test skipped by default.
- Final official jk4 smoke: **15:05:41–15:07:01 UTC**, 80 seconds, RESOLVING → CONNECTING → LIVE, **5 delivered comments**, all `NICONICO`. No reconnect was needed in this short window.
- An earlier real implementation run also reached LIVE and delivered 7 comments in 80 seconds (14:58:56–15:00:16 UTC), before the final concurrency/parser hardening. These are implementation measurements, not reuse of the preliminary research probe.
- The final generated-proto compile, unit suite and separately opted-in smoke all completed successfully. Detailed command/exit/EOF evidence and sanitized logs are retained in the implementation report outside the source tree.
