# User corrections to SPEC.md (2026-11-28) — apply BEFORE building; these WIN over SPEC.md

Resume the Live TV + Multiview update using docs/live-tv-upgrade/SPEC.md, RESUME.md, and plan-and-reviews.json in ataahmed989/firestick-player.

Check the current branch first. This review used 72a85fd on claude/blissful-mayer-2nf8z9. Reuse the existing architecture and implement the agreed layout. Apply the following corrections to the spec before building. Choose the smallest implementation that satisfies each outcome.

Prioritize safe cleanup, accurate picture monitoring, and reduced redraw/memory work. Keep the existing picture quality, instant Multiview sound-follow, captions, PiP, and buffer preference. Implement the higher-risk chain-on-close experiment last, as already planned.

## 1. Preserve ownership when spreading Multiview releases across messages.

Multiview.leaveTo() currently calls closeAll() and immediately calls callbacks.leave(ch, pl). closeAll() drops the tiles, clears their leases, and returns their views to the board. suspend()/resume() reuse existing tile/player wrappers.

Posting releases changes these assumptions. Capture each retiring ExoPlayer and its resources before posting cleanup. Release it exactly once. Hold its surface out of the reusable view pool until cleanup has completed safely. An old callback must never clear a new player, release its lease, or return its view to the pool.

Coordinate the transition back to full-screen and rapid resume with pending cleanup so new streams obey the connection allowance and available decoder resources. If the viewer changes their mind while cleanup is pending, discard the obsolete navigation/open action while still completing the old cleanup.

Use the smallest completion/generation mechanism needed. Verify close→immediate reopen, suspend→immediate resume, close→full-screen, and repeated close calls.

## 2. Remove stale retry and listener effects on player replacement.

PlayerHost.playerFor() replaces the player but currently only explicitly removes stallCheck in that replacement block. Both player wrappers have retry runnables that read the mutable current player.

Cancel pending retry/watch work before replacement and teardown. Ensure late events from an old player cannot change the new player's status or schedule its recovery. Cover analytics callbacks as well as Player.Listener callbacks where they affect state.

Test a queued retry followed by a channel/header/buffer-setting change. The old retry must never act on the replacement.

## 3. Tighten DecoderHealth evidence and recovery.

Correct the spec's approximately 450 ms release-timeout assumption. A slow release is a suspicion signal. Check actual timeout-operation evidence where available and the retiring playback thread's state. Preserve the configured Media3 timeout values.

Two attempts at the same damaged channel producing no picture do not, by themselves, establish a device-wide failure. Keep that failure local unless independent blocked-thread or decoder-lifecycle evidence corroborates STUCK.

Identify each failure episode and player. A delayed self-check for an older episode must not clear a newer failure. One exiting thread must not clear another relevant unresolved blocked player.

Enforce one explicit viewer recovery attempt per stuck episode. Passing 30 seconds must not repeatedly replenish that allowance. Verify that repeated OK/channel presses cannot accumulate blocked players.

Use existing Status.error and per-picture error handling. Keep the state in memory and gate every automatic player-creation path consistently.

## 4. Make the picture watchdog distinguish intentional waiting from failure.

Keep the four-second polling approach and the existing spec's timing windows. Use a consistent monotonic clock for watchdog comparisons. Existing LiveTiming calls use uptimeMillis while ThreadBeat uses elapsedRealtime, so avoid subtracting timestamps from different clocks.

READY + playWhenReady alone is insufficient. Account for playback suppression, especially transient audio-focus loss such as Alexa. Pause the freeze observation while playback is intentionally suppressed, then establish a fresh baseline when it resumes.

Call ensureUpdated() before reading decoder counters. For a visible picture, rendered-frame progress is the primary evidence of picture advancement. Dropped input and buffers skipped during flush must not count as successful pictures.

Keep decoder/counter identity and baselines local to each player. A reused decoder need not initialize again for every channel. A newly created player's initialization count cannot be compared numerically with the previous player's count.

Handle visible, hidden, and deliberately video-disabled states explicitly. Hidden output must not trigger a visible-picture false alarm.

Restore the no-data/no-tracks case from the reviews. Give a useful waiting message and cooperate with the existing load-retry path. Avoid simultaneous watchdog and Media3 reconnect attempts.

Keep radio exempt. Repeated failures alone must not permanently mark a channel "sparse" and silence its watchdog.

Test Alexa suppression, buffering, reused decoders, counter resets, seeks, timestamp discontinuities, radio, and a real frozen-output condition.

## 5. Prevent recovery loops and stale health resets.

Every reopen must pass through the same decoder-health gate. A failed quick reopen should fall through to the existing channel-local slow retry behavior.

Use meaningful frame progress to establish recovery where appropriate. READY or isPlaying alone was insufficient in the recorded frozen-picture incident.

Keep picture-watch retry state scoped to the channel/failure episode so rebuilding a player does not reset its quick-retry allowance indefinitely.

A still-healthy Multiview picture must not clear an unrelated newer stuck episode through an old decoder's first-frame callback.

## 6. Optimize only the work shown to be wasteful.

Implement the agreed status-event filtering and track-list caching, including cache invalidation when players change. Inspect the steady Multiview invalidations and update views only when their displayed state changes.

Keep the corner-rendering and graphics-trim change independently testable. Compare repeated identical layout cycles and settled memory. Report whether graphics memory stabilizes and whether corners, animations, and remote response remain correct.

Implement the 60-second hidden-video release with the spec's healthy-player gate. Cancel it on lifecycle changes and restore at the earliest return signal. Test audio continuity and the expected TS reconnect on a one-connection playlist.

Preserve the explicit decisions already made about layout, sound-follow, guide loading, retry codes, and log privacy.

## 7. Give chain-on-close its own acceptance decision.

Verify that only the intended progressive TS media load completion queues the next item, that the previous connection has ended, and that the queue stays bounded.

Test delayed/refused next connections, stream format changes, captions/audio selection, pause, Go live, buffer changes, and navigation into Multiview. Bound transition grace so it cannot conceal a failed next item.

Keep the experiment only when the Max shows lower total interrupted time with acceptable memory and connection behavior. Record actual flush/reinitialization behavior instead of assuming decoder reuse.

## 8. Verify observable outcomes.

Run focused tests for the cases above, compileDebugKotlin during implementation, and the full testDebugUnitTest suite plus APK build on the final combined code.

On the Max, compare the documented baseline under matching conditions. Include repeated layout changes, at least 30 minutes of three-picture playback, and a four-picture smoke test within the provider allowance.

Report settled PSS/graphics memory, remote responsiveness, startup and reconnect delays, total interrupted seconds, and any false watchdog actions. Use controlled test-only failure simulation for stale callbacks and blocked-thread behavior. Healthy playback alone cannot verify those paths.

Update RESUME.md with the final commit, completed stages, test results, device measurements, and outstanding checks. Keep unverified performance claims explicitly marked.

## Lead's note
"Analyze carefully and intricately when the time comes" (user). Future, separate update: Stalker portal playlists (portal + MAC
sign-in, per-channel play links); keep reconnects able to ask for a fresh link so the Live TV work carries over.
