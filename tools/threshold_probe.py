"""Which thresholds would actually make the day's account flat?

The user asks why the blank blocks have no category and suggests categorising
them as screen-off. Before touching any threshold, measure what each candidate
threshold would produce on the real database -- the 5-minute session-merge
experiment once inflated a day from 5h18m to 9h58m, and that is exactly the
mistake this script exists to prevent.

Reports, for one logged day:
  A. screen-off periods: how many, and how long in total, at floors 30s / 60s /
     2min / 5min (the current floor is 5 min).
  B. app sessions: how many, and how much foreground time, at minimum session
     lengths 10s / 20s / 30s / 60s (the current minimum is 60 s), including the
     30-second "same app came back" merge rule.
  C. the resulting day account: recorded vs unrecorded, under combinations.

Usage: python tools/threshold_probe.py <db> <YYYY-MM-DD> [...]
"""

import datetime
import sqlite3
import sys

TZ = datetime.timezone(datetime.timedelta(hours=8))
BOUNDARY_HOUR = 4

OFF_TYPES = {16, 17, 26}
ON_TYPES = {15, 18, 27}
FOREGROUND = 1
PAUSED = 2
STOPPED = 23
MERGE_GAP = 30_000  # ForegroundSessionBuilder's merge window
IGNORED = {"com.bbk.launcher2", "com.android.systemui", "android", None}


def hm(ms: int) -> str:
    h = int(ms // 3_600_000)
    m = int((ms % 3_600_000) // 60_000)
    return f"{h}h{m:02d}m" if h else f"{m}m"


def screen_off_periods(events, span_end, floor):
    periods = []
    opened = None
    for ts, etype in events:
        if etype in OFF_TYPES:
            if opened is None:
                opened = ts
        elif etype in ON_TYPES and opened is not None:
            if ts - opened >= floor:
                periods.append((opened, ts))
            opened = None
    if opened is not None and span_end - opened >= floor:
        periods.append((opened, span_end))
    return periods


def sessions(events, span_start, span_end, min_session):
    """Approximation of ForegroundSessionBuilder: ignore non-usage packages,
    close on screen-off/keyguard, merge a re-open of the same app within 30 s."""
    out = []
    current = None          # [package, start, end]
    last_closed = {}        # package -> end

    def close(end):
        nonlocal current
        if current is None:
            return
        pkg, start, _ = current
        if end - start >= min_session:
            out.append((start, end, pkg))
            last_closed[pkg] = end
        current = None

    for ts, etype, pkg in events:
        if etype in OFF_TYPES:
            close(ts)
        elif etype == FOREGROUND:
            if pkg in IGNORED:
                continue
            if current is not None and current[0] == pkg:
                current[2] = ts          # same app again: keep the session open
                continue
            if current is None:
                prev_end = last_closed.get(pkg)
                if prev_end is not None and ts - prev_end <= MERGE_GAP:
                    # re-open within the merge window -- but the builder refuses to
                    # chain over an intervening session that was long enough
                    current = [pkg, prev_end, ts]
                    continue
                current = [pkg, ts, ts]
            else:
                close(ts)
                current = [pkg, ts, ts]
        elif etype in (PAUSED, STOPPED):
            if current is not None and current[0] == pkg:
                close(ts)
    close(span_end)
    return [(max(a, span_start), min(b, span_end)) for a, b, _ in out if min(b, span_end) > max(a, span_start)]


def main() -> None:
    db = sys.argv[1]
    con = sqlite3.connect(db)
    con.row_factory = sqlite3.Row

    for day_str in (sys.argv[2:] or ["2026-09-12"]):
        day = datetime.datetime.strptime(day_str, "%Y-%m-%d").replace(tzinfo=TZ)
        start = int((day + datetime.timedelta(hours=BOUNDARY_HOUR)).timestamp() * 1000)
        end = start + 86_400_000
        now = int(datetime.datetime.now(TZ).timestamp() * 1000)
        observed_end = min(max(min(now, end), start), end)

        events = [
            (r["timestamp_millis"], r["event_type"], r["package_name"])
            for r in con.execute(
                "SELECT * FROM usage_events WHERE timestamp_millis >= ? AND timestamp_millis < ?",
                (start - 6 * 3_600_000, observed_end),
            )
        ]
        off_on = [(t, e) for t, e, _ in events]

        print("=" * 78)
        print(f"{day_str}   window {hm(observed_end - start)}")

        print("  A. screen-off records at candidate floors:")
        for floor, label in ((30_000, "30s"), (60_000, "1min"), (120_000, "2min"), (300_000, "5min (now)")):
            periods = [p for p in screen_off_periods(off_on, observed_end, floor)
                       if p[0] >= start and p[1] <= observed_end]
            total = sum(b - a for a, b in periods)
            print(f"     floor {label:<10} periods {len(periods):>4}   total {hm(total):>7}")

        print("  B. app sessions at candidate minimum lengths:")
        base = None
        for minimum, label in ((10_000, "10s"), (20_000, "20s"), (30_000, "30s"), (60_000, "60s (now)")):
            ss = sessions(events, start, observed_end, minimum)
            total = sum(b - a for a, b in ss)
            if base is None:
                base = total
            print(f"     min {label:<10} sessions {len(ss):>4}   total {hm(total):>7}"
                  f"   added vs 60s {hm(total - base):>6}")

        # C. day account with the app's current rules
        records = con.execute(
            "SELECT * FROM time_records WHERE is_deleted = 0 "
            "AND start_time_millis < ? AND end_time_millis > ?",
            (end, start),
        ).fetchall()
        covered = []
        for r in records:
            a, b = max(r["start_time_millis"], start), min(r["end_time_millis"], observed_end)
            if b > a:
                covered.append((a, b))
        covered.sort()
        merged = []
        for a, b in covered:
            if merged and a <= merged[-1][1]:
                merged[-1] = (merged[-1][0], max(merged[-1][1], b))
            else:
                merged.append((a, b))
        total_covered = sum(b - a for a, b in merged)
        print(f"  C. now: recorded {hm(total_covered)} / "
              f"unrecorded {hm(observed_end - start - total_covered)}")


if __name__ == "__main__":
    main()
