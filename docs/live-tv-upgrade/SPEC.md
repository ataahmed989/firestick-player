# Live TV + Multiview upgrade — FINAL build spec

Source material: `docs/live-tv-upgrade/plan-and-reviews.json` (the six findings, the plan, two adversarial critiques). This spec is
the decision record: where it differs from the plan, THIS SPEC WINS. Read the plan item of the same id for detail
(file:line anchors, rationale), then apply the amendments below.

Repo `/home/user/firestick-player`, branch `claude/blissful-mayer-2nf8z9` (base 5e152ca = release 1.0.374).

## Ground rules (every item)
- Lean: nothing per frame, no start-up cost, no new background jobs, no new dependencies, no new preferences or DB
  changes (storage compatible both ways). New timers run only while a channel/Multiview is open and are removed on
  close/suspend/release. Never post to a released player or a quit looper.
- Decisions go in small PURE classes with unit tests (JUnit4, like app/src/test/java/app/fireplayer/playback/*Test.kt).
  Glue in PlayerHost / MultiviewPlayer / Multiview / HomeActivity / LivePage stays thin.
- Logs: never a link, IP, credential, channel name or playlist name. Use "a picture", "playlist <id>", codes.
- User-facing text in plain words, like the existing live messages (LiveRefusal.message, waitReason, Status.error).
- Keep behaviours the user relies on: quick channel return, PiP on Home/Search, Multiview focus/sound follow
  (volume 0/1 switch at once), captions choice, Live TV buffer setting, favourites, guide speed.
- Build must pass `./gradlew :app:compileDebugKotlin` and the touched tests; the last engine stage runs the full
  `./gradlew :app:testDebugUnitTest`.
- Commit messages: a plain-English summary line, then bullet details, then exactly these trailer lines:
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_01CeAGQc19KZMJTFxzJrjwSq`. Never push (the lead pushes).

## Decisions from the user (2026-11-28)
- 3 pictures in the grid: two on top, the third CENTRED underneath, "Add a channel" as a HALF-SIZE button to its right
  (ADD_SCALE = 0.5; Box(718, 338.25, 232, 130.5) dp). 4 pictures: plain 2x2 grid.
- Gap 8 -> 6 dp (LiveWidths.MV_GAP), picture corner radius 12 -> 8 dp (LiveWidths.MV_RADIUS). The Add button's
  corners match the pictures (8 dp), not the 15 dp panel radius.
- "One large on top": KEEP the design and proportions; only the smaller gap applies (large Box(172,6,616,346.5),
  small 312x175.5 at y 358.5; x 165/483 with 2 small, 6/324/642 with 3 small). No Add button in that mode (as today).

## Lead decisions (small details)
- D-pad: drop the plan's TIE tolerance (strict `<` already sends exact ties to the earlier stop; keep/added tests pin it).
  * Down from a top picture prefers a PICTURE that overlaps it horizontally over the Add button
    (so top-right Down -> the centred bottom picture; top-left Down -> bottom picture). Bottom Right -> Add;
    Add Left -> bottom; Add Up -> top-right; bottom Left -> top-left.
  * Up from the centred bottom picture -> the top picture the viewer came down from, when known; otherwise top-left.
    Implement the memory in the pure layer if focus moves go through it (MultiviewRules/MultiviewModel); if it would
    need Multiview.kt, the layout agent must NOT touch Multiview.kt: leave the default (top-left) and report.
- Off-screen delay before the hidden live picture lets go of its video decoder: 60 s.
- mv-silent-audio: DROPPED this round (risk to the instant sound follow).
- ENDED_FIRST_MS: 300 -> 150 ms (not 50) until measured on the Max.
- chain-on-close: built LAST as its own commit; kept only if the Max test passes (lead decides; may revert).

## Items, final form

### release-order (must)  [plan item + amendments]
- Always remove PlayerHost's/MultiviewPlayer's listener BEFORE release(), and cancel any pending retry/stall
  runnables bound to that player, so a release-timeout ERROR_CODE_TIMEOUT never reaches our handler and no stray
  retry fires on the next player.
- Keep today's order (clear surface, then release) for a HEALTHY player. Skip the surface clear only when the player is
  SUSPECT: its ThreadBeat (player/ThreadBeat.kt) has been silent >= 1 s right now, or DecoderHealth is suspect/stuck.
  That removes the 2000 ms detach wait exactly where it happened tonight without changing healthy closes.
- Do NOT change Media3's detach-surface timeout (leave 2000 ms) or the release timeout (500 ms) in this round.
- Time each release(); if it took >= ~450 ms (the release timed out), tell DecoderHealth `releaseTimedOut(thread)`
  keeping a reference to that player's playback thread (`playbackLooper.thread`).
- Multiview close/suspend: release pictures one per main-loop message (post each), so key events run between them.
- Remove the stray-retry hazard on player swap (playerFor) too.
- Unit tests for any pure part (e.g. the suspect rule).

### decoder-health (must)  [plan item + both critiques' amendments]
- New pure, in-memory `DecoderHealth` (process singleton object or owned by PlayerHost companion; shared by single
  view and Multiview). States OK / SUSPECT / STUCK. No persistence.
- SUSPECT on: a timed-out release; an ERROR_CODE_TIMEOUT coming from a surface detach/attach.
- STUCK only on a video-decoder-specific confirming signal:
  * a player with an open stream whose video track is known but whose video decoder has not started
    (DecoderCounters.decoderInitCount unchanged after ensureUpdated) while its ThreadBeat is silent >= 3 s
    (the playback thread is blocked creating/flushing the codec); or
  * a SUSPECT state followed by the next fresh player's video decoder not starting within the first-picture window; or
  * the picture-watch DECODER verdict (below).
  Thread silence AFTER frames were playing is NOT decoder-specific: route it to the existing FreezeWatch-style
  neutral handling, not the decoder text (FreezeWatch/ThreadBeat document Fire OS sound-output blocks on the Plus).
- While STUCK: no AUTOMATIC player builds anywhere (single view auto-resume, reconnect retries, Multiview retries,
  picture-watch reopens). The message goes in the existing Status.error field, e.g.
  "The TV's video decoder isn't responding.\nRestart the Fire TV: hold Select and Play/Pause for 5 seconds."
  (match existing tone/format). A viewer action (OK/Play, channel change) may build at most ONE player per stuck
  episode, and only when no earlier stuck-era player's playback thread is still alive (Thread.isAlive), or 30 s have
  passed. togglePause/goLive while player == null and stuck: one retry via the normal open path, then the message.
- Clear STUCK only when a decoder initialised AFTER the mark renders a first frame (compare decoderInitCount /
  init time against the mark), or by the self-check: ~5 s after a timed-out release, if that player's playback
  thread is no longer alive, clear (log it). One delayed check, nothing per frame.
- ERROR_CODE_TIMEOUT is never retried on the same player: rebuild through openStream (gated by DecoderHealth).
- Process restart loses the state (after an ANR). Accept and document: the first player of the new process may block;
  the first-picture watch must then show the message with exactly one blocked player.
- Live keeps NOT gating on DecoderCheck (that's a film-only start-up check; adds start-up cost).
- Pure unit tests: suspect vs stuck sources, the one-viewer-build-per-episode limit, clearing only on a decoder made
  after the mark, the self-clear, Multiview pictures don't clear it with an old decoder.

### picture-watch (must) — agreed change 2, extended  [plan item + amendments]
- New pure `LivePictureWatch` judged every 4 s by one main-thread Runnable per player (single view) and ONE shared
  Runnable for all Multiview pictures (start in Multiview enterWith/resume, remove in closeAll/suspend).
- Inputs per check: DecoderCounters after ensureUpdated (decoderInitCount, queuedInputBufferCount, rendered +
  skipped + dropped output count), playbackState, playWhenReady, has video track, ThreadBeat silence, time since the
  last open / reconnect / jump (LiveTiming has the marks), last position discontinuity or video format change.
  Use counters, not onRenderedFirstFrame (doesn't fire while the surface is hidden) and not onVideoDecoderInitialized
  (doesn't fire when the codec is reused across channel changes).
- Verdicts:
  * NO_PICTURE: 15 s after an open/reconnect/jump with zero new output frames while a video track is known.
    If decoder-specific (thread silent >= 3 s, or input queued with 0 output on two fresh players in a row) ->
    DECODER: DecoderHealth stuck + message. Otherwise per channel: one quick reopen, then the plain line
    "This channel isn't sending a picture right now" with the existing slow retry ("Trying again every 30 seconds").
  * FROZEN: READY + playWhenReady + video known + output-frame counter unchanged for 20 s, and no judgement within
    30 s of a discontinuity / format change. Then reopen through the timed letGo with DecoderHealth marked SUSPECT
    (a frozen decoder is likely dead; expect the decoder message if the next decoder won't start).
  * Channels with no video track (radio): never judged. Key "sparse"/audio-only per channel for the life of the
    process in a small in-memory set, not per player.
- While the video track is deliberately off (offscreen-decoder) the watch is paused for that player.
- Multiview: MultiviewPlayer needs the same counters (read DecoderCounters; add an AnalyticsListener only if needed).
  Per-picture messages use the existing per-picture error display; logs say "a picture".
- Multiview keepScreenOn: clear it when EVERY picture shows an error or the decoder message; set again when any plays.
- Logs: never names/links.

### offscreen-decoder (must) — agreed change 1  [plan item + amendments]
- When the Home/Search live picture (PiP) has been out of sight (covered or scrolled away) for 60 s and the player is
  healthy (output frames moved within the last 10 s), disable the video track
  (trackSelectionParameters.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)); sound plays on. Log once, no names.
- Bring it back at the FIRST sign of a return: the Live TV tab focused, Home's live row coming into view, PiP to full
  screen, held Back — not only when the picture is seen. Mark it as a jump (LiveTiming startJump) so it logs
  "picture back in N ms" and isn't counted as a stall. On plain TS this reconnects the stream (~1 s, sound too) and
  lands at live: accept; if the reconnect is refused, one quick silent retry before LiveRefusal speaks.
- While video is off keep the last known videoSize and video Format in Status, so HomeActivity's videoSizeListener
  doesn't lay the surface out twice (HomeActivity.kt ~1474-1477).
- openStream and playerFor always clear the flag (setTrackTypeDisabled(VIDEO, false)) before use; closeStream,
  parkPaused and stop drop the off-screen timer.
- Never disable on a SUSPECT/STUCK decoder (disabling flushes then releases the codec — the step that hung).

### mv-layout (must) — layout agent  [plan item + amendments + user decisions]
- Geometry exactly as the plan's layout_spec with gap 6 dp and the user's choices above.
- Add button: half-size box from the spec, vertically centred on the bottom picture, corners 8 dp.
- D-pad per "Lead decisions" above. Update/extend MultiviewRulesTest with every box and move listed in the plan's
  layout_spec tests (two, three, four, large) plus the new D-pad rules.
- New pictures stay INVISIBLE until other pictures finish moving (3 -> 4 would otherwise overlap): track new pictures
  separately from movingNow, settle them in moveEnd, in finishMotion (which suspend() calls) and on the next place(),
  so a suspend mid-move never leaves one invisible after resume().
- KDoc updated; no change to "One large on top" beyond the gap.

### mv-gpu-memory (should) — layout agent, experiment
- Draw the picture corners with Canvas.drawDoubleRoundRect on API >= 29 (outer rect radius 0, inner rounded rect
  radius r; RectF/radii made in onSizeChanged), keep the even-odd Path for API < 29. Plus one delayed
  GraphicsTrim-style "stale only" trim after a move ends (one Runnable, cancelled by the next move).
- The lead will A/B it on the Max (10 layout toggles; dumpsys gfxinfo Total GPU memory / Scratch, meminfo GL mtrack).
  Keep it as a separate commit so it can be reverted if it doesn't help or the corners look different.

### ended-faster (should)
- ENDED_FIRST_MS 300 -> 150 ms.
- A separate DECODER_ERRORS set = { ERROR_CODE_DECODING_FAILED } gets one quick first retry (300 ms) per steady period.
  CANT_DECODE and its give-up rule stay EXACTLY as today (add a test that DECODING_FAILED keeps unlimited retries).

### not-a-stream (should)
- LiveRefusal gets an explicit notAStream state (UnrecognizedInputFormatException / "container unsupported"): it
  counts toward the streak, makes `shown` true without an HTTP code, message() returns plain text such as
  "This channel isn't sending a video stream right now", and the log says "not a video stream" instead of a code.
  Test: an HTTP 4xx after a not-a-stream run still shows its own code.

### publish-churn (should)
- PlayerHost and MultiviewPlayer onEvents: skip publishing for batches that only carry CUES and/or METADATA.
- Cache the derived track lists by Tracks identity; clear the cache on player swap (playerFor) and closeStream.

### log-privacy (should)
- Replace playlist names in logs with "playlist ${p.id}" at LiveSync.kt ~185, 223, 238, 265, 272, 314, 327 and any
  other Log call printing a playlist/channel name in live/ and playback/ (grep).

### mv-retry-stagger (could, from mv-polish part 2)
- Multiview pictures' reconnect retries staggered (at most 750 ms apart) with a pure tested rule. Drop part 3.

### guide-scale (could) — readiness for more playlists
- Build the "All playlists" union only when "All playlists" is actually opened; keep each playlist's start/end offset
  beside it in ScopeLists (app/src/main/java/app/fireplayer/live/ScopeLists.kt) so it is invalidated with it; serve a
  single playlist's All channels as a subList view only while the union is already held, otherwise read it alone as
  today. A fast row index (channel key -> row) built on the guide thread only for lists over 5000 channels, dropped
  with the list. Tests in ScopeListsTest.

### steady-multiview-redraws (should) — new, from the device measurement
- In steady 3-picture Multiview the UI drew ~6 frames/s with 51-67% janky (gfxinfo) although nothing on screen
  changes. Find what invalidates/redraws (status publish -> board/overlay views, ring, clock, labels, corner overlay)
  and stop needless redraws. Measure with a unit-level reasoning + the lead's gfxinfo check on the Max. Do not touch
  MultiviewBoard.kt geometry (layout agent owns it); if the cause is in MultiviewBoard, describe the exact change in the
  report instead of making it.

### small-cleanups (could)
- No glass copy without a frame (plan part 3). Delete dead code: live/SmartPlayOrchestrator.kt if truly unused
  (plan part 5) — confirm no references. Drop parts 1, 2 and 4.

### rebuffer-cap (could) — LOG ONLY
- On a long single-channel rebuffer, log one line "refill X s per s over 10 s" (no reconnect, no behaviour change).

### chain-on-close (should, experimental, LAST commit)
- Per plan + amendments: when the provider closes a plain (progressive TS) live stream, queue a second copy of the
  same channel so Media3 opens the next connection while the loaded tail plays. Mark the transition as a jump
  (startJump on onMediaItemTransition AUTO / once queued) so it isn't counted as a stall; onTracksChanged must keep the
  chosen sound and captions; check retry/seekToDefaultPosition, goLive, bufferChanged/pickAgain, parkPaused,
  streamDead, onTimelineChanged publishing and Multiview open() reuse with 2 items. A failed queued item must end in
  today's "stream ended; try 1" path. Single view only in this round. Own commit.

### device-checks / lean baseline (lead, on the Max, not code)
- Before (1.0.374, measured): cold start; single channel PSS 163 MB / graphics 17 MB; fresh 3-picture Multiview PSS
  253 MB / graphics 117 MB; graphics +14 MB per layout change; app CPU 28-30 % single, 65 % with 3 pictures; zap
  1.4-4.4 s (FirePlayer share ~100 ms); Multiview open 3.2-3.7 s; close 200-450 ms on the main thread.
- After: the same, plus no regression in cold start and Home.
