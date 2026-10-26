"""Explain what the blank (gap) blocks on the day timeline actually are.

The vertical timeline draws a blank block ("空 Xh") whenever two consecutive
records are >= 15 minutes apart. The user asks: why are these blanks still there
after adding the screen-off category? This script, for one logged day, lists every
gap between live records and classifies it from the raw usage events:

  * screen_off   -> SCREEN_OFF / KEYGUARD_SHOWN events present (phone was off),
                    so the gap is "screen off but too short / not recorded"
  * idle_on      -> screen-on / keyguard-hidden events + no app in foreground,
                    i.e. phone awake but no tracked app (home screen etc.)
  * app_activity -> some foreground app event present (shouldn't normally happen)
  * no_events    -> no usage events at all in that window

Usage: python tools/explain_blanks.py <db> <epochDay-or-YYYY-MM-DD>
"""

import sqlite3
import sys
from datetime import datetime, timedelta, timezone

TZ = timezone(timedelta(hours=8))
BOUNDARY = 4  # logged day starts at 04:00

OFF_TYPES = {16, 17, 26}       # SCREEN_OFF, KEYGUARD_SHOWN, SHUTDOWN
ON_TYPES = {15, 18, 27}        # SCREEN_ON, KEYGUARD_HIDDEN, STARTUP
APP_TYPES = {1, 2, 7, 23, 12}  # RESUMED/PAUSED/MOVE-*/ACTIVITY-ish foreground events


def l(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, TZ).strftime("%m-%d %H:%M:%S")


def dur(ms: int) -> str:
    h = ms // 3_600_000
    m = (ms % 3_600_000) // 60_000
    return f"{h}h{m:02d}m"


def main() -> None:
    db = sys.argv[1]
    day = sys.argv[2] if len(sys.argv) > 2 else "2026-09-12"
    con = sqlite3.connect(db)
    con.row_factory = sqlite3.Row

    day_dt = datetime.strptime(day, "%Y-%m-%d").replace(tzinfo=TZ)
    start = int((day_dt + timedelta(hours=BOUNDARY)).timestamp() * 1000)
    end = start + 86_400_000

    rows = con.execute(
        "SELECT * FROM time_records WHERE is_deleted = 0 "
        "AND start_time_millis < ? AND end_time_millis > ? ORDER BY start_time_millis",
        (end, start),
    ).fetchall()
    print(f"logged day {day}  window {l(start)} -> {l(end)}  live records: {len(rows)}")

    # clip records to the window and gather as intervals
    ivals = []
    for r in rows:
        a, b = max(r["start_time_millis"], start), min(r["end_time_millis"], end)
        if b > a:
            ivals.append((a, b, r["id"], r["source"], r["category_id"]))
    ivals.sort()

    # merge intervals (should be near-disjoint after resolution, but be safe)
    merged = []
    for a, b, rid, src, cat in ivals:
        if merged and a < merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], b), rid, src, cat)
        else:
            merged.append([a, b, rid, src, cat])

    covered = 0
    print()
    print("gaps between live records:")
    cursor = start
    for a, b, *_ in merged:
        if a > cursor:
            covered += (a - cursor)
            classify(con, cursor, a)
        cursor = max(cursor, b)
    if cursor < end:
        covered += (end - cursor)
        classify(con, cursor, end)

    total = end - start
    print()
    print(f"covered {dur(covered)} of {dur(total)}  "
          f"blanks {dur(total - covered)}  ({100.0 * (total - covered) / total:.1f}%)")


def classify(con, a, b):
    evs = con.execute(
        "SELECT * FROM usage_events WHERE timestamp_millis >= ? AND timestamp_millis < ?",
        (a, b),
    ).fetchall()
    types = {e["event_type"] for e in evs}
    apps = {e["package_name"] for e in evs
            if e["package_name"] and e["package_name"] != "android"}

    if not evs:
        label = "no_events (phone off / no data captured)"
    elif types & OFF_TYPES:
        # a screen-off happened here but the resulting record was < 5 min (absorbed),
        # or the off period is longer but split across the day boundary
        off_count = sum(1 for e in evs if e["event_type"] in OFF_TYPES)
        label = f"screen_off ({off_count} off-events, below threshold / absorbed)"
    elif apps:
        label = f"app_activity ({sorted(apps)[:4]})"
    elif types & ON_TYPES:
        label = "idle_on (phone awake, no tracked app in foreground)"
    else:
        label = f"misc types={sorted(types)}"

    print(f"  {l(a)} -> {l(b)}   {dur(b - a):>6}   {label}")


if __name__ == "__main__":
    main()
