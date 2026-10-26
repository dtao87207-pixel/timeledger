package com.timeledger.app.ui.mapping

import com.timeledger.app.domain.model.Activity
import com.timeledger.app.domain.model.AppMapping
import com.timeledger.app.domain.model.Category
import com.timeledger.app.domain.usage.AppUsageHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App 归类界面状态测试。
 *
 * 这里钉的是两件事：
 * 1. **分组**——「待归类」不等于「归属为空」。用户可能已经明确说过「这个 App 不用归类」，
 *    那种必须算已处理，否则他刚做完决定，那个 App 还赖在待办列表里。
 * 2. **排序**——最近用过的排前面。用户的时间和注意力有限，
 *    先处理「昨天刚用了两小时」的那个 App 才有意义。
 */
class AppMappingStateMapperTest {

    private val nowMillis: Long = 1_700_000_000_000L
    private val mapper = AppMappingStateMapper(nowMillis = { nowMillis })

    private val categories = listOf(
        Category(id = 1L, name = "学习", colorArgb = 0xFF3F6BFF.toInt(), sortOrder = 1),
        Category(id = 2L, name = "娱乐", colorArgb = 0xFFE0574A.toInt(), sortOrder = 2),
        Category(id = 3L, name = "旧分类", sortOrder = 3, isArchived = true),
    )

    private val activities = listOf(
        Activity(id = 10L, categoryId = 1L, name = "Java", colorArgb = 0xFF00AA00.toInt(), sortOrder = 1),
        Activity(id = 11L, categoryId = 1L, name = "算法", sortOrder = 2),
        Activity(id = 20L, categoryId = 2L, name = "B站", sortOrder = 1),
        Activity(id = 30L, categoryId = 1L, name = "已归档活动", sortOrder = 3, isArchived = true),
    )

    private fun mapping(
        pkg: String,
        appLabel: String? = null,
        categoryId: Long? = null,
        activityId: Long? = null,
        isUserConfirmed: Boolean = false,
    ) = AppMapping(
        packageName = pkg,
        appLabel = appLabel,
        categoryId = categoryId,
        activityId = activityId,
        isUserConfirmed = isUserConfirmed,
    )

    /**
     * 造一条使用提示。[minutesUsed] 默认 30 分钟（超过「值得问」的 10 分钟阈值），
     * 传小值可以造出「用时很少、不该打扰」的那些 App。
     */
    private fun hint(
        pkg: String,
        minutesAgo: Long,
        count: Int = 1,
        minutesUsed: Long = 30,
    ) = AppUsageHint(
        packageName = pkg,
        lastForegroundMillis = nowMillis - minutesAgo * 60_000L,
        sessionCount = count,
        foregroundMillis = minutesUsed * 60_000L,
    )

    /**
     * 默认给每个 App 一条「用了 30 分钟」的提示。
     *
     * 因为绝大多数用例关心的是「分组与文案」，而不是「打扰阈值」；
     * 阈值本身由 `an app with tiny usage is listed but not nagged about` 等用例专门盯着。
     * 需要造「用时很少」的场景就显式传 hints。
     */
    private fun map(
        mappings: List<AppMapping>,
        hints: Map<String, AppUsageHint> = mappings.associate {
            it.packageName to hint(it.packageName, minutesAgo = 30)
        },
        form: AppMappingFormState? = null,
        formError: AppMappingFormError? = null,
    ) = mapper.map(mappings, hints, categories, activities, form, formError)

    // ---------------- 分组 ----------------

    @Test
    fun `apps waiting for a decision go to the pending group`() {
        val state = map(listOf(mapping("com.a", appLabel = "甲")))

        assertEquals(1, state.pendingRows.size)
        assertTrue(state.handledRows.isEmpty())
        assertTrue(state.hasPending)
    }

    @Test
    fun `an app with a binding goes to the handled group`() {
        val state = map(listOf(mapping("com.a", categoryId = 1L, activityId = 10L)))

        assertTrue(state.pendingRows.isEmpty())
        assertEquals(1, state.handledRows.size)
    }

