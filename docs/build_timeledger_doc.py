#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""生成 TimeLedger 作业说明文档（python-docx）。"""

import os
from docx import Document
from docx.shared import Pt, Cm, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_ALIGN_VERTICAL, WD_TABLE_ALIGNMENT, WD_ROW_HEIGHT_RULE
from docx.enum.text import WD_BREAK
from docx.oxml.ns import qn
from docx.oxml import OxmlElement

SONG, HEI, MONO = "宋体", "黑体", "Consolas"
GRAY = RGBColor(0x59, 0x59, 0x59)


def set_font(run, name=SONG, size=11, bold=False, color=None):
    run.font.name = name
    run.font.size = Pt(size)
    run.bold = bold
    if color is not None:
        run.font.color.rgb = color
    rpr = run._element.get_or_add_rPr()
    rfonts = rpr.find(qn("w:rFonts"))
    if rfonts is None:
        rfonts = OxmlElement("w:rFonts")
        rpr.append(rfonts)
    for attr in ("w:eastAsia", "w:ascii", "w:hAnsi"):
        rfonts.set(qn(attr), name)


def shade(cell, fill):
    el = OxmlElement("w:shd")
    el.set(qn("w:val"), "clear")
    el.set(qn("w:fill"), fill)
    cell._tc.get_or_add_tcPr().append(el)


def body(doc, text, size=11, bold=False, indent=True, space_after=5):
    p = doc.add_paragraph()
    pf = p.paragraph_format
    pf.space_before = Pt(0)
    pf.space_after = Pt(space_after)
    pf.line_spacing = 1.28
    if indent:
        pf.first_line_indent = Pt(size * 2)
    set_font(p.add_run(text), SONG, size, bold)
    return p


def heading(doc, text, level=1):
    p = doc.add_paragraph()
    pf = p.paragraph_format
    pf.space_before = Pt(14 if level == 1 else 9)
    pf.space_after = Pt(6 if level == 1 else 4)
    pf.keep_with_next = True
    size = 15 if level == 1 else 12
    set_font(p.add_run(text), HEI, size, True)
    return p


def bullet(doc, text, size=11):
    p = doc.add_paragraph(style="List Bullet")
    p.paragraph_format.space_after = Pt(2)
    p.paragraph_format.line_spacing = 1.26
    set_font(p.add_run(text), SONG, size)
    return p


def numbered(doc, idx, text, size=11):
    p = doc.add_paragraph()
    pf = p.paragraph_format
    pf.left_indent = Cm(0.9)
    pf.first_line_indent = Cm(-0.9)
    pf.space_after = Pt(3)
    pf.line_spacing = 1.26
    set_font(p.add_run("%s  " % idx), SONG, size, True)
    set_font(p.add_run(text), SONG, size)
    return p


def boxed(doc, text, fill="F7F7F7", size=10, width=15.4):
    t = doc.add_table(rows=1, cols=1)
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    cell = t.cell(0, 0)
    cell.width = Cm(width)
    shade(cell, fill)
    p = cell.paragraphs[0]
    p.paragraph_format.space_before = Pt(4)
    p.paragraph_format.space_after = Pt(4)
    p.paragraph_format.line_spacing = 1.18
    for i, line in enumerate(text.split("\n")):
        if i:
            p.add_run().add_break()
        set_font(p.add_run(line), SONG, size)
    return t


def grid_table(doc, rows, widths=None, header=True, size=9.5):
    t = doc.add_table(rows=len(rows), cols=len(rows[0]))
    t.style = "Table Grid"
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    t.autofit = False
    for r, row in enumerate(rows):
        for c, val in enumerate(row):
            cell = t.cell(r, c)
            cell.vertical_alignment = WD_ALIGN_VERTICAL.CENTER
            p = cell.paragraphs[0]
            p.paragraph_format.space_before = Pt(2)
            p.paragraph_format.space_after = Pt(2)
            set_font(p.add_run(str(val)), SONG, size, bold=(header and r == 0))
            if header and r == 0:
                shade(cell, "EDEDED")
    if widths:
        for c, w in enumerate(widths):
            for row in t.rows:
                row.cells[c].width = Cm(w)
    return t


