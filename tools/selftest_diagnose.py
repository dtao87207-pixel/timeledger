#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
诊断工具的自测：用 app/schemas 里导出的**真实建表语句**造一个假数据库，
再塞进一份「典型的一天」，然后跑 diagnose_usage.py 看它报的数字对不对。

这样做的理由：诊断工具本身也得先被验证过。
等真机数据到手才发现工具算错，是最浪费时间的一种情况。

用法：
    python tools/selftest_diagnose.py
"""

from __future__ import annotations

import json
import os
import sqlite3
import subprocess
import sys
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
SCHEMA = os.path.join(
    PROJECT, "app", "schemas",
    "com.timeledger.app.data.local.TimeLedgerDatabase", "1.json",
)
OUT_DIR = os.path.join(PROJECT, "build", "diagnose")
DB = os.path.join(OUT_DIR, "selftest.db")
TZ = timezone(timedelta(hours=8))
PY = sys.executable


def ts(day: str, hour: int, minute: int = 0, second: int = 0) -> int:
    dt = datetime.strptime(f"{day} {hour:02d}:{minute:02d}:{second:02d}", "%Y-%m-%d %H:%M:%S")
    return int(dt.replace(tzinfo=TZ).timestamp() * 1000)


def create_db() -> sqlite3.Connection:
    os.makedirs(OUT_DIR, exist_ok=True)
    if os.path.exists(DB):
        os.remove(DB)
    conn = sqlite3.connect(DB)
    with open(SCHEMA, encoding="utf-8") as f:
        schema = json.load(f)
    for entity in schema["database"]["entities"]:
        # Room 导出的 DDL 里的 ${TABLE_NAME} 是占位符
        conn.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
    conn.commit()
    return conn


def main() -> int:
    conn = create_db()
    day = "2026-09-13"

    # ---- 造事件：模拟「一天里切来切去」的真实形态 ----
    events = []  # (pkg, type, millis)
    eid = 0

    def ev(pkg, typ, millis):
        nonlocal eid
        eid += 1
        events.append((eid, pkg, typ, millis))

    # 上午：学习通为主，中间 6 次短切到微信（每次 40 秒 —— 会被 60s 阈值丢掉）
    ev("com.study", 1, ts(day, 9, 0))
    cursor = ts(day, 9, 0)
    for i in range(6):
        cursor += 25 * 60 * 1000
        ev("com.tencent.mm", 1, cursor)                     # 切到微信
        ev("com.study", 1, cursor + 40 * 1000)              # 40 秒后切回
        ev("com.tencent.mm", 2, cursor + 40 * 1000)
    ev("com.study", 2, ts(day, 12, 0))                      # 12:00 结束

    # 中午：微信 30 分钟（有效会话）
    ev("com.tencent.mm", 1, ts(day, 12, 30))
    ev("com.tencent.mm", 2, ts(day, 13, 0))

    # 下午：B 站碎片 8 段，每段 2 分钟，中间隔 3 分钟（有效但很短 → 「碎」）
    for i in range(8):
        start = ts(day, 14, 0) + i * 5 * 60 * 1000
        ev("tv.danmaku.bili", 1, start)
        ev("tv.danmaku.bili", 2, start + 2 * 60 * 1000)

    # 晚上：熄屏结束一段较长的使用
    ev("com.study", 1, ts(day, 20, 0))
    ev("com.study", 2, ts(day, 21, 30))

    conn.executemany(
        "INSERT INTO usage_events (package_name, class_name, event_type, timestamp_millis,"
        " event_key, created_at_millis) VALUES (?,?,?,?,?,?)",
        [(p, None, t, m, f"{t}|{m}|{p}", m) for _, p, t, m in events],
    )

    # ---- 造这个 App 会生成的记录：只有 >=60s 的会话才落库 ----
    records = [
        # 上午学习通：被 6 次短切切成 6 段（这里简化成 3 段示意）
        (ts(day, 9, 0), ts(day, 9, 25), 1, 100, "SYSTEM", "usage|1|a|com.study", None),
        (ts(day, 11, 0), ts(day, 12, 0), 1, 100, "SYSTEM", "usage|1|b|com.study", None),
        # 微信 30 分钟：未归类 → 待确认
        (ts(day, 12, 30), ts(day, 13, 0), None, None, "SUGGESTED", "usage|1|c|com.tencent.mm", "微信"),
        # B 站 8 段 2 分钟：未归类 → 8 条待确认
        *[(ts(day, 14, 0) + i * 5 * 60 * 1000, ts(day, 14, 2) + i * 5 * 60 * 1000,
           None, None, "SUGGESTED", f"usage|1|d{i}|tv.danmaku.bili", "哔哩哔哩") for i in range(8)],
        # 晚上学习通 90 分钟
        (ts(day, 20, 0), ts(day, 21, 30), 1, 100, "SYSTEM", "usage|1|e|com.study", None),
    ]
    conn.executemany(
        "INSERT INTO time_records (start_time_millis, end_time_millis, category_id, activity_id,"
        " source, confidence, count_value, note, external_key, is_deleted, created_at_millis,"
        " updated_at_millis) VALUES (?,?,?,?,?,?,NULL,?,?,0,?,?)",
        [(s, e, c, a, src, 0.6, note, key, s, s) for s, e, c, a, src, key, note in records],
    )

    conn.executemany(
        "INSERT INTO categories (name, color_argb, icon_key, sort_order, is_built_in,"
        " is_archived, created_at_millis, updated_at_millis) VALUES (?,?,NULL,?,1,0,0,0)",
        [("学习", None, 0), ("娱乐", None, 1)],
    )
    conn.executemany(
        "INSERT INTO app_mappings (package_name, app_label, category_id, activity_id,"
        " confidence, is_user_confirmed, updated_at_millis) VALUES (?,?,?,?,?,?,0)",
        [
            ("com.study", "学习通", 1, None, 1.0, 1),      # 用户已确认
            ("com.tencent.mm", "微信", None, None, 0.0, 0),  # 未归类
            ("tv.danmaku.bili", "哔哩哔哩", None, None, 0.0, 0),
        ],
    )
    conn.commit()
    conn.close()

    print(f"自测数据库已生成: {DB}")
    print(f"事件 {len(events)} 条，记录 {len(records)} 条")
    print(f"预期：丢弃 6 段短会话（共约 4 分钟）；"
          f"待确认 9 条（微信 1 + B站 8）；短记录 8 条")
    print("=" * 78, flush=True)  # 不 flush 的话，管道里这行会跑到子进程输出的后面

    rc = subprocess.call([PY, os.path.join(HERE, "diagnose_usage.py"), DB])
    return rc


if __name__ == "__main__":
    sys.exit(main())