    @Test
    fun `an app the user said not to classify counts as handled`() {
        // 用户明确说过「不用归类」，它就不该再出现在待办里 ——
        // 否则他刚做完决定却看到它还在，会以为没保存成功
        val state = map(listOf(mapping("com.chrome", isUserConfirmed = true)))

        assertTrue(state.pendingRows.isEmpty())
        assertEquals(1, state.handledRows.size)
        assertEquals("已确认：不归类", state.handledRows.single().bindingLabel)
    }

    @Test
    fun `a system guess with a target is listed as handled but marked as a guess`() {
        // 系统内置默认映射给了归属，但用户没点头。这条记录会在「待确认」列表里等他确认，
        // 而在这里必须标成「系统推测」—— 否则他会以为这个归类是自己定的
        val state = map(listOf(mapping("com.a", categoryId = 2L)))

        assertTrue(state.pendingRows.isEmpty())
        val row = state.handledRows.single()
        assertFalse(row.isUserConfirmed)
        assertEquals("娱乐（系统推测）", row.bindingLabel)
    }

    @Test
    fun `a user confirmed binding carries no guess marker`() {
        val state = map(listOf(mapping("com.a", categoryId = 2L, isUserConfirmed = true)))

        assertEquals("娱乐", state.handledRows.single().bindingLabel)
    }

    // ---------------- 排序 ----------------

    @Test
    fun `recently used apps come first`() {
        val state = map(
            mappings = listOf(
                mapping("com.old", appLabel = "很久以前"),
                mapping("com.new", appLabel = "刚刚用过"),
                mapping("com.mid", appLabel = "中间"),
            ),
            hints = mapOf(
                "com.old" to hint("com.old", minutesAgo = 60 * 40),
                "com.new" to hint("com.new", minutesAgo = 5),
                "com.mid" to hint("com.mid", minutesAgo = 60),
            ),
        )

        assertEquals(listOf("com.new", "com.mid", "com.old"), state.pendingRows.map { it.packageName })
    }

    @Test
    fun `apps last used long ago come last`() {
        // 三个 App 用得都够多（都值得问），但最近使用时间不同 → 按最近使用排序。
        // 注意「没有使用记录」的那些不在这里：它们根本不会被催（见下一节）
        val state = map(
            mappings = listOf(mapping("com.zzz"), mapping("com.aaa"), mapping("com.seen")),
            hints = mapOf(
                "com.zzz" to hint("com.zzz", minutesAgo = 60 * 24 * 5),
                "com.aaa" to hint("com.aaa", minutesAgo = 60 * 24 * 2),
                "com.seen" to hint("com.seen", minutesAgo = 10),
            ),
        )

        assertEquals(listOf("com.seen", "com.aaa", "com.zzz"), state.pendingRows.map { it.packageName })
    }

    @Test
    fun `the handled group is sorted the same way`() {
        val state = map(
            mappings = listOf(
                mapping("com.old", categoryId = 1L),
                mapping("com.new", categoryId = 1L),
            ),
            hints = mapOf(
                "com.old" to hint("com.old", minutesAgo = 60 * 24 * 3),
                "com.new" to hint("com.new", minutesAgo = 30),
            ),
        )

        assertEquals(listOf("com.new", "com.old"), state.handledRows.map { it.packageName })
    }

    // ---------------- 文案 ----------------

    @Test
    fun `the app name is used when available and the package name otherwise`() {
        val state = map(listOf(mapping("com.tencent.mm", appLabel = "微信"), mapping("com.unknown")))

        val names = state.pendingRows.map { it.displayName }.toSet()
        assertEquals(setOf("微信", "com.unknown"), names)
    }

    @Test
    fun `a blank app name falls back to the package name`() {
        val state = map(listOf(mapping("com.a", appLabel = "   ")))
        assertEquals("com.a", state.pendingRows.single().displayName)
    }

    @Test
    fun `the binding label describes the whole path`() {
        val state = map(
            listOf(
                mapping("a", categoryId = 1L, activityId = 10L, isUserConfirmed = true),
                mapping("b", categoryId = 1L, isUserConfirmed = true),
                mapping("c", activityId = 10L, isUserConfirmed = true),
                mapping("d", categoryId = 1L, activityId = 10L, isUserConfirmed = true),
            )
        )

        assertEquals(
            listOf("学习 / Java", "学习", "Java", "学习 / Java"),
            state.handledRows.sortedBy { it.packageName }.map { it.bindingLabel },
        )
    }

