package com.timeledger.app.data.local.seed

/**
 * 内置分类与活动的种子数据（需求八节的清单）。
 *
 * 几点说明：
 * 1. 只列需求里明确给出的活动（学习/娱乐/运动）。其余分类先留空 ——
 *    用户自己加活动比面对一堆用不上的默认值更省事，也符合「不要为了以后可能有用而扩大范围」。
 * 2. 颜色用 ARGB 整数，UI 层直接构造 Compose 的 Color，不需要字符串解析。
 * 3. iconKey 是**抽象标识**，不是具体图标资源名：图标库将来换掉时，
 *    只需要改 UI 层的映射表，不用动数据。
 * 4. 这是种子数据，不是唯一来源：用户可以新增、改名、归档。
 *    匹配靠名称，因此名称本身就是这些条目的稳定标识。
 */
object DefaultCatalog {

    /** 种子活动。[name] 在同一分类下唯一。 */
    data class SeedActivity(val name: String)

    /** 种子分类。[activities] 是该分类下的初始活动。 */
    data class SeedCategory(
        val name: String,
        val colorArgb: Int,
        val iconKey: String,
        val activities: List<SeedActivity>,
    )

    val categories: List<SeedCategory> = listOf(
        SeedCategory(
            name = "学习",
            colorArgb = 0xFF3F6BFF.toInt(),
            iconKey = "school",
            activities = listOf(
                "Java", "Python", "C++", "数据结构", "算法", "LeetCode",
                "英语", "阅读", "AI Agent", "课程", "作业", "项目",
            ).map { SeedActivity(it) },
        ),
        SeedCategory(
            name = "工作",
            colorArgb = 0xFF6A4CFF.toInt(),
            iconKey = "work",
            activities = emptyList(),
        ),
        SeedCategory(
            name = "娱乐",
            colorArgb = 0xFFFF8A3D.toInt(),
            iconKey = "entertainment",
            activities = listOf("B站", "游戏", "影视", "音乐", "小说", "社交媒体")
                .map { SeedActivity(it) },
        ),
        SeedCategory(
            name = "生活",
            colorArgb = 0xFF2FB37A.toInt(),
            iconKey = "life",
            activities = emptyList(),
        ),
        SeedCategory(
            name = "运动",
            colorArgb = 0xFF00A5A5.toInt(),
            iconKey = "sport",
            activities = listOf("健身", "跑步", "篮球", "足球", "散步", "骑行")
                .map { SeedActivity(it) },
        ),
        SeedCategory(
            name = "社交",
            colorArgb = 0xFFFF5C8A.toInt(),
            iconKey = "social",
            activities = emptyList(),
        ),
        SeedCategory(
            name = "交通",
            colorArgb = 0xFF8A93A6.toInt(),
            iconKey = "transport",
            activities = emptyList(),
        ),
        SeedCategory(
            name = "睡眠",
            colorArgb = 0xFF5B4B8A.toInt(),
            iconKey = "sleep",
            activities = emptyList(),
        ),
        SeedCategory(
            name = "个人",
            colorArgb = 0xFFB08A00.toInt(),
            iconKey = "personal",
            activities = emptyList(),
        ),
        SeedCategory(
            name = "其他",
            colorArgb = 0xFF7A7A7A.toInt(),
            iconKey = "other",
            activities = emptyList(),
        ),
        // ⚠️ 必须加在**最后**：sortOrder 用的是下标，插在中间会把已有分类的排序整体挪一位。
        //
        // 「熄屏」由系统感知自动写入（见 ScreenOffPeriods）：手机没在用的时候，
        // 那段时间以前是**空白**（未知），现在是**一条记录**（已知）。
        // 用户的原话：「或许可以设定一个熄屏的分类，这样就可以避免某个统计时间够长的风险」——
        // 它既堵住了「合并把空档算进 App 时长」的漏洞，也让一天的账变成平的。
        //
        // 名字刻意用**事实描述**（熄屏）而不是推论（睡眠/空闲）：手机熄屏不等于人在睡觉，
        // 但那段时间确实「手机没在用」。用户可以在时间轴上点开它，改成真实活动
        // （看书、上课、写作业），统计会跟着变 —— 这正是他要的用法。
        SeedCategory(
            name = "熄屏",
            colorArgb = 0xFF546E7A.toInt(),
            iconKey = "screen_off",
            activities = emptyList(),
        ),
    )
}
