#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
系统感知的定量诊断：把「感觉抓取不准」变成具体数字。

用法：
    python diagnose_usage.py <time_ledger.db> [--min-session-seconds 60]

它做四件事：

1. 用**与 App 完全相同的规则**（见 domain/usage/ForegroundSessionBuilder.kt）
   从原始事件重建前台会话，算出这一天「实际上有多少可用的系统事实」；
2. 统计这些事实里有多少被**短会话阈值丢掉**（这是「偏少」的一大来源）；
3. 把它和数据库里真正生成的记录逐段对比，指出差额去哪了
   （丢弃 / 被手动记录挡下 / 从未采集 / 归属为空）；
4. 量化「碎」：记录条数、短记录占比、待确认条数、未归类时长。

只读，不改任何数据。
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from collections import defaultdict
from datetime import datetime, timedelta, timezone

# ---- 与 UsageEventKind.kt 一一对应的常量（值取自 android.jar，见 README 决策 71）----
FOREGROUND = 1  # ACTIVITY_RESUMED = MOVE_TO_FOREGROUND
BACKGROUND = 2  # ACTIVITY_PAUSED = MOVE_TO_BACKGROUND
SCREEN_OFF = 16
KEYGUARD_SHOWN = 17
ACTIVITY_STOPPED = 23
DEVICE_SHUTDOWN = 26

# 每帧事件里「结束当前会话」的类型（亮屏/解锁/开机不开启会话）
ENDS_SESSION = {SCREEN_OFF, KEYGUARD_SHOWN, DEVICE_SHUTDOWN}

BOUNDARY_HOUR = 4  # 逻辑日 04:00 ~ 次日 04:00
TZ = timezone(timedelta(hours=8))  # 诊断按北京时间显示；时区只影响展示，不影响判定


def logical_day_key(millis: int) -> str:
    """与 TimeBoundary.logicalDayKey 一致：减 4 小时后取自然日。"""
    dt = datetime.fromtimestamp(millis / 1000, TZ) - timedelta(hours=BOUNDARY_HOUR)
    return dt.date().isoformat()


def fmt_duration(millis: int) -> str:
    minutes = round(millis / 60000)
    if minutes < 60:
        return f"{minutes}m"
    return f"{minutes // 60}h{minutes % 60:02d}m"


def fmt_clock(millis: int) -> str:
    return datetime.fromtimestamp(millis / 1000, TZ).strftime("%m-%d %H:%M:%S")


def connect(path: str) -> sqlite3.Connection:
    # 只读打开，避免诊断动作本身弄脏数据（WAL 也一并接管）
    conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    conn.row_factory = sqlite3.Row
    return conn


def table_exists(conn: sqlite3.Connection, name: str) -> bool:
    row = conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,)
    ).fetchone()
    return row is not None


