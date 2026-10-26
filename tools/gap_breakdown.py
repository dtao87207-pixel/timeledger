"""Where does the "unrecorded" time actually come from?

The user asks: the time inside the blank blocks has no category either, it just
cuts off -- why not categorise it (as screen-off)?

This script answers with numbers. For one logged day it takes every stretch of
time NOT covered by a record and splits it into three kinds:

  screen_off  - the screen was off (even if every off period was shorter than the
                5-minute threshold, i.e. pocket/glance flicker)
  fragments   - apps DID come to the foreground, but every session was shorter
                than the 60-second threshold, so the session builder dropped them
  no_data     - the system reports no events at all (phone off, or no history)

It also answers the follow-up: if short screen-off flickers were merged into one
screen-off record (across stretches where no app was opened), how much of the
blank time would get a category?

Usage: python tools/gap_breakdown.py <db> <YYYY-MM-DD> [...]
"""

import datetime
import sqlite3
import sys

TZ = datetime.timezone(datetime.timedelta(hours=8))
BOUNDARY_HOUR = 4

OFF_TYPES = {16, 17, 26}
ON_TYPES = {15, 18, 27}
FOREGROUND = 1
IDLE_FLOOR = 30_000          # 30 s: below this it is button jitter / ROM duplicates
SESSION_MIN = 60_000         # the app's min session
CLUSTER_GAP = 5 * 60_000     # events closer than this count as one stretch
IGNORED = {"com.bbk.launcher2", "com.android.systemui", "android"}


def l(ms: int) -> str:
    return datetime.datetime.fromtimestamp(ms / 1000, TZ).strftime("%m-%d %H:%M")


