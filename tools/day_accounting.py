"""Full accounting of one logged day: what is recorded, and why the rest is blank.

Outputs, for the given day:
  1. recorded time split by category (screen-off vs app vs manual)
  2. every blank >= 15 min (these are the blocks the timeline draws as "空 Xh"),
     each classified by what the raw events show inside it
  3. totals per blank reason

Blank reasons:
  no_events   - not a single usage event: the system has nothing (phone off, or
                the ROM kept no history that far back). Nothing the app can infer.
  screen_off  - screen off events exist but every off period is < 5 min, i.e. the
                screen flickered on/off (pocket, glances) so no screen-off record
                was written. The phone was NOT being used.
  fragments   - app foreground events exist, but every session was < 60 s, so the
                session builder dropped all of them. The phone WAS being used.
  mixed       - both of the above.

Usage: python tools/day_accounting.py <db> <YYYY-MM-DD> [...]
"""

import sqlite3
import sys
from datetime import datetime, timedelta, timezone

TZ = timezone(timedelta(hours=8))
BOUNDARY_HOUR = 4
OFF_TYPES = {16, 17, 26}
ON_TYPES = {15, 18, 27}
APP_TYPES = {1, 2, 7, 23}  # RESUMED / PAUSED / MOVE_TO_*


def l(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, TZ).strftime("%m-%d %H:%M:%S")


def hm(ms: int) -> str:
    h = int(ms // 3_600_000)
    m = int((ms % 3_600_000) // 60_000)
    return f"{h}h{m:02d}m"


def day_window(day: str):
    dt = datetime.strptime(day, "%Y-%m-%d").replace(tzinfo=TZ)
    start = int((dt + timedelta(hours=BOUNDARY_HOUR)).timestamp() * 1000)
    return start, start + 86_400_000


def main() -> None:
    db = sys.argv[1]
    days = sys.argv[2:] or ["2026-09-12"]
    con = sqlite3.connect(db)
    con.row_factory = sqlite3.Row

    # screen-off category id, for splitting recorded time
    so_cat = con.execute("SELECT id FROM categories WHERE name = '熄屏'").fetchone()
    so_id = so_cat["id"] if so_cat else -1

    for day in days:
        start, end = day_window(day)
        rows = con.execute(
            "SELECT * FROM time_records WHERE is_deleted = 0 "
            "AND start_time_millis < ? AND end_time_millis > ?",
            (end, start),
        ).fetchall()

        ivals = sorted(
            (max(r["start_time_millis"], start), min(r["end_time_millis"], end), r)
            for r in rows
            if min(r["end_time_millis"], end) > max(r["start_time_millis"], start)
        )

        print("=" * 78)
        print(f"logged day {day}   window {l(start)} -> {l(end)}   "
              f"live records touching it: {len(rows)}")
        print()

        # 1. recorded time by kind
        by_kind = {"熄屏": 0, "app/system": 0, "manual/merged": 0}
        for a, b, r in ivals:
            if r["category_id"] == so_id:
                by_kind["熄屏"] += b - a
            elif r["source"] in ("MANUAL", "MERGED"):
                by_kind["manual/merged"] += b - a
            else:
                by_kind["app/system"] += b - a
        for k, v in by_kind.items():
            print(f"  recorded {k:<14} {hm(v):>7}")
        print(f"  (records with no category: "
              f"{sum(1 for r in rows if r['category_id'] is None)})")

        # 2. blanks
        gaps = []
        cursor = start
        for a, b, _ in ivals:
            if a > cursor:
                gaps.append((cursor, a))
            cursor = max(cursor, b)
        if cursor < end:
            gaps.append((cursor, end))

        shown = [g for g in gaps if g[1] - g[0] >= 15 * 60 * 1000]
        hidden = [g for g in gaps if 0 < g[1] - g[0] < 15 * 60 * 1000]
        print()
        print(f"  blanks total {hm(sum(b - a for a, b in gaps))}"
              f"   >=15min (drawn as 空) {hm(sum(b - a for a, b in shown))}"
              f" in {len(shown)} blocks"
              f"   <15min (not drawn) {hm(sum(b - a for a, b in hidden))}")
        print()

        totals = {}
        for a, b in shown:
            evs = con.execute(
                "SELECT timestamp_millis AS t, event_type AS e, package_name AS p "
                "FROM usage_events WHERE timestamp_millis >= ? AND timestamp_millis < ? "
                "ORDER BY timestamp_millis",
                (a, b),
            ).fetchall()
            off = [e for e in evs if e["e"] in OFF_TYPES]
            app = [e for e in evs if e["e"] in APP_TYPES and e["p"] not in (None, "android")]
            if not evs:
                reason = "no_events"
            elif off and app:
                reason = "mixed (off+app fragments)"
            elif off:
                reason = "screen_off (flicker <5min)"
            elif app:
                reason = "fragments (sessions <60s)"
            else:
                reason = "other"
            totals[reason] = totals.get(reason, 0) + (b - a)
            print(f"  {l(a)} -> {l(b)}  {hm(b - a):>6}  {reason:<26} "
                  f"events={len(evs):<5} off={len(off):<4} app={len(app)}")

        print()
        print("  blank totals by reason:")
        for k, v in sorted(totals.items(), key=lambda kv: -kv[1]):
            print(f"    {k:<26} {hm(v):>7}")


if __name__ == "__main__":
    main()
