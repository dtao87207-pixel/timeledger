"""One-off repair: drop the stale user edits (MERGED) so re-analysis can rewrite them.

Why: the user edited several records back when the session boundaries were wrong
(fragmented). Those edits occupy the idempotency keys, so `saveSystemRecord`
keeps skipping them and the short, wrong durations stay forever
(09-13 抖音: 12m stored vs 40m computed, 8m vs 20m, ...).

The user's decision (verbatim): 「直接删除我的编辑」.

Soft delete, not hard delete: `saveSystemRecord` revives a soft-deleted row that
has the same external key, which is exactly what we want -- and if the new
session starts at a different millisecond (different key), a fresh row is
inserted and the dead one stays dead.

Usage: python tools/drop_edits.py <db> [--apply]
       (without --apply it only reports what it would do)
"""

import sqlite3
import sys

TZ_OFFSET_MS = 8 * 3600 * 1000


def main() -> None:
    db = sys.argv[1]
    apply = "--apply" in sys.argv

    con = sqlite3.connect(db)
    con.row_factory = sqlite3.Row

    rows = con.execute(
        "SELECT id, start_time_millis, end_time_millis, source, external_key, note "
        "FROM time_records WHERE is_deleted = 0 AND source = 'MERGED' "
        "ORDER BY start_time_millis"
    ).fetchall()

    print(f"{len(rows)} 条被用户编辑过的记录（MERGED）：")
    total = 0
    for r in rows:
        duration = r["end_time_millis"] - r["start_time_millis"]
        total += duration
        print(
            f"   #{r['id']:<4} {r['start_time_millis']} → {r['end_time_millis']} "
            f"({duration // 60000}m)  {r['note']}  key={r['external_key']}"
        )
    print(f"合计 {total // 60000} 分钟")

    if not apply:
        print("（预演，没有改动。加 --apply 才会写库）")
        return

    con.execute(
        "UPDATE time_records SET is_deleted = 1 WHERE source = 'MERGED' AND is_deleted = 0"
    )
    con.commit()
    left = con.execute(
        "SELECT COUNT(*) FROM time_records WHERE source = 'MERGED' AND is_deleted = 0"
    ).fetchone()[0]
    print(f"已软删除，剩余 live 的 MERGED 记录：{left}")
    print("下一步：删掉 -wal / -shm，重启 App，它会重新分析并写回正确的记录。")


if __name__ == "__main__":
    main()
