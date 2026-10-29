"""Repeatable, non-destructive ADB sampling. No app-data clearing or battery-history reset.

Open the desired book/library first. For narration, start listening in Folio first.
python scripts/measure_android_performance.py --serial SERIAL --scenario narration
Results are local raw evidence. Compare identical release/device/content/settings runs.
"""
import argparse
import csv
import datetime as dt
import json
from pathlib import Path
import re
import shutil
import subprocess
import time

PACKAGE = "com.folio.reader"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--scenario", choices=("idle", "scroll", "narration"), required=True)
    parser.add_argument("--seconds", type=int)
    parser.add_argument("--interval", type=int, default=30)
    parser.add_argument("--adb", default=shutil.which("adb") or str(Path.home()/"AppData/Local/Android/Sdk/platform-tools/adb.exe"))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    seconds = args.seconds if args.seconds is not None else (1800 if args.scenario == "narration" else 120)
    if not 5 <= seconds <= 7200 or not 5 <= args.interval <= 300:
        parser.error("seconds must be 5..7200 and interval 5..300")
    out = args.output or Path("outputs") / ("performance-"+dt.datetime.now().strftime("%Y%m%d-%H%M%S"))
    out.mkdir(parents=True, exist_ok=False)

    def adb(*command, timeout=30):
        result = subprocess.run([args.adb,"-s",args.serial,*command],capture_output=True,text=True,encoding="utf-8",errors="replace",timeout=timeout)
        return result.stdout + ("\nSTDERR: "+result.stderr if result.stderr else "")

    if adb("get-state").strip() != "device":
        raise SystemExit("The specified device is not connected/authorized")
    if not adb("shell","pidof",PACKAGE).strip():
        raise SystemExit("Open Folio in the intended scenario first")
    if args.scenario == "narration":
        media = adb("shell","dumpsys","media_session")
        # Require Folio's own session to report playback; another app doesn't qualify.
        sessions = re.split(r"(?m)^\s*package=",media)[1:]
        if not any(s.splitlines()[0].strip() == PACKAGE and
                   re.search(r"state=(?:PLAYING\()?3(?:\)|,|\s)",s) for s in sessions):
            raise SystemExit("Start narration in Folio before measuring screen-off listening")
    metadata = {"scenario":args.scenario,"seconds":seconds,"interval":args.interval,
                "serial":args.serial,"started_utc":dt.datetime.now(dt.timezone.utc).isoformat(),
                "build":adb("shell","dumpsys","package",PACKAGE),
                "device":adb("shell","getprop","ro.product.model"),
                "notes":"PSS is the main process only. Renderer attribution requires inspecting activity-process associations; do not sum unrelated WebViews. USB charging affects battery/thermal comparisons. Raw counters are not proof of savings."}
    (out/"metadata.json").write_text(json.dumps(metadata,indent=2),encoding="utf-8")
    adb("shell","dumpsys","gfxinfo",PACKAGE,"reset")
    if args.scenario == "narration": adb("shell","input","keyevent","KEYCODE_SLEEP")
    dimensions = re.search(r"(\d+)x(\d+)",adb("shell","wm","size"))
    width,height = map(int,dimensions.groups()) if dimensions else (1080,2400)
    start = time.monotonic()
    next_sample = start
    try:
        with (out/"samples.csv").open("w",newline="",encoding="utf-8") as f:
            writer = csv.DictWriter(f,fieldnames=("elapsed_s","main_pss_kb","battery_percent","battery_temp_tenths_c","charge_counter_uah"))
            writer.writeheader()
            while True:
                now = time.monotonic()
                if now >= next_sample:
                    elapsed = round(now-start,2)
                    sample = out/f"sample-{int(elapsed):04}"
                    sample.mkdir()
                    commands = {
                        "memory":("dumpsys","meminfo",PACKAGE),
                        "cpu":("dumpsys","cpuinfo"),
                        "battery":("dumpsys","battery"),
                        "thermal":("dumpsys","thermalservice"),
                        "frames":("dumpsys","gfxinfo",PACKAGE,"framestats"),
                        "audio":("dumpsys","media.audio_flinger"),
                        "processes":("dumpsys","activity","processes"),
                        "sessions":("dumpsys","media_session"),
                    }
                    raw = {name:adb("shell",*command) for name,command in commands.items()}
                    for name,text in raw.items(): (sample/(name+".txt")).write_text(text,encoding="utf-8")
                    def number(pattern,text):
                        match = re.search(pattern,text,re.M)
                        return int(match.group(1)) if match else ""
                    writer.writerow({"elapsed_s":elapsed,"main_pss_kb":number(r"TOTAL PSS:\s*(\d+)",raw["memory"]),
                                     "battery_percent":number(r"^\s*level:\s*(\d+)",raw["battery"]),
                                     "battery_temp_tenths_c":number(r"^\s*temperature:\s*(\d+)",raw["battery"]),
                                     "charge_counter_uah":number(r"^\s*Charge counter:\s*(\d+)",raw["battery"])})
                    f.flush()
                    print(f"{elapsed:.0f}/{seconds}s sampled",flush=True)
                    next_sample += args.interval
                if time.monotonic()-start >= seconds: break
                if args.scenario == "scroll":
                    down = int((time.monotonic()-start)//8)%2 == 0
                    adb("shell","input","swipe",str(width//2),str(height*(3 if down else 1)//4),str(width//2),str(height*(1 if down else 3)//4),"450")
                time.sleep(min(1,max(.1,next_sample-time.monotonic())))
    finally:
        if args.scenario == "narration": adb("shell","input","keyevent","KEYCODE_WAKEUP")
    print(f"Evidence saved to {out}")


if __name__ == "__main__": main()