    @Test
    fun `an unbound system guess reads as undetermined`() {
        val state = map(listOf(mapping("com.a")))
        assertEquals("未确定", state.pendingRows.single().bindingLabel)
    }

    @Test
    fun `the usage label says how long ago and how many times`() {
        val state = map(
            mappings = listOf(mapping("com.a")),
            hints = mapOf("com.a" to hint("com.a", minutesAgo = 120, count = 8, minutesUsed = 52)),
        )

        assertEquals("2 小时前用过 · 打开 8 次 · 共 52m", state.pendingRows.single().usageLabel)
    }

    @Test
    fun `an app never seen in the window has no usage label`() {
        val state = map(listOf(mapping("com.a")), hints = emptyMap())
        assertNull(state.minorRows.single().usageLabel)
    }

    @Test
    fun `the colour comes from the activity then the category`() {
        val state = map(
            listOf(
                mapping("a", categoryId = 1L, activityId = 10L),
                mapping("b", categoryId = 2L),
                mapping("c"),
            )
        )

        val rows = state.handledRows.associateBy { it.packageName }
        assertEquals(0xFF00AA00.toInt(), rows.getValue("a").colorArgb)
        assertEquals(0xFFE0574A.toInt(), rows.getValue("b").colorArgb)
        assertNull(state.pendingRows.single().colorArgb)
    }

    @Test
    fun `the summary counts what still needs attention`() {
        val none = map(listOf(mapping("com.a", categoryId = 1L)))
        assertEquals("用得多的 App 都已经归类了", none.summaryLabel)

        val one = map(listOf(mapping("com.a")))
        assertTrue(one.summaryLabel.contains("还有 1 个 App"))

        val many = map(listOf(mapping("com.a"), mapping("com.b")))
        assertTrue(many.summaryLabel.contains("还有 2 个 App"))
        // 说清楚「归类之后会怎样」，用户才知道做这件事的价值
        assertTrue(many.summaryLabel.contains("自动出现在时间轴"))
    }

    // ---------------- 不打扰用时很少的 App（模块 20） ----------------

    @Test
    fun `an app with tiny usage is listed but not nagged about`() {
        // 真机数据：47 个待归类里 24 个用时不到 1 分钟（天气 0 分钟、信息 0.1 分钟…）。
        // 为这些逐个弹归类请求，用户只会把这一页当成骚扰
        val state = map(
            mappings = listOf(mapping("com.weather"), mapping("com.big")),
            hints = mapOf(
                "com.weather" to hint("com.weather", minutesAgo = 60, minutesUsed = 1),
                "com.big" to hint("com.big", minutesAgo = 30, minutesUsed = 90),
            ),
        )

        assertEquals(listOf("com.big"), state.pendingRows.map { it.packageName })
        assertEquals(listOf("com.weather"), state.minorRows.map { it.packageName })
        assertTrue(state.hasMinor)
    }

    @Test
    fun `an app never seen in the window is not nagged about`() {
        // 很久以前用过、最近七天没出现的 App：翻出来问「这是干什么用的」，
        // 用户还得先回忆半天
        val state = map(listOf(mapping("com.old")), hints = emptyMap())

        assertTrue(state.pendingRows.isEmpty())
        assertEquals(1, state.minorRows.size)
    }

    @Test
    fun `the summary says the tiny ones are not waiting for the user`() {
        // 「另有 24 个用时很少的 App」必须写清楚不催 ——
        // 否则用户会以为还得处理 47 个
        val state = map(
            mappings = listOf(mapping("com.big"), mapping("com.tiny")),
            hints = mapOf(
                "com.big" to hint("com.big", minutesAgo = 10, minutesUsed = 60),
                "com.tiny" to hint("com.tiny", minutesAgo = 10, minutesUsed = 1),
            ),
        )

        assertTrue(state.summaryLabel.contains("还有 1 个 App"))
        assertTrue(state.summaryLabel.contains("另有 1 个用时很少的 App"))
        assertTrue(state.summaryLabel.contains("不催你处理"))
    }

    @Test
    fun `no minor line when everything is worth asking`() {
        val state = map(listOf(mapping("com.a"), mapping("com.b")))

        assertTrue(state.minorRows.isEmpty())
        assertTrue(!state.hasMinor)
        assertTrue(!state.summaryLabel.contains("另有"))
    }

