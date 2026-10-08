# Glass performance — measuring it, and the order to attribute it in

The liquid-glass work is about **rate**, not appearance: the room re-draws, and every pane that
samples it re-blurs behind it. A screenshot cannot show that, and an argument from the source cannot
settle it. This is the recipe.

## 1. The one number that matters

`FolioTokens.fieldAnimationFrameMs` — **50 ms, 20 Hz** — is how often the ground plane re-samples its
drift. It is the number the whole budget turns on, because the room is what every Haze source
records:

> A room that changes 20 times a second rather than 120 divides **every** pane's per-second blur cost
> by three to six.

The phase is computed from elapsed time (`rememberSlowPhases`), so the drift is exactly as fast and
as smooth at 20 Hz — the tick decides how often the picture is redrawn, not how fast it moves. If a
change makes the field look *steppy* rather than cheaper, the tick is not the lever that was pulled;
look at the family's `speed` first.

## 2. Frame time, on the device

```bash
adb shell dumpsys gfxinfo com.folio.reader framestats > /tmp/framestats.txt
adb shell dumpsys gfxinfo com.folio.reader reset      # clear before the next run
```

Read the **p95**, not the mean. Then, in the app, `FolioGlassStats(enabled = true)` draws the same
two percentiles live, alongside the two facts that decide which material a pane is even allowed to
use:

- **`blur`** — can this device blur at all (API ≥ 32, not low-RAM, preference on)?
- **`backdrop`** — is a Haze registry live at this point in the tree?

A report of "the glass looks wrong" is unanswerable without those: on a device that cannot blur, or a
screen that provides no backdrop, the non-blur fallback *is* the design.

**Turn the overlay off before believing a field measurement.** Its `withFrameNanos` loop is itself an
infinite animation and keeps the frame clock awake.

## 3. The attribution order

Change **one** thing, then measure. In this order — each is strictly cheaper to fix than the next, so
stopping as soon as the numbers move saves redoing the rest:

1. **Lens** — the AGSL refraction pass. Two full-surface shader evaluations per blurred pane.
   Currently off app-wide: `LocalFolioGuillocheActive` is `false`, because the plate it refracts has
   no call site.
2. **Film** — the AGSL rim/glint pass. One more full-surface pass per blurred pane.
3. **Field motion** — `FolioTokens.fieldAnimationFrameMs`, and the family's `speed`.
4. **In-flow blur** — anything still asking for `FolioGlassTier.Overlay` that sits in the page's own
   flow. An in-flow surface has nothing passing behind it, so its blur samples a still room and buys
   nothing.

## 4. What is deliberately *not* a lever

- **`inputScale`.** Haze's `Auto` already downsamples the backdrop as the radius grows; the small,
  high-attention surfaces pin `None` on purpose (see `folioGlassEffect`'s notes on pixel blocks).
  It is a cost lever for large surfaces only, and only per call site.
- **The blur radius cap.** It came down to 22 dp in P7 so the cap actually binds; it is a look
  decision with a cost side effect, not a knob to trade.
- **Removing an animation.** The brief was explicit: fix the lag without removing motion. Freeze
  only where a surface cannot be seen (`forceStatic` on the base layer under an opaque field).

## 5. Off-device checks

The colour and smoothness work can be checked without a phone:

- `:shared:desktopTest` — the theme guards (`DesignSystemTest`, `ThemeSchemeTest`, `FolioFieldModelTest`).
  Note the suite has a **pre-existing red baseline**; diff the failure set rather than treating red as
  new.
- `outputs/curation/topology.py` is the canonical `T6`/`W6` — import it, never reimplement a metric.
- The `folio-agsl-render` skill renders the shipped shaders to PNG off-device.
