package com.timeledger.app.domain.model

/**
 * 时间记录的来源（需求三节）。
 *
 * 优先级（需求原文）：`MANUAL > MERGED > SYSTEM > SUGGESTED`
 *
 * 语义约定：
 * - [MANUAL]    用户主动记录。**任何系统流程都不得覆盖或删除它**（见 TimeRecordRepository 的写入保护）。
 * - [MERGED]    用户确认/修改系统推测后的合并结果，可信度高但低于用户亲手记录。
 * - [SYSTEM]    Android 系统检测到的原始事实（UsageStats 等），代表「手机发生了什么」，
 *               不代表「用户在做什么」，因此仍需用户确认。
 * - [SUGGESTED] 规则/上下文推测出的候选行为，置信度低，**必须**经用户确认才能成为正式时间轴。
 *
 * [priority] 数值越大越权威，用于时间轴冲突消解：
 * 同一时段被多条记录覆盖时，取优先级最高者，同优先级取置信度更高者。
 */
enum class RecordSource(val priority: Int) {
    SUGGESTED(0),
    SYSTEM(1),
    MERGED(2),
    MANUAL(3);

    /** 是否由用户亲手录入（不可被系统覆盖）。 */
    val isUserAuthored: Boolean get() = this == MANUAL

    /** 是否为系统/规则自动产生（可被用户覆盖，也可被再次分析替换）。 */
    val isSystemGenerated: Boolean get() = this == SYSTEM || this == SUGGESTED

    /** 是否等待用户确认（推测记录一律待确认）。 */
    val needsConfirmation: Boolean get() = this == SUGGESTED

    companion object {
        /**
         * 数据库与外部输入的统一解析入口：未知字符串返回 null，由调用方决定降级策略，
         * 避免反序列化旧数据时因枚举新增而崩溃（数据库迁移兼容性）。
         */
        fun fromNameOrNull(value: String?): RecordSource? =
            value?.let { name -> entries.firstOrNull { it.name == name } }
    }
}
