"""Why do our numbers differ from the phone's built-in screen-time numbers?

The built-in 「健康使用设备 → 使用统计」 reads Android's own aggregated usage
stats (`UsageStatsManager.queryUsageStats`), which count, per package:
  * totalTimeUsed    - the app's activities were in the foreground
  * totalTimeVisible - ...and visible (multi-window / on screen)
  * totalTimeFS      - a foreground *service* was running

This script reconstructs the same three quantities from our own raw event table
and puts them next to (a) what our record pipeline produced and (b) the numbers
read off the phone's screen, so the difference can be attributed instead of
guessed. It is a diagnostic; the phone screen is the reference.

Usage: python tools/vs_builtin.py <db> <YYYY-MM-DD> [untilHH:MM]
"""

import datetime
import sqlite3
import sys

TZ = datetime.timezone(datetime.timedelta(hours=8))

RESUMED = 1
PAUSED = 2
STOPPED = 23
SCREEN_OFF_TYPES = {16, 17, 26}
FS_START = 19
FS_STOP = 20

# numbers read off the phone's 「健康使用设备 → 使用统计」 screen (09-13, 16:11)
BUILTIN_TOTAL = "6h50m"
BUILTIN_APPS = {
    "com.ss.android.ugc.aweme.lite": "1h54m",
    "com.timeledger.app": "1h37m",
    "com.tencent.tmgp.sgame": "38m",
    "tv.danmaku.bili": "28m",
}


def hm(ms):
    h = int(ms // 3_600_000)
    m = int((ms % 3_600_000) // 60_000)
    return f"{h}h{m:02d}m" if h else f"{m}m"


def main() -> None:
    db = sys.argv[1]
    day = sys.argv[2] if len(sys.argv) > 2 else "2026-09-13"
    until = sys.argv[3] if len(sys.argv) > 3 else "16:11"

    con = sqlite3.connect(db)
    con.row_factory = sqlite3.Row

    start_dt = datetime.datetime.strptime(day, "%Y-%m-%d").replace(tzinfo=TZ)
    end_dt = start_dt.replace(
        hour=int(until.split(":")[0]), minute=int(until.split(":")[1])
    )
    start = int(start_dt.timestamp() * 1000)
    end = int(end_dt.timestamp() * 1000)

    events = con.execute(
        "SELECT * FROM usage_events WHERE timestamp_millis >= ? AND timestamp_millis < ? "
        "ORDER BY timestamp_millis",
        (start, end),
    ).fetchall()

    # ---- platform-style: resumed -> paused/stopped, per package ----
    open_since = {}
    used = {}
    visible = {}
    fs_open = {}
    fs_total = {}
    last_resume = {}

    for e in events:
        pkg, etype, ts = e["package_name"], e["event_type"], e["timestamp_millis"]
        if pkg is None:
            continue
        if etype == RESUMED:
            # a new activity of the same package: keep the session open but remember
            if pkg not in open_since:
                open_since[pkg] = ts
            last_resume[pkg] = ts
        elif etype in (PAUSED, STOPPED):
            since = open_since.get(pkg)
            if since is not None:
                used[pkg] = used.get(pkg, 0) + (ts - since)
                del open_since[pkg]
        elif etype == FS_START:
            fs_open[pkg] = ts
        elif etype == FS_STOP:
            since = fs_open.pop(pkg, None)
            if since is not None:
                fs_total[pkg] = fs_total.get(pkg, 0) + (ts - since)

    for pkg, since in open_since.items():
        used[pkg] = used.get(pkg, 0) + (end - since)
    for pkg, since in fs_open.items():
        fs_total[pkg] = fs_total.get(pkg, 0) + (end - since)

    # ---- our record pipeline, same window (records from both logical days) ----
    ours = {}
    for r in con.execute(
        "SELECT * FROM time_records WHERE is_deleted = 0 "
        "AND start_time_millis < ? AND end_time_millis > ?",
        (end, start),
    ):
        key = r["external_key"] or ""
        if not key.startswith("usage|"):
            continue
        a, b = max(r["start_time_millis"], start), min(r["end_time_millis"], end)
        if b > a:
            pkg = key.split("|")[-1]
            ours[pkg] = ours.get(pkg, 0) + (b - a)

    print(f"窗口 {day} 00:00 → {until}（手机内置那张屏幕的时间范围）")
    print(f"  手机内置的合计：{BUILTIN_TOTAL}")
    print()
    print(f"{'包名':<34}{'内置':>8}{'平台式重建':>12}{'我们的记录':>12}{'前台服务':>10}")
    for pkg, builtin in BUILTIN_APPS.items():
        print(
            f"{pkg:<34}{builtin:>8}{hm(used.get(pkg, 0)):>12}"
            f"{hm(ours.get(pkg, 0)):>12}{hm(fs_total.get(pkg, 0)):>10}"
        )
    print()
    total_used = sum(used.values())
    total_ours = sum(ours.values())
    print(f"所有应用（含系统）平台式重建合计：{hm(total_used)}")
    print(f"所有应用 我们的记录合计：        {hm(total_ours)}")
    print()
    print("前台服务时长（平台单独计，不计入使用时长）：")
    for pkg, ms in sorted(fs_total.items(), key=lambda kv: -kv[1])[:6]:
        print(f"   {pkg:<34}{hm(ms):>10}")


if __name__ == "__main__":
    main()
