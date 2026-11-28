# Live TV + Multiview upgrade: where it stands (paused 2026-11-28)

Paused for the user's weekly usage limit. Resume on **Tuesday 2026-12-01, 5:00 AM** (user's time).

## Done
- Investigation and design are finished. Six investigations covered the live engine, Multiview, the Live TV page
  lifecycle, the 27 Nov log, on-device measurements on the Max, and Media3/Fire TV research. One plan was written and
  two adversarial reviews checked it.
- `SPEC.md` is the final, decided spec. It folds in the reviews' amendments and the user's answers, and it wins over
  everything else.
- `plan-and-reviews.json` holds the full plan, the reviews and the findings, with file:line anchors. Links, addresses
  and provider names are scrubbed.
- No code has changed yet. The build stage was stopped before its first commit, so the branch code equals release
  1.0.374 (5e152ca).

## User decisions (28 Nov)
- **3 pictures in the grid:** two on top, the third centred underneath, and a **half-size** "Add a channel" button to
  its right.
- **4 pictures:** the plain 2x2 grid.
- **Spacing:** gap 8 -> 6 dp, corner radius 12 -> 8 dp (slightly less cropped).
- **"One large on top":** keep as it is (only the smaller gap).
- **Two agreed changes:**
  1. Let go of the hidden live picture's video decoder after 60 s out of sight; the sound plays on.
  2. A channel with no picture within ~15 s shows a plain message instead of spinning forever. This covers Multiview
     pictures too.

## What the 27 Nov incident really was
- A channel kept sending damaged H.264 and dropped its stream every ~20-27 s.
- At 20:51:50 that damaged video crashed the Fire TV's MediaTek video service (vpud).
- FirePlayer saw a "playing" player with a frozen picture for 12 minutes and did nothing.
- At 21:04 releasing that player wedged the decoder service. Every new player then blocked, and FirePlayer kept
  building players. Each close froze the screen 2.5 s, and an app freeze (ANR) followed.
- A reboot cleared it.
- The spec's must-haves follow from this:
  - release-order
  - decoder-health
  - picture-watch (frozen and no-picture detection)
  - offscreen-decoder
  - mv-layout

## Baseline on the Max (FirePlayer 1.0.374), for the before/after check
- **Cold start:** 1193 / 767 / 772 ms. Home: PSS 132 MB, graphics 40 MB, 0% CPU idle.
- **One channel full screen:** PSS 163 MB, graphics 17 MB, app CPU 28-30%.
- **Fresh 3-picture Multiview:** PSS 253 MB, graphics 117 MB, app CPU 65%.
  - Graphics grows about 14 MB per layout change; it reached 364 MB after an hour.
  - The UI drew about 6 frames/s with 51-67% janky while nothing on screen changed.
- **Channel change:** 1.4-4.4 s to first frame. About 100 ms of that is FirePlayer; the rest is the provider.
- **Multiview:** opening takes 3.2-3.7 s, adding a picture 4.2 s, and closing 200-450 ms on the main thread.

## To resume
0. FIRST read `CORRECTIONS.md` (the user's corrections, 28 Nov). It wins over `SPEC.md`; amend the spec
   accordingly before building, and analyse carefully and intricately.
1. Re-read `SPEC.md`, then `plan-and-reviews.json` for detail.
2. Build in this order. Engine stages run one after another in the main repo, and the layout runs in parallel in its
   own worktree:
   - **Layout** (worktree, own branch): mv-layout, then mv-gpu-memory as a separate commit. Touch only
     MultiviewRules.kt, MultiviewBoard.kt, LiveWidths.kt (MV_GAP, MV_RADIUS) and MultiviewRulesTest.
   - **Engine e1:** release-order, decoder-health, picture-watch (single view and Multiview).
   - **Engine e2:** offscreen-decoder, ended-faster (150 ms; DECODING_FAILED quick retry without widening
     CANT_DECODE), not-a-stream, publish-churn, log-privacy, mv-retry-stagger, rebuffer log only.
   - **Engine e3:** steady-multiview-redraws, guide-scale, small-cleanups, then the full unit test suite.
   - **Engine e4:** chain-on-close, experimental, in its own commit. Keep it only if the Max test passes.
3. Merge the layout branch and run the full `./gradlew :app:testDebugUnitTest`. Then an adversarial review (lifecycle,
   lean, spec conformance, privacy/text) and fixes.
4. Push the branch and open a PR to main. CI builds a release-signed APK as an artifact. Install it on the **Max**
   only; the user uses the Plus.
5. Check on the Max:
   - the new layouts and D-pad;
   - the 15 s message;
   - the off-screen release after 60 s;
   - faster reconnects;
   - lean numbers against the baseline above;
   - the graphics memory A/B for mv-gpu-memory;
   - chain-on-close on a channel that keeps dropping.
6. Then the usual release flow: squash-merge with the full head SHA, CI publishes v1.0.N, merge main back, verify the
   signer, install on the Max.