def placeholder(doc, label, instruction, caption, height=3.5):
    t = doc.add_table(rows=1, cols=1)
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    row = t.rows[0]
    row.height = Cm(height)
    row.height_rule = WD_ROW_HEIGHT_RULE.AT_LEAST
    cell = t.cell(0, 0)
    cell.vertical_alignment = WD_ALIGN_VERTICAL.CENTER
    shade(cell, "FAFAFA")
    tcPr = cell._tc.get_or_add_tcPr()
    borders = OxmlElement("w:tcBorders")
    for edge in ("top", "left", "bottom", "right"):
        e = OxmlElement("w:%s" % edge)
        e.set(qn("w:val"), "dashed")
        e.set(qn("w:sz"), "6")
        e.set(qn("w:color"), "BFBFBF")
        borders.append(e)
    tcPr.append(borders)

    p = cell.paragraphs[0]
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.space_after = Pt(2)
    set_font(p.add_run("【截图占位 · %s】" % label), HEI, 10, True, GRAY)
    p2 = cell.add_paragraph()
    p2.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p2.paragraph_format.space_after = Pt(2)
    set_font(p2.add_run(instruction), SONG, 9, False, GRAY)

    cap = doc.add_paragraph()
    cap.alignment = WD_ALIGN_PARAGRAPH.CENTER
    cap.paragraph_format.space_before = Pt(2)
    cap.paragraph_format.space_after = Pt(8)
    set_font(cap.add_run(caption), SONG, 9, False, GRAY)


def two_shots(doc, left, right, cap_left, cap_right, width=5.0):
    t = doc.add_table(rows=1, cols=2)
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    t.autofit = False
    for i, (img, cap) in enumerate(((left, cap_left), (right, cap_right))):
        cell = t.cell(0, i)
        cell.width = Cm(7.6)
        cell.vertical_alignment = WD_ALIGN_VERTICAL.TOP
        p = cell.paragraphs[0]
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p.paragraph_format.space_before = Pt(0)
        p.paragraph_format.space_after = Pt(2)
        p.add_run().add_picture(img, width=Cm(width))
        p2 = cell.add_paragraph()
        p2.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p2.paragraph_format.space_after = Pt(2)
        set_font(p2.add_run(cap), SONG, 9, False, GRAY)


def page_break(doc):
    doc.add_paragraph().add_run().add_break(WD_BREAK.PAGE)


