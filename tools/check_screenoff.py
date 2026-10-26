"""Focused check: does the overnight screen-off record survive a re-analysis?

Why this exists: analysing day D used to soft-delete every SYSTEM record whose
*time range overlapped* D's window. A record that ends exactly at D's window
start (e.g. screen-off [00:43, 04:00] vs next window starting 04:00) therefore
got deleted every time the next day was analysed. The DAO now matches on
`start_time_millis` instead. This script prints the records around that
boundary so the fix can be confirmed against the real database.

Usage: python tools/check_screenoff.py <path-to-time_ledger.db> [yesterday] [today]
       (dates are local YYYY-MM-DD, default 2026-09-12 / 2026-09-13)
"""

import sqlite3
import sys
from datetime import datetime, timedelta, timezone

TZ = timezone(timedelta(hours=8))  # Asia/Shanghai, same zone the app logs in
DAY_BOUNDARY_HOUR = 4  # logged day starts at 04:00 local


def local(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, TZ).strftime("%m-%d %H:%M:%S")


def logged_day(ms: int) -> str:
    dt = datetime.fromtimestamp(ms / 1000, TZ) - timedelta(hours=DAY_BOUNDARY_HOUR)
    return dt.strftime("%Y-%m-%d")


def main() -> None:
    db = sys.argv[1]
    labels = sys.argv[2:3]

    con = sqlite3.connect(db)
    con.row_factory = sqlite3.Row
    rows = con.execute(
        """
        SELECT r.id, r.start_time_millis AS s, r.end_time_millis AS e,
               r.source, r.is_deleted, r.external_key,
               c.name AS category, a.name AS activity, r.note
        FROM time_records r
        LEFT JOIN categories c ON c.id = r.category_id
        LEFT JOIN activities a ON a.id = r.activity_id
        ORDER BY r.start_time_millis
        """
    ).fetchall()

    print(f"total rows: {len(rows)}  (live: {sum(1 for r in rows if not r['is_deleted'])})")
    print()

    # Everything whose logged day is 09-12 or 09-13: that is the window where the
    # overnight screen-off was being destroyed.
    keys = ("screen-off",)
    interesting = []
    for r in rows:
        day = logged_day(r["s"])
        is_screen_off = (r["external_key"] or "").startswith(keys) or r["category"] == "熄屏"
        if day in ("2026-09-12", "2026-09-13") and is_screen_off:
            interesting.append(r)

    print("screen-off rows owned by logged day 09-12 / 09-13:")
    for r in interesting:
        flag = "DELETED" if r["is_deleted"] else "live   "
        dur = (r["e"] - r["s"]) / 60000
        print(
            f"  {flag} #{r['id']:<4} {logged_day(r['s'])}  "
            f"{local(r['s'])} -> {local(r['e'])}  {dur:6.1f}min  "
            f"src={r['source']:<9} cat={r['category']} key={r['external_key']}"
        )
    if not interesting:
        print("  (none)")

    print()
    print("all rows touching the 09-13 04:00 boundary (start or end within 10min of it):")
    boundary = datetime(2026, 9, 13, 4, 0, tzinfo=TZ).timestamp() * 1000
    for r in rows:
        if abs(r["s"] - boundary) <= 600_000 or abs(r["e"] - boundary) <= 600_000:
            flag = "DELETED" if r["is_deleted"] else "live   "
            print(
                f"  {flag} #{r['id']:<4} {local(r['s'])} -> {local(r['e'])}  "
                f"src={r['source']:<9} cat={r['category']} note={r['note']}"
            )
    print()
    print("screen-off totals by logged day:")
    totals: dict[str, float] = {}
    for r in rows:
        if r["is_deleted"]:
            continue
        if (r["external_key"] or "").startswith(keys) or r["category"] == "熄屏":
            totals[logged_day(r["s"])] = totals.get(logged_day(r["s"]), 0.0) + (
                r["e"] - r["s"]
            ) / 60000
    for day in sorted(totals):
        print(f"  {day}: {totals[day]:7.1f} min ({totals[day] / 60:.2f} h)")


if __name__ == "__main__":
    main()
