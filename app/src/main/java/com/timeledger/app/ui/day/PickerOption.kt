package com.timeledger.app.ui.day

/**
 * 选择器里的一个选项（分类或活动）。
 *
 * 放在共用包里而不是各自定义：目标表单、课程表表单都需要它，
 * 两处各写一个同名字段完全相同的数据类，正是「不要重复创建相似的数据类」要避免的情况。
 */
data class PickerOption(
    val id: Long,
    val label: String,
    val colorArgb: Int?,
)