def build(path, img_a, img_b):
    doc = Document()
    sec = doc.sections[0]
    sec.page_width, sec.page_height = Cm(21.0), Cm(29.7)
    sec.top_margin, sec.bottom_margin = Cm(2.3), Cm(2.1)
    sec.left_margin, sec.right_margin = Cm(2.6), Cm(2.6)

    normal = doc.styles["Normal"]
    normal.font.name = SONG
    normal.font.size = Pt(11)
    normal.element.rPr.rFonts.set(qn("w:eastAsia"), SONG)

    t = doc.add_paragraph()
    t.alignment = WD_ALIGN_PARAGRAPH.CENTER
    t.paragraph_format.space_before = Pt(4)
    t.paragraph_format.space_after = Pt(2)
    set_font(t.add_run("用 AI 智能体从零构建一款 Android 时间记账 App"), HEI, 17, True)

    st = doc.add_paragraph()
    st.alignment = WD_ALIGN_PARAGRAPH.CENTER
    st.paragraph_format.space_after = Pt(12)
    set_font(st.add_run("——文本大模型辅助创作说明文档"), SONG, 11, False, GRAY)

    # 1
    heading(doc, "1  作品基本信息")
    grid_table(
        doc,
        [
            ["项目", "内容"],
            ["作品名称", "TimeLedger 时间记账（Android 原生 App V1）"],
            ["作品类型", "代码与数据类（移动应用）"],
            ["姓名", "（本人填写）"],
            ["学号", "（本人填写）"],
            ["班级", "（本人填写）"],
            ["完成日期", "2026 年 10 月 22 日（构建期 2026-09-12 ～ 09-14）"],
        ],
        widths=[3.6, 11.8],
    )

    # 2
    heading(doc, "2  作品目标")
    body(
        doc,
        "做一款给自己用的 Android 时间账本：不用手动打卡，也能看清一天的时间去哪了。"
        "它把课程表、目标和手机的系统使用记录自动拼成一条时间轴，用户只需要“确认”而不用“记录”。"
        "面向的是和我一样的学生——上课时间固定、自习时间碎片化、手动记两天就放弃。"
        "作品要解决的是“记不下来”和“记了也看不出问题”两件事，最终产出一个能装进手机、"
        "连续用满一天的完整 App，而不是一个功能演示。",
    )

    # 3
    heading(doc, "3  模型与工具")
    body(doc, "全程只用一个智能体，靠它连续调用命令行、文件读写与构建工具完成，不外包给多个模型拼接。", indent=True)
    grid_table(
        doc,
        [
            ["用途", "平台 / 工具", "模型"],
            ["需求理解、方案设计、全部代码与文档", "DeepSeek Harness 桌面客户端（智能体模式）", "deepseek-v4-pro"],
            ["真机截图理解（看 UI 效果再改）", "同上", "deepseek-v4-flash-vision-exp"],
            ["构建与验证", "Gradle 8.14.3 + AGP 8.13 + Kotlin 2.2.21", "—"],
        ],
        widths=[5.0, 7.2, 3.2],
    )
    body(doc, "选择理由：", indent=True, space_after=2)
    bullet(doc, "它是智能体而不是对话框：能自己跑 Gradle、读编译报错、改代码、再跑测试，"
                "一次任务里有 1052 次文件编辑和 606 次命令行调用，人只负责给方向和验收。")
    bullet(doc, "上下文足够长，17 节需求书（5572 字）能整份读进去，不必拆成碎片反复粘贴。")
    bullet(doc, "能看截图：界面改完直接看真机截图判断“挤不挤、冷不冷”，不用我描述。")

    # 4
    heading(doc, "4  提示词与实现思路")

    heading(doc, "4.1  第一版：一份 17 节的开发需求书", 2)
    boxed(
        doc,
        "# 时间管理 App V1 开发需求\n"
        "## 一、产品定位\n"
        "开发一个 Android 原生 App，暂定为“时间管理/时间记账”。\n"
        "核心不是让用户手动记录一天，而是：通过“目标 → 计划 → 系统感知 → 时间轴 → 用户确认 → 复盘”\n"
        "自动还原用户一天的时间使用情况。产品核心价值：让用户少记录，却更清楚自己一天是怎么度过的。\n"
        "……（全文 5572 字，共 17 节）",
    )
    body(
        doc,
        "章节清单：一 产品定位｜二 V1 核心功能（首页 / 日历 / 时间轴）｜三 低操作成本的三条路径（自动 / 主动 / 事后确认）｜"
        "四 目标系统（TIME / COUNT / FREQUENCY，日周月年）｜五 计划与课程表｜六 Android 系统数据感知｜七 分析原则｜"
        "八 App 分类｜九 App→活动映射｜十 计划与实际的比较｜十一 每日复盘｜十二 提醒｜十三 统计分析｜"
        "十四 技术栈｜十五 V1 明确不做｜十六 开发原则｜十七 最终目标。",
        space_after=6,
    )
    body(
        doc,
        "这一版的效果是“方向全对、颗粒度失控”：AI 把 17 节拆成 25 个模块，从数据层一路做到界面，"
        "但它当时没有真机、也没有我的使用数据，所以“做出来”和“好用”之间还差整整一轮。",
    )

    heading(doc, "4.2  中间的小步反馈：改的是判断，不是功能", 2)
    body(doc, "装到手机用了一天之后，真正的迭代靠的是一句一句的具体反馈（以下为原话）：", indent=True, space_after=3)
    boxed(
        doc,
        "「感觉它抓取应用的时间并不十分准确，到头来还是要我自己手动调整，显得麻烦」\n"
        "「你这个轴太挤了吧，想要修改时间都不太好操作，我的建议是放大」\n"
        "「可是这跟手机内置的统计应用的时间并不一致，能否看看那边是怎么统计的先，\n"
        "  一切最好以手机端内置的为主」",
        size=9.5,
    )
    body(
        doc,
        "第三条最要紧：它把“算法怎么算”变成了“跟谁对齐”。AI 于是重写了会话重建，改成 Android 自己的口径，"
        "结果是王者荣耀 38 分钟、手机内置也是 38 分钟；哔哩哔哩 18 分对 18 分。"
        "如果只写“再准一点”，这一轮永远不会发生。",
    )

    heading(doc, "4.3  第二版：V1.1 产品改进反馈（24 节）", 2)
    boxed(
        doc,
        "# TimeLedger V1.1 产品改进反馈\n"
        "请基于当前 TimeLedger 项目的实际代码和现有功能，对产品进行一次「V1 → V1.1」级别的产品体验优化。\n"
        "核心原则：不为了增加功能而增加功能。\n"
        "第一原则：先保持现有功能稳定（列出 Room / 数据模型 / 来源优先级 / 系统感知等 20 项不许动的东西）。\n"
        "……（全文 6834 字，24 节，含 P0/P1/P2 排序与 Step1–7 的执行方式）",
    )
    body(doc, "与第一版相比，改的是“怎么写要求”，不是“要什么”：", indent=True, space_after=2)
    grid_table(
        doc,
        [
            ["维度", "第一版", "第二版"],
            ["出发点", "要哪些功能", "哪些体验要改、按什么优先级改"],
            ["篇幅", "5572 字 / 17 节", "6834 字 / 24 节 + 7 步执行方式"],
            ["约束", "只写了要做什么", "写明 20 项不许动的东西、不许为加功能而加功能"],
            ["验收", "功能清单", "P0 必须做 / P1 增强 / P2 以后，附判断标准"],
            ["产出", "25 个模块的骨架", "首页信息层级重排、当前时刻线、空状态、7 天平均"],
        ],
        widths=[2.6, 5.4, 7.4],
    )

    heading(doc, "4.4  第三版：UI 鲜活化（带一条硬约束）", 2)
    boxed(
        doc,
        "我希望在不改变现有功能、数据结构和核心交互逻辑的前提下，对整个 Android App 的 UI 做一次\n"
        "“鲜活化”设计。目前功能闭环已经基本完成，但界面如果只是简单的列表、卡片、文字和统计图，\n"
        "会比较像传统工具 App。",
        size=9.5,
    )
    body(
        doc,
        "“不改变现有功能、数据结构和核心交互逻辑”这半句是刻意加的：前一版已经证明，"
        "AI 一旦觉得“顺手”，就会连业务逻辑一起重写。加了约束之后，它先交设计系统（颜色 / 字号 / 圆角 / 间距四套 token），"
        "再逐页套用，排版从 9 级收敛到 6 级——改了几十个界面，没有一条业务逻辑被碰。",
    )

    heading(doc, "4.5  实现过程（五步）", 2)
    numbered(doc, "①", "需求成文：把想法写成 17 节需求书，明确“不做什么”比“做什么”先写。")
    numbered(doc, "②", "拆解：智能体把它拆成 25 个模块并建待办清单，按“数据层 → 目标 → 计划 → 系统感知 → 时间轴 → 复盘 → 统计”递进。")
    numbered(doc, "③", "逐模块实现与自检：每个模块写完就编译、跑单元测试；中途踩到中文路径导致 37 个测试类加载失败，"
                    "把工程挪到纯英文路径才解决。")
    numbered(doc, "④", "真机迭代：装到手机上用两天，按“不准 / 太挤 / 偏冷 / 要显示应用名”逐条返工，共三轮。")
    numbered(doc, "⑤", "收尾成档：README 记 27 条“改了会出 bug”的设计决策，WORKLOG 按天记“做了什么、拿什么验证的”。")
    body(
        doc,
        "进阶用法说明：本作品用的是“让智能体分步骤完成任务”。会话里给它设了目标与待办清单，"
        "它自己按模块推进，每完成一块就编译、跑测试、再进下一块；前一步的验证结果（编译报错、测试失败、"
        "真机截图）直接成为下一步的输入。这也是它能连续工作三天、改出一万多行 Kotlin 的原因。",
    )

    # 5
    heading(doc, "5  过程附图")
    body(doc, "（图 1、图 2 请在提交前替换为实际截图；图 3、图 4 为装到手机后的真机截图。）", indent=True, space_after=6)
    placeholder(doc, "图 1", "截取智能体对话窗口：输入 17 节需求书并发送的完整内容",
                "图 1　第一版提示词（17 节需求书）的输入界面", height=2.6)
    placeholder(doc, "图 2", "截取智能体执行过程：模块待办清单、Gradle 编译或单元测试输出",
                "图 2　智能体按模块推进，逐块编译并跑单元测试", height=2.6)
    two_shots(
        doc, img_a, img_b,
        "图 3　真机首页：今日洞察、已有记录占比与时间轴",
        "图 4　重新读取系统感知后的首页：346 段时间、457 段自动归类",
    )

    # 6
    page_break(doc)
    heading(doc, "6  最终作品与个人总结")

    heading(doc, "6.1  最终作品", 2)
    body(
        doc,
        "作品是一款可安装的 Android App：TimeLedger。装到手机上连续用了两天，"
        "能自动读取系统使用记录、按课程表还原时间轴、算目标完成度、出日/周/月统计。"
        "附件包含 APK、全部源码与工程文档（README、WORKLOG、V1.1 待办、真机走查清单）。",
    )
    grid_table(
        doc,
        [
            ["项目", "规模", "说明"],
            ["功能模块", "25 个全部实现", "数据层、目标、计划、系统感知、时间轴、复盘、提醒、统计、UI 设计系统"],
            ["代码", "225 个 Kotlin 文件", "Compose + Room + ViewModel + Repository，分层单向依赖"],
            ["测试", "700+ 条 JVM 单元测试", "2026-09-13 记录 701 条全绿；含边界、恒等式、迁移用例"],
            ["构建", "APK 13.2 MB", "Gradle 8.14.3 / AGP 8.13 / Kotlin 2.2.21 / Compose BOM 2025.12"],
            ["对话", "103 条本人指令，3 天", "2026-09-12 21:41 ～ 09-14 16:49"],
        ],
        widths=[2.8, 5.2, 7.4],
    )

    heading(doc, "6.2  AI 辅助与本人修改的分工", 2)
    grid_table(
        doc,
        [
            ["环节", "由 AI 完成", "由本人完成"],
            ["需求", "把 17 节需求拆成 25 个模块", "写需求书、定“一天从 4:00 开始”"],
            ["实现", "全部 Kotlin 代码、测试、诊断脚本", "决定阈值（熄屏 5→1 分钟、会话 60→10 秒）"],
            ["验证", "编译、跑测试、从手机拉数据库做统计", "装真机连用三天，提具体意见"],
            ["迭代", "按反馈重写算法与界面", "判定“以手机内置统计为准”，要求先出设计系统"],
        ],
        widths=[2.6, 7.0, 5.8],
    )

    heading(doc, "6.3  个人总结", 2)
    body(
        doc,
        "最满意的不是代码量，而是“时间对不上就查到底”那一段：App 统计的王者荣耀 38 分钟和手机内置完全一致，"
        "这个数字不是猜出来的，是让 AI 把会话构建器的四步中间结果逐步打出来、才发现真凶是它自己加的一条"
        "“类名对不上就兜底关闭”。AI 出的问题也很实在：9 月 14 日上午改完“调整目标进度”对话框后，"
        "App 一进主界面就闪退，连续五轮“还是闪退”；同一天重装，把我前一天导入的课表弄丢了；"
        "还有它用 PowerShell 改源码，中文注释被按 ANSI 解码，把三个文件弄坏。我做的修改是："
        "把“不许动数据与业务逻辑”写进提示词、要求一切以手机内置为准、亲眼看真机截图再提意见。"
        "下次会先让它把中间产物打出来再改代码，动数据库之前必须先备份。",
    )

    heading(doc, "6.4  引用与合规说明", 2)
    body(
        doc,
        "引用材料：DeepSeek Harness 的会话记录（2026-09-12 ～ 09-14，103 条指令）、项目自带的 README 与 WORKLOG、"
        "以及手机系统“使用情况访问”读到的个人使用数据（仅本人设备）。"
        "合规处理：截图裁掉了状态栏（时间、通知、运营商）；文档不含他人信息、不含任何账号密码；"
        "App 读取的 UsageStats 数据只存在手机本机数据库，未上传。",
    )

    doc.save(path)


if __name__ == "__main__":
    ws = os.path.dirname(os.path.abspath(__file__))
    build(
        os.path.join(ws, "TimeLedger_说明文档.docx"),
        os.path.join(ws, "img", "home_a.png"),
        os.path.join(ws, "img", "home_b.png"),
    )
    print("ok")


