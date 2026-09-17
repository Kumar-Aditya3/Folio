#!/usr/bin/env bash
# Does the cold-start morph lag come from *startup contention* (Home still
# settling, JIT still running) or from the *destination's first composition*
# (Library never composed before)?
#
# The discriminator is how long we wait between launch and the tap:
#
#   wait=1.5  Home is still settling -> if the cost is startup contention, slow.
#   wait=8.0  Home fully idle       -> if the cost is Library's first
#                                       composition, STILL slow; if it was
#                                       contention, now fast.
#
# usage: cold_morph_probe.sh <label> <waitBeforeTapSecs> [afterTapSecs]
set -u
ADB="C:\\Users\\kumar\\AppData\\Local/Android/Sdk/platform-tools/adb.exe"
PKG=com.folio.reader
ACT="$PKG/.MainActivity"
LABEL="${1:?label}"
WAIT_BEFORE="${2:?wait before tap}"
WAIT_AFTER="${3:-2.0}"
# Library tab in the bottom capsule. Taken from `uiautomator dump`, not from
# eyeballing a screenshot: the capsule's touch targets sit at y≈2196..2259, and
# a y of 2016 (which *looks* right on a scaled screenshot) lands in the shelf
# above and opens a book instead of switching tabs.
TAB_X=513
TAB_Y=2227
OUT="D:/projects/Folio/.dbg/cold_probe_${LABEL}.txt"

"$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
sleep 1.0
"$ADB" shell am start -n "$ACT" >/dev/null 2>&1
sleep "$WAIT_BEFORE"

"$ADB" shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1
"$ADB" shell input tap "$TAB_X" "$TAB_Y"
sleep "$WAIT_AFTER"
"$ADB" shell dumpsys gfxinfo "$PKG" framestats >"$OUT" 2>/dev/null

awk -F, -v label="$LABEL" -v wait="$WAIT_BEFORE" '
  /^---PROFILEDATA---/ { inside = !inside; next }
  inside && NF >= 24 && $3 ~ /^[0-9]+$/ && $17 > 0 && $18 > $17 {
    n++
    total    = ($18 - $3) / 1e6
    interval = $12 / 1e6
    ui       = ($9  - $6) / 1e6
    render   = ($17 - $16) / 1e6
    present  = ($18 - $17) / 1e6
    sumT += total; sumI += interval; sumU += ui; sumD += render; sumP += present
    if (total > interval)   missed++
    if (total > 1.5 * interval) over++
    if (total > worst) { worst = total; worstUI = ui }
  }
  END {
    if (n == 0) { printf "%-16s wait=%-4s n=0 (no frames)\n", label, wait; exit }
    printf "%-16s wait=%-4ss n=%-4d avg=%5.2fms | missed=%d(%.0f%%) >1.5x=%d(%.0f%%) worst=%6.2fms(UI %5.2f) | UI=%4.2f render=%5.2f present=%5.2f\n",
      label, wait, n, sumT / n,
      missed, 100 * missed / n, over, 100 * over / n, worst, worstUI,
      sumU / n, sumD / n, sumP / n
  }
' "$OUT"
