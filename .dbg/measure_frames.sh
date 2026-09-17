#!/usr/bin/env bash
# Frame-time measurement for the Folio release build.
#
# `dumpsys gfxinfo <pkg> framestats` emits comma-separated rows, so awk needs
# FS="," — with the default whitespace FS every row has NF==1 and $12/$18 are
# empty, which silently produces nonsense (an "interval" of 0.2ms). That is the
# bug this script exists to avoid.
#
# Columns: 3=IntendedVsync 12=FrameInterval 16=IssueDrawCommandsStart
#          17=SwapBuffers 18=FrameCompleted
# Jank is judged against each frame's OWN FrameInterval, not a fixed 16.7ms.
#
# usage: measure_frames.sh <label> <seconds>
set -u
ADB="C:\\Users\\kumar\\AppData\\Local/Android/Sdk/platform-tools/adb.exe"
PKG=com.folio.reader
LABEL="${1:-measurement}"
SECS="${2:-4}"
# Explicit path: $TMPDIR can be a Windows-style path under Git Bash, which makes
# the redirect produce an empty file and every measurement report n=0.
OUT="D:/projects/Folio/.dbg/framestats.txt"

"$ADB" shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1
sleep "$SECS"
"$ADB" shell dumpsys gfxinfo "$PKG" framestats >"$OUT" 2>/dev/null

awk -F, -v label="$LABEL" -v secs="$SECS" '
  /^---PROFILEDATA---/ { inside = !inside; next }
  inside && NF >= 24 && $3 ~ /^[0-9]+$/ {
    n++
    total    = ($18 - $3) / 1e6      # intended vsync -> frame completed
    interval = $12 / 1e6             # this frame own budget
    draw     = ($17 - $16) / 1e6     # issueDrawCommands -> swapBuffers (render)
    present  = ($18 - $17) / 1e6     # swapBuffers -> frameCompleted (queue)
    sumT += total; sumI += interval; sumD += draw; sumP += present
    if (total > interval)       missed++
    if (total > 1.5 * interval) over++
    if (total > worst)          worst = total
  }
  END {
    if (n == 0) {
      printf "%-26s n=0  (nothing rendered in %ss)\n", label, secs
      exit
    }
    printf "%-26s n=%-4d %5.1f fps | avg=%5.2fms interval=%5.2fms | missed=%d(%.1f%%) >1.5x=%d(%.1f%%) worst=%5.2fms | draw=%5.2f present=%5.2f\n",
      label, n, n / secs, sumT / n, sumI / n,
      missed, 100 * missed / n, over, 100 * over / n, worst, sumD / n, sumP / n
  }
' "$OUT"
