# Home / open weekly summary

Release 1.2.111 (165), 2026-10-21. Follow-up to
[the weekly glass correction](home-week-glass.md).

The isolated panel was still a composition mismatch with the open Reading desk
Home. `ThisWeekWell` now draws directly on Home's field. It has no card fill,
blur, border or rounded enclosure. One 20dp gutter aligns the weekly summary with
the other sections, replacing the previous outer plus inner panel padding. The
plot spans that content width at 72dp high, with its last-day marker inset so its
edge stays visible. A quieter gradient under the line supplies local definition.

Weekly totals, forecast, started/finished counts, graph entry motion, haptics and
tap-to-Stats remain. The whole section has a button role and a named statistics
action for accessibility. Floating navigation clearance remains beneath the plot.
No glass workload is needed for this open section, and panel-opacity preferences
continue to govern actual panels elsewhere.

The former Home card opacity cases have been replaced with a regression check
that the real weekly summary preserves its room background at low/default/full
panel opacity in both Home layouts and light/dark themes. Shared panel opacity
checks remain. Native Home previews cover normal and narrow/large-text widths.

Evidence: `outputs/home-open-week-*`. Install-only device handling remains in
force; no app launch, navigation or phone performance measurements are claimed.
