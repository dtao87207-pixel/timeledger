#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
改造前后对比：只读 App **实际写进库里的记录**，不自己重算。

为什么要这样一个工具：`diagnose_usage.py` 里有一份「按 App 规则重建前台会话」的实现，
它跟着 App 的规则一起演进（模块 20 加了排除系统组件、合并快速切回两条规则之后，
那份实现就落后了，报出来的「被丢掉的碎片」还是旧口径）。
所以验收要看 App 真正写出来的东西 —— 那才是用户会看到的时间轴。

用法：
    python compare_records.py before.db after.db [--label-before 改造前] [--label-after 改造后]
"""

from __future__ import annotations

import argparse
import sqlite3
import sys
from collections import defaultdict
from datetime import datetime, timedelta, timezone

TZ = timezone(timedelta(hours=8))
BOUNDARY_HOUR = 4


def logical_day(millis: int) -> str:
    dt = datetime.fromtimestamp(millis / 1000, TZ) - timedelta(hours=BOUNDARY_HOUR)
    return dt.date().isoformat()


def fmt(ms: int) -> str:
    m = round(ms / 60000)
    if m < 60:
        return f"{m}m"
    return f"{m // 60}h{m % 60:02d}m"


def load(path: str):
    conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    conn.row_factory = sqlite3.Row
    records = [dict(r) for r in conn.execute(
        "SELECT start_time_millis AS s, end_time_millis AS e, category_id, activity_id,"
        " source, note FROM time_records WHERE is_deleted = 0 ORDER BY start_time_millis"
    )]
    cats = {r["id"]: r["name"] for r in conn.execute("SELECT id, name FROM categories")}
    labels = {}
    if conn.execute("SELECT 1 FROM sqlite_master WHERE name='app_mappings'").fetchone():
        for r in conn.execute("SELECT package_name, app_label FROM app_mappings"):
            labels[r["package_name"]] = r["app_label"]
    conn.close()
    return records, cats, labels


def package_of(note) -> str:
    """未归类的记录把 App 名写在备注里（见 UsageRecordInferrer）。"""
    return (note or "").strip()


def report(path: str, label: str, cats, labels) -> dict:
    records, _cats, _labels = load(path)
    cats = cats or _cats
    print("=" * 78)
    print(f"{label}    ({path})")
    print("=" * 78)

    by_source = defaultdict(lambda: [0, 0])
    for r in records:
        by_source[r["source"]][0] += 1
        by_source[r["source"]][1] += r["e"] - r["s"]

    print(f"{'来源':<12}{'条数':>6}{'总时长':>10}")
    for src, (n, d) in sorted(by_source.items()):
        print(f"{src:<12}{n:>6}{fmt(d):>10}")
    live = [r for r in records if r["source"] in ("SYSTEM", "SUGGESTED")]
    pending = [r for r in records if r["source"] == "SUGGESTED"]
    unclassified = [r for r in live if r["category_id"] is None and r["activity_id"] is None]
    print(f"\n系统记录 {len(live)} 条；待确认 {len(pending)} 条；"
          f"未归类 {len(unclassified)} 条 / {fmt(sum(r['e'] - r['s'] for r in unclassified))}")

    buckets = [(0, 60_000, "<1m"), (60_000, 300_000, "1–5m"),
               (300_000, 1_800_000, "5–30m"), (1_800_000, 10**15, ">30m")]
    print("\n记录时长分布（系统记录）:")
    for lo, hi, name in buckets:
        rows = [r for r in live if lo <= r["e"] - r["s"] < hi]
        bar = "█" * min(len(rows), 40)
        print(f"  {name:<7}{len(rows):>4} 条  {fmt(sum(r['e'] - r['s'] for r in rows)):>8}  {bar}")

    print("\n按逻辑日:")
    days = defaultdict(lambda: [0, 0, 0])
    for r in live:
        d = days[logical_day(r["s"])]
        d[0] += 1
        d[1] += r["e"] - r["s"]
        if r["source"] == "SUGGESTED":
            d[2] += 1
    for day in sorted(days, reverse=True):
        n, dur, pend = days[day]
        print(f"  {day}: {n:>4} 条 / {fmt(dur):>8}   待确认 {pend}")

    print("\n按 App（未归类的看备注，已归类的看分类）:")
    per = defaultdict(lambda: [0, 0])
    for r in live:
        key = cats.get(r["category_id"]) or package_of(r["note"]) or "未归类"
        per[key][0] += 1
        per[key][1] += r["e"] - r["s"]
    for name, (n, d) in sorted(per.items(), key=lambda kv: -kv[1][1])[:12]:
        print(f"  {name[:30]:<32}{n:>4} 条  {fmt(d):>8}")
    print()
    return {
        "records": len(live),
        "pending": len(pending),
        "unclassified": len(unclassified),
        "unclassified_ms": sum(r["e"] - r["s"] for r in unclassified),
        "total_ms": sum(r["e"] - r["s"] for r in live),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("before")
    ap.add_argument("after")
    ap.add_argument("--label-before", default="改造前")
    ap.add_argument("--label-after", default="改造后")
    args = ap.parse_args()

    # 每个库**各自**加载自己的分类表。
    # 第一版图省事，把 before 的分类表同时用于两边 —— 结果新增的分类（比如「熄屏」）
    # 在报告里全被显示成「未归类」，看起来像功能没生效。工具的口径错了，
    # 比没有工具更危险：它会让人得出完全相反的结论。
    _, before_cats, labels = load(args.before)
    _, after_cats, _ = load(args.after)

    b = report(args.before, args.label_before, before_cats, labels)
    a = report(args.after, args.label_after, after_cats, labels)

    print("=" * 78)
    print("对比")
    print("=" * 78)
    rows = [
        ("系统记录条数", b["records"], a["records"]),
        ("待确认条数", b["pending"], a["pending"]),
        ("未归类条数", b["unclassified"], a["unclassified"]),
        ("未归类时长", fmt(b["unclassified_ms"]), fmt(a["unclassified_ms"])),
        ("系统记录总时长", fmt(b["total_ms"]), fmt(a["total_ms"])),
    ]
    print(f"{'指标':<18}{args.label_before:>12}{args.label_after:>12}")
    for name, x, y in rows:
        print(f"{name:<18}{str(x):>12}{str(y):>12}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
