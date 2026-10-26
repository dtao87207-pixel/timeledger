"""Reproduce Android's own attribution rule and check it against the phone screen.

Reading of AOSP `UsageStatsService`: it keeps the set of **currently resumed
activities** (`mActivities`, keyed by package+class+instance) and, on every
event, attributes the elapsed wall time since the previous event to *every*
package that currently has a resumed activity.

That is very different from the naive "RESUMED closes on the next PAUSED" rule:
a video app that swaps activities (feed -> profile -> feed, PAUSED/RESUMED pairs
microseconds apart) stays "in the foreground" the whole time, while the naive
rule chops it into fragments.

This script implements the AOSP-style rule from our stored events and compares
the result with the numbers read off the phone's 「健康使用设备 → 使用统计」.
"""

import datetime
import sqlite3
import sys

TZ = datetime.timezone(datetime.timedelta(hours=8))
RESUMED = 1
PAUSED = 2
STOPPED = 23

# 手机内置屏幕（09-13 16:11）——这是参照物
BUILTIN = {
    "com.ss.android.ugc.aweme.lite": ("抖音极速版", 1 * 3600 + 54 * 60),
    "com.timeledger.app": ("时间记账", 1 * 3600 + 37 * 60),
    "com.tencent.tmgp.sgame": ("王者荣耀", 38 * 60),
    "tv.danmaku.bili": ("哔哩哔哩", 28 * 60),
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
    from_hhmm = sys.argv[4] if len(sys.argv) > 4 else "00:00"
    start_dt = start_dt.replace(hour=int(from_hhmm[:2]), minute=int(from_hhmm[3:]))
    end_dt = start_dt.replace(hour=int(until[:2]), minute=int(until[3:]))
    if end_dt < start_dt:
        end_dt += datetime.timedelta(days=1)
    start, end = int(start_dt.timestamp() * 1000), int(end_dt.timestamp() * 1000)

    events = con.execute(
        "SELECT * FROM usage_events WHERE timestamp_millis >= ? AND timestamp_millis < ? "
        "ORDER BY timestamp_millis",
        (start, end),
    ).fetchall()

    # ---- AOSP-style: attribute wall time to every package with a resumed activity
    resumed = {}          # key -> package
    by_package = {}
    last_ts = None
    for e in events:
        ts = e["timestamp_millis"]
        if last_ts is not None and ts > last_ts and resumed:
            for pkg in set(resumed.values()):
                by_package[pkg] = by_package.get(pkg, 0) + (ts - last_ts)
        last_ts = ts
        etype, pkg, cls = e["event_type"], e["package_name"], e["class_name"]
        if pkg is None:
            continue
        key = (pkg, cls)
        if etype == RESUMED:
            resumed[key] = pkg
        elif etype in (PAUSED, STOPPED):
            # AOSP keys by activity instance; (package, class) is our best proxy
            resumed.pop(key, None)
    if last_ts is not None and resumed:
        for pkg in set(resumed.values()):
            by_package[pkg] = by_package.get(pkg, 0) + (end - last_ts)

    # ---- the naive rule, for contrast
    naive = {}
    open_pkg, open_since = None, None
    for e in events:
        ts, etype, pkg = e["timestamp_millis"], e["event_type"], e["package_name"]
        if pkg is None:
            continue
        if etype == RESUMED and open_pkg is None:
            open_pkg, open_since = pkg, ts
        elif etype in (PAUSED, STOPPED) and open_pkg == pkg:
            naive[pkg] = naive.get(pkg, 0) + (ts - open_since)
            open_pkg, open_since = None, None

    # ---- our records
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

    print(f"{day} 00:00 → {until}")
    print(f"{'应用':<12}{'手机内置':>10}{'AOSP 式重建':>14}{'朴素重建':>10}{'我们的记录':>12}")
    for pkg, (name, builtin_ms) in BUILTIN.items():
        print(
            f"{name:<12}{hm(builtin_ms):>10}{hm(by_package.get(pkg, 0)):>14}"
            f"{hm(naive.get(pkg, 0)):>10}{hm(ours.get(pkg, 0)):>12}"
        )

    total_aosp = sum(by_package.values())
    print()
    # 重叠有多少：把「所有 App 的段」并起来（墙上时间）与逐个相加比较
    spans = []
    open_keys = {}
    last = None
    for e in events:
        ts = e["timestamp_millis"]
        if last is not None and ts > last and open_keys:
            for pkg in set(open_keys.values()):
                spans.append((last, ts, pkg))
        last = ts
        pkg, cls, etype = e["package_name"], e["class_name"], e["event_type"]
        if pkg is None:
            continue
        key = (pkg, cls)
        if etype == RESUMED:
            open_keys[key] = pkg
        elif etype in (PAUSED, STOPPED):
            open_keys.pop(key, None)
    if last is not None and open_keys:
        for pkg in set(open_keys.values()):
            spans.append((last, end, pkg))

    # 每个瞬间有几个 App 同时算前台
    points = {}
    for a, b, _ in spans:
        points[a] = points.get(a, 0) + 1
        points[b] = points.get(b, 0) - 1
    depth, prev, multi = 0, None, 0
    for t in sorted(points):
        if prev is not None and depth > 1:
            multi += t - prev
        depth += points[t]
        prev = t
    wall = 0
    for a, b, _ in spans:
        pass
    union = 0
    depth, prev = 0, None
    for t in sorted(points):
        if prev is not None and depth > 0:
            union += t - prev
        depth += points[t]
        prev = t

    print(f"AOSP 式重建的所有应用合计：{hm(total_aosp)}")
    print(f"  其中「多个 App 同时算前台」的时长：{hm(multi)}")
    print(f"  并起来（真正的墙上时间）：{hm(union)}")
    print("按 AOSP 式重建排前 12：")
    for pkg, ms in sorted(by_package.items(), key=lambda kv: -kv[1])[:12]:
        label = next((n for p, (n, _) in BUILTIN.items() if p == pkg), pkg)
        print(f"   {label:<34}{hm(ms):>9}")


if __name__ == "__main__":
    main()
