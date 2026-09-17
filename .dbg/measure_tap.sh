#!/usr/bin/env bash
# Measure the frames a tab-switch transition actually costs.
#
# usage: measure_tap.sh <label> <x> <y> [seconds-after-tap]
#
# Resets the profile, taps, waits for the transition to play out, then reports
# the per-frame pipeline for exactly those frames. See measure_frames.sh for why
# awk needs FS="," here.
set -u
ADB="C:\\Users\\kumar\\AppData\\Local/Android/Sdk/platform-tools/adb.exe"
PKG=com.folio.reader
LABEL="${1:?label}"
X="${2:?x}"
Y="${3:?y}"
WAIT="${4:-1.6}"
OUT="D:/projects/Folio/.dbg/framestats_tap.txt"

"$ADB" shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1
"$ADB" shell input tap "$X" "$Y"
sleep "$WAIT"
"$ADB" shell dumpsys gfxinfo "$PKG" framestats >"$OUT" 2>/dev/null

awk -F, -v label="$LABEL" '
  /^---PROFILEDATA---/ { inside = !inside; next }
  inside && NF >= 24 && $3 ~ /^[0-9]+$/ && $17 > 0 && $18 > $17 {
    n++
    total    = ($18 - $3) / 1e6
    interval = $12 / 1e6
    ui       = ($9  - $6) / 1e6    # handleInputStart -> drawStart: all UI-thread work
    render   = ($17 - $16) / 1e6   # issueDrawCommands -> swapBuffers
    present  = ($18 - $17) / 1e6   # swapBuffers -> frameCompleted
    sumT += total; sumI += interval; sumU += ui; sumD += render; sumP += present
    if (total > interval)       missed++
    if (total > 1.5 * interval) over++
    if (total > worst)          worst = total
  }
  END {
    if (n == 0) { printf "%-24s n=0 (no frames)\n", label; exit }
    printf "%-24s n=%-4d avg=%5.2fms interval=%5.2fms | missed=%d(%.1f%%) >1.5x=%d(%.1f%%) worst=%5.2fms | UI=%4.2f render=%5.2f present=%5.2f\n",
      label, n, sumT / n, sumI / n,
      missed, 100 * missed / n, over, 100 * over / n, worst,
      sumU / n, sumD / n, sumP / n
  }
' "$OUT"