def hm(ms: int) -> str:
    h = int(ms // 3_600_000)
    m = int((ms % 3_600_000) // 60_000)
    return f"{h}h{m:02d}m" if h else f"{m}m"


def screen_off_periods(events, span_start, span_end):
    """ScreenOffPeriods.extract: consecutive black screens, >= IDLE_FLOOR."""
    periods = []
    opened = None
    for ts, etype in events:
        if etype in OFF_TYPES:
            if opened is None:
                opened = ts
        elif etype in ON_TYPES and opened is not None:
            periods.append([opened, ts])
            opened = None
    if opened is not None:
        periods.append([opened, span_end])
    return [p for p in periods if p[1] - p[0] >= IDLE_FLOOR]


def coalesce(periods, app_foreground_times):
    """Merge screen-off periods whose separating screen-on stretch had no app open."""
    merged = []
    for start, end in periods:
        if merged:
            last = merged[-1]
            has_app = any(last[1] <= t < start for t in app_foreground_times)
            if not has_app:
                last[1] = end
                continue
        merged.append([start, end])
    return merged


def union(intervals):
    out = []
    for a, b in sorted(intervals):
        if out and a <= out[-1][1]:
            out[-1][1] = max(out[-1][1], b)
        else:
            out.append([a, b])
    return out


def overlap(a, b):
    return max(0, min(a[1], b[1]) - max(a[0], b[0]))


def main() -> None:
    db = sys.argv[1]
    con = sqlite3.connect(db)
    con.row_factory = sqlite3.Row

    for day_str in (sys.argv[2:] or ["2026-09-12"]):
        day = datetime.datetime.strptime(day_str, "%Y-%m-%d").replace(tzinfo=TZ)
        start = int((day + datetime.timedelta(hours=BOUNDARY_HOUR)).timestamp() * 1000)
        end = start + 86_400_000

        records = con.execute(
            "SELECT * FROM time_records WHERE is_deleted = 0 "
            "AND start_time_millis < ? AND end_time_millis > ?",
            (end, start),
        ).fetchall()

        # observed window: 04:00 -> max(now, last record end), like the app
        now = int(datetime.datetime.now(TZ).timestamp() * 1000)
        latest = max([min(r["end_time_millis"], end) for r in records] + [start])
        observed_end = min(max(min(now, end), latest, start), end)

        covered = union(
            (max(r["start_time_millis"], start), min(r["end_time_millis"], observed_end))
            for r in records
            if min(r["end_time_millis"], observed_end) > max(r["start_time_millis"], start)
        )
        gaps = []
        cursor = start
        for a, b in covered:
            if a > cursor:
                gaps.append((cursor, a))
            cursor = max(cursor, b)
        if cursor < observed_end:
            gaps.append((cursor, observed_end))

        events = [
            (r["timestamp_millis"], r["event_type"], r["package_name"])
            for r in con.execute(
                "SELECT * FROM usage_events WHERE timestamp_millis >= ? AND timestamp_millis < ?",
                (start - 6 * 3_600_000, observed_end),
            )
        ]
        off_on = [(t, e) for t, e, _ in events]
        app_fg_times = [t for t, e, p in events if e == FOREGROUND and p and p not in IGNORED]

        periods = screen_off_periods(off_on, start, observed_end)
        merged_periods = coalesce([list(p) for p in periods], app_fg_times)

        print("=" * 76)
        print(f"{day_str}  observed {l(start)} -> {l(observed_end)} ({hm(observed_end - start)})"
              f"  recorded {hm(sum(b - a for a, b in covered))}"
              f"  unrecorded {hm(sum(b - a for a, b in gaps))}")

        # split every gap into the three kinds
        kinds = {"screen_off": [], "fragments": [], "no_data": []}
        for a, b in gaps:
            off_part = union([[max(a, p[0]), min(b, p[1])] for p in periods if overlap((a, b), p) > 0])
            # "activity" = clusters of app foreground events
            times = sorted(t for t in app_fg_times if a <= t < b)
            spans = []
            for t in times:
                if spans and t - spans[-1][1] <= CLUSTER_GAP:
                    spans[-1][1] = t
                else:
                    spans.append([t, t])
            frag = union(spans)

            remaining = [a, b]
            for part in union(off_part + frag):
                pass
            kinds["screen_off"] += off_part
            kinds["fragments"] += frag
            # no data = the gap minus (off ∪ fragments)
            busy = union(off_part + frag)
            cur = a
            for x, y in busy:
                if x > cur:
                    kinds["no_data"].append([cur, x])
                cur = max(cur, y)
            if cur < b:
                kinds["no_data"].append([cur, b])

        # these three may overlap each other (a flicker inside an activity cluster);
        # report them on their own, plus the gap total for reference
        total_gap = sum(b - a for a, b in gaps)
        so = sum(b - a for a, b in union(kinds["screen_off"]))
        fr = sum(b - a for a, b in union(kinds["fragments"]))
        nd = sum(b - a for a, b in union(kinds["no_data"]))
        print(f"  gap total {hm(total_gap)}")
        print(f"    screen off inside gaps (any length >=30s): {hm(so)}")
        print(f"    app activity inside gaps (sub-60s sessions): {hm(fr)}")
        print(f"    no events at all inside gaps:                {hm(nd)}")

        # what would the screen-off total become if flickers were merged?
        plain = sum(p[1] - p[0] for p in periods)
        merged_total = sum(p[1] - p[0] for p in merged_periods)
        recorded_so = 0
        so_id = con.execute("SELECT id FROM categories WHERE name = '熄屏'").fetchone()
        if so_id:
            so_id = so_id["id"]
            recorded_so = sum(
                min(r["end_time_millis"], observed_end) - max(r["start_time_millis"], start)
                for r in records
                if r["category_id"] == so_id
                and min(r["end_time_millis"], observed_end) > max(r["start_time_millis"], start)
            )
        print(f"  screen off now recorded: {hm(recorded_so)}"
              f"   (all black screens >=30s: {hm(plain)};"
              f" after merging flickers: {hm(merged_total)})")


if __name__ == "__main__":
    main()