    @Test
    fun `the minor group still carries its usage label`() {
        // 不催，但也不能把信息藏起来：用户想管的时候得看得见
        val state = map(
            mappings = listOf(mapping("com.tiny", appLabel = "天气")),
            hints = mapOf("com.tiny" to hint("com.tiny", minutesAgo = 240, minutesUsed = 1)),
        )

        val row = state.minorRows.single()
        assertEquals("天气", row.displayName)
        assertTrue(row.usageLabel!!.contains("共 1m"))
    }

    @Test
    fun `the threshold is ten minutes of use`() {
        val tenMinutes = map(
            mappings = listOf(mapping("com.a")),
            hints = mapOf("com.a" to hint("com.a", minutesAgo = 10, minutesUsed = 10)),
        )
        val nineMinutes = map(
            mappings = listOf(mapping("com.a")),
            hints = mapOf("com.a" to hint("com.a", minutesAgo = 10, minutesUsed = 9)),
        )

        assertEquals(1, tenMinutes.pendingRows.size)
        assertEquals(0, nineMinutes.pendingRows.size)
    }

    @Test
    fun `an empty table explains where the apps will come from`() {
        val state = map(emptyList())

        assertTrue(state.isEmpty)
        assertTrue(state.emptyMessage.contains("使用情况访问"))
    }

    @Test
    fun `a table with only handled apps is not empty`() {
        val state = map(listOf(mapping("com.a", categoryId = 1L)))

        assertFalse(state.isEmpty)
    }

    // ---------------- 编辑表单 ----------------

    @Test
    fun `the form offers active categories and their activities only`() {
        val state = map(
            mappings = listOf(mapping("com.a")),
            form = AppMappingFormState("com.a", "甲", categoryId = 1L),
        )

        val form = state.form!!
        assertEquals(listOf(1L, 2L), form.categories.map { it.id })
        assertFalse("归档分类不该出现在选择器里", form.categories.any { it.id == 3L })
        assertEquals(listOf(10L, 11L), form.activities.map { it.id })
        assertFalse(form.activities.any { it.id == 30L })
    }

    @Test
    fun `without a category there are no activity options`() {
        val state = map(
            mappings = listOf(mapping("com.a")),
            form = AppMappingFormState("com.a", "甲"),
        )

        assertTrue(state.form!!.activities.isEmpty())
    }

    @Test
    fun `the form title names the app being classified`() {
        val state = map(
            mappings = listOf(mapping("com.tencent.mm", appLabel = "微信")),
            form = AppMappingFormState("com.tencent.mm", "微信"),
        )

        assertEquals("给「微信」指定归属", state.form!!.title)
    }

    @Test
    fun `the form says that choosing nothing is allowed`() {
        // 「什么都不选就保存」的含义是「这个 App 不用归类」，之后不再问用户。
        // 不说清楚的话，用户会以为自己没填完
        val state = map(listOf(mapping("com.a")), form = AppMappingFormState("com.a", "甲"))

        assertTrue(state.form!!.hint.contains("不用归类"))
        assertTrue(state.form!!.hint.contains("不会再问"))
    }

    @Test
    fun `the form prefills the current binding so editing does not lose it`() {
        val state = map(
            mappings = listOf(mapping("com.a", categoryId = 2L, activityId = 20L)),
            form = AppMappingFormState("com.a", "甲", categoryId = 2L, activityId = 20L),
        )

        assertEquals(2L, state.form!!.form.categoryId)
        assertEquals(20L, state.form!!.form.activityId)
    }

    @Test
    fun `no form means no dialog`() {
        val state = map(listOf(mapping("com.a")))
        assertNull(state.form)
        assertFalse(state.isFormOpen)
    }

    @Test
    fun `a save failure is explained`() {
        val state = map(
            mappings = listOf(mapping("com.a")),
            form = AppMappingFormState("com.a", "甲"),
            formError = AppMappingFormError.SAVE_FAILED,
        )

        assertTrue(state.form!!.errorMessage!!.contains("已不可用"))
    }

    @Test
    fun `no error means no message`() {
        val state = map(listOf(mapping("com.a")), form = AppMappingFormState("com.a", "甲"))
        assertNull(state.form!!.errorMessage)
    }
}