def build_sessions(events, min_session_millis: int):
    """与 ForegroundSessionBuilder 同规则：只跟踪唯一一个「当前前台 App」。"""
    sessions = []          # (pkg, start, end, reason)
    dropped = []           # 被阈值丢掉的（含零长度）
    current = None

    def close(at: int, reason: str):
        nonlocal current
        if current is None:
            return
        pkg, start = current
        current = None
        if at <= start:
            return
        if at - start < min_session_millis:
            dropped.append((pkg, start, at, reason, "短于阈值"))
        else:
            sessions.append((pkg, start, at, reason))

    for e in events:
        et, ts, pkg = e["event_type"], e["timestamp_millis"], e["package_name"]
        if et == FOREGROUND:
            if not pkg:
                continue
            if current and current[0] == pkg:
                continue  # 同一 App 重复 RESUMED：不切碎会话
            close(ts, "APP_SWITCHED")
            current = (pkg, ts)
        elif et in (BACKGROUND, ACTIVITY_STOPPED):
            if current and current[0] == pkg:
                close(ts, "APP_BACKGROUNDED")
        elif et in ENDS_SESSION:
            close(ts, "SCREEN_OFF/KEYGUARD/SHUTDOWN")

    if current is not None:
        last_ts = events[-1]["timestamp_millis"] if events else current[1]
        close(last_ts, "EVENTS_END")
    return sessions, dropped


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("db")
    ap.add_argument("--min-session-seconds", type=int, default=60,
                    help="与 ForegroundSessionBuilder.DEFAULT_MIN_SESSION_MILLIS 保持一致")
    ap.add_argument("--days", type=int, default=3, help="报告最近几个逻辑日")
    args = ap.parse_args()

    min_ms = args.min_session_seconds * 1000
    conn = connect(args.db)

    print("=" * 78)
    print("TimeLedger 系统感知诊断")
    print("=" * 78)
    print(f"数据库        : {args.db}")
    print(f"短会话阈值    : {args.min_session_seconds}s（App 默认 60s）")

    # ---------------- 原始事件 ----------------
    if not table_exists(conn, "usage_events"):
        print("\n[!] 没有 usage_events 表 —— 数据库可能不是这个 App 的，或者还没采过数据。")
        return 1

    events = [dict(r) for r in conn.execute(
        "SELECT event_type, timestamp_millis, package_name FROM usage_events ORDER BY timestamp_millis"
    )]
    print(f"原始事件      : {len(events)} 条")
    if not events:
        print("\n[!] usage_events 是空的：要么还没授权「使用情况访问」，")
        print("    要么授权后还没回过首页（采集发生在回到首页时）。")
        return 0

    print(f"事件时间范围  : {fmt_clock(events[0]['timestamp_millis'])} ~ {fmt_clock(events[-1]['timestamp_millis'])}")
    by_type = defaultdict(int)
    for e in events:
        by_type[e["event_type"]] += 1
    names = {FOREGROUND: "RESUMED", BACKGROUND: "PAUSED", ACTIVITY_STOPPED: "STOPPED",
             SCREEN_OFF: "SCREEN_OFF", KEYGUARD_SHOWN: "KEYGUARD", DEVICE_SHUTDOWN: "SHUTDOWN"}
    print("事件类型分布  : " + ", ".join(
        f"{names.get(t, t)}={c}" for t, c in sorted(by_type.items(), key=lambda kv: -kv[1])
    ))

    sessions, dropped = build_sessions(events, min_ms)
    total_session = sum(end - start for _, start, end, _ in sessions)
    total_dropped = sum(end - start for _, start, end, _, _ in dropped)

    print()
    print("-" * 78)
    print("① 系统事实（按 App 的规则重建前台会话）")
    print("-" * 78)
    print(f"有效会话      : {len(sessions)} 段，合计 {fmt_duration(total_session)}")
    print(f"被阈值丢掉    : {len(dropped)} 段，合计 {fmt_duration(total_dropped)}"
          f"   ← 这就是「偏少」的一部分")
    if total_session + total_dropped > 0:
        share = total_dropped / (total_session + total_dropped) * 100
        print(f"丢弃占比      : {share:.1f}% 的前台时间没有变成记录")

    buckets = [(0, 60_000, "<1m"), (60_000, 300_000, "1–5m"), (300_000, 1_800_000, "5–30m"),
               (1_800_000, 10**15, ">30m")]
    print("会话时长分布  :", end=" ")
    for lo, hi, label in buckets:
        n = sum(1 for _, s, e, _ in sessions if lo <= (e - s) < hi)
        print(f"{label}={n}", end="  ")
    print()

    # ---------------- 生成的记录 ----------------
    if not table_exists(conn, "time_records"):
        print("\n[!] 没有 time_records 表")
        return 1
    records = [dict(r) for r in conn.execute(
        "SELECT id, start_time_millis, end_time_millis, category_id, activity_id, source,"
        " confidence, note, external_key, is_deleted FROM time_records WHERE is_deleted = 0"
        " ORDER BY start_time_millis"
    )]

    by_source = defaultdict(lambda: [0, 0])
    for r in records:
        d = r["end_time_millis"] - r["start_time_millis"]
        by_source[r["source"]][0] += 1
        by_source[r["source"]][1] += d

    print()
    print("-" * 78)
    print("② 数据库里真正生成的记录")
    print("-" * 78)
    for src, (n, d) in sorted(by_source.items()):
        print(f"{src:<10}: {n:>4} 条，合计 {fmt_duration(d)}")
    if not records:
        print("（一条记录都没有）")

    sys_records = [r for r in records if r["source"] in ("SYSTEM", "SUGGESTED")]
    pending = [r for r in records if r["source"] == "SUGGESTED"]
    unclassified = [r for r in records if r["category_id"] is None and r["activity_id"] is None]
    short_records = [r for r in sys_records if r["end_time_millis"] - r["start_time_millis"] < 300_000]

    print()
    print("-" * 78)
    print("③ 「碎」到什么程度")
    print("-" * 78)
    print(f"系统生成的记录: {len(sys_records)} 条"
          f"（其中短于 5 分钟的 {len(short_records)} 条，占 "
          f"{(len(short_records) / len(sys_records) * 100) if sys_records else 0:.0f}%）")
    print(f"待确认        : {len(pending)} 条 ← 每一条都要你手动点一下")
    print(f"未归类        : {len(unclassified)} 条，合计 "
          f"{fmt_duration(sum(r['end_time_millis'] - r['start_time_millis'] for r in unclassified))}")

    # ---------------- 差额去哪了 ----------------
    synthetic_total = sum(r["end_time_millis"] - r["start_time_millis"] for r in sys_records)
    print()
    print("-" * 78)
    print("④ 差额去哪了")
    print("-" * 78)
    print(f"系统事实合计  : {fmt_duration(total_session)}")
    print(f"写成记录合计  : {fmt_duration(synthetic_total)}")
    diff = total_session - synthetic_total
    print(f"差额          : {fmt_duration(diff)}")
    print("可能的原因（按上面几节对照）：")
    print(f"  · 短于 {args.min_session_seconds}s 被丢     : {fmt_duration(total_dropped)}")
    print(f"  · 与手动记录重叠被挡下 : 无法从这里直接看出，看下面按天的分解")
    print(f"  · 从未采集到           : 看「事件时间范围」是否覆盖了你实际用的时段")

    # ---------------- 按逻辑日分解 ----------------
    print()
    print("-" * 78)
    print(f"⑤ 最近 {args.days} 个逻辑日（04:00 为界）")
    print("-" * 78)
    days = sorted({logical_day_key(e["timestamp_millis"]) for e in events}, reverse=True)[: args.days]
    for day in days:
        day_sessions = [s for s in sessions if logical_day_key(s[1]) == day]
        day_dropped = [d for d in dropped if logical_day_key(d[1]) == day]
        day_records = [r for r in sys_records if logical_day_key(r["start_time_millis"]) == day]
        day_pending = [r for r in day_records if r["source"] == "SUGGESTED"]
        print(f"{day}: 会话 {len(day_sessions):>3} 段 / {fmt_duration(sum(e - s for _, s, e, _ in day_sessions)):<8}"
              f" 丢弃 {len(day_dropped):>3} 段 / {fmt_duration(sum(e - s for _, s, e, _, _ in day_dropped)):<8}"
              f" 记录 {len(day_records):>3} 条  待确认 {len(day_pending):>3} 条")

    # ---------------- 按 App 排名 ----------------
    print()
    print("-" * 78)
    print("⑥ 用得最多的 App（按前台时长）")
    print("-" * 78)
    per_pkg = defaultdict(int)
    for pkg, start, end, _ in sessions:
        per_pkg[pkg] += end - start
    mappings = {}
    if table_exists(conn, "app_mappings"):
        for r in conn.execute(
            "SELECT package_name, app_label, category_id, activity_id, is_user_confirmed"
            " FROM app_mappings"
        ):
            mappings[r["package_name"]] = dict(r)
    categories = {}
    if table_exists(conn, "categories"):
        for r in conn.execute("SELECT id, name FROM categories"):
            categories[r["id"]] = r["name"]

    print(f"{'App':<34}{'前台时长':>10}  {'归类状态'}")
    for pkg, millis in sorted(per_pkg.items(), key=lambda kv: -kv[1])[:15]:
        m = mappings.get(pkg)
        label = (m or {}).get("app_label") or pkg
        if not m:
            status = "没有映射记录（采集时未登记）"
        elif m["category_id"] or m["activity_id"]:
            status = f"{categories.get(m['category_id'], '?')}" + ("（用户确认）" if m["is_user_confirmed"] else "（系统默认，需确认）")
        else:
            status = "未归类" + ("（用户已确认不归类）" if m["is_user_confirmed"] else "")
        print(f"{label[:33]:<34}{fmt_duration(millis):>10}  {status}")

    # ---------------- 结论 ----------------
    print()
    print("=" * 78)
    print("结论")
    print("=" * 78)
    if total_dropped > 0 and total_dropped > total_session * 0.15:
        print(f"· 偏少的主因很可能是**短会话阈值**：{fmt_duration(total_dropped)}"
              f"（占前台时间 {total_dropped / (total_session + total_dropped) * 100:.0f}%）被丢掉。"
              f"\n  修法：把阈值从 {args.min_session_seconds}s 降到 15–30s，或把碎片合并进相邻会话。")
    if len(pending) > 10:
        print(f"· **待确认 {len(pending)} 条**是「麻烦」的主因：未归类的 App 每条都在问你一次。"
              f"\n  修法：未归类的记录直接落库（事实是你用了它），只在归类页集中问一次。")
    if len(short_records) > len(sys_records) * 0.4 and sys_records:
        print(f"· **碎**：{len(short_records)}/{len(sys_records)} 条短于 5 分钟。"
              f"\n  修法：把相邻的同类会话合并成一条。")
    if not records and sessions:
        print("· 有系统事实但一条记录都没有 —— 这更像 bug，请把整份输出发我。")
    if not pending and not short_records and total_dropped <= total_session * 0.15:
        print("· 从数据上看没有明显的丢失或碎片，问题可能在「没碰手机的时间」这类系统天然看不到的部分。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
