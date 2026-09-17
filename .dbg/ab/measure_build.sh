#!/usr/bin/env bash
# One build's worth of morph measurements: N cold first-visits and M warm
# second-visits.
#
# Why both: comparing two builds on *absolute* frame times does not work on this
# device. The first post-fix runs came back uniformly ~5.5ms worse than a
# baseline recorded hours earlier — and warm moved by +5.6ms while cold moved by
# +6.1ms, i.e. by the same amount. A cold-path code change cannot slow the warm
# path, so that shift was device state (thermal, scheduler, battery), not code.
#
# The cold/warm *ratio* is what survives that: a uniform slowdown multiplies
# both terms, so the ratio stays put. If a fix genuinely closes the cold-start
# gap, the ratio falls toward 1.0; if it does nothing, the ratio is unchanged.
#
# usage: measure_build.sh <label> [coldRepeats] [warmRepeats]
set -u
LABEL="${1:?label}"
COLD="${2:-3}"
WARM="${3:-2}"
HERE="$(cd "$(dirname "$0")" && pwd)"

# Discard the first visit after an install: a freshly installed APK pays
# verification/compilation on its first launch that later launches do not.
bash "$HERE/../cold_morph_probe.sh" "${LABEL}_warmup" 1.5 >/dev/null 2>&1

for i in $(seq 1 "$COLD"); do
  bash "$HERE/../cold_morph_probe.sh" "${LABEL}_cold$i" 1.5 2>&1 | tail -1
done
for i in $(seq 1 "$WARM"); do
  bash "$HERE/../probe_warm.sh" "${LABEL}_warm$i" 2>&1 | tail -1
done
