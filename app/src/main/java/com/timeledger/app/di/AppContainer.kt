package com.timeledger.app.di

import android.content.Context
import android.content.Intent
import androidx.room.Room
import com.timeledger.app.core.time.DayBoundary
import com.timeledger.app.data.local.TimeLedgerDatabase
import com.timeledger.app.data.repository.ActivityRepositoryImpl
import com.timeledger.app.data.repository.AppMappingRepositoryImpl
import com.timeledger.app.data.repository.CatalogSeederImpl
import com.timeledger.app.data.repository.CategoryRepositoryImpl
import com.timeledger.app.data.repository.GoalRepositoryImpl
import com.timeledger.app.data.repository.PlanBlockRepositoryImpl
import com.timeledger.app.data.repository.TimeRecordRepositoryImpl
import com.timeledger.app.data.repository.UsageEventRepositoryImpl
import com.timeledger.app.data.usage.AndroidAppLabelResolver
import com.timeledger.app.data.usage.AndroidNonUsageAppResolver
import com.timeledger.app.data.usage.AndroidUsageEventReader
import com.timeledger.app.data.usage.UsageAccess
import com.timeledger.app.domain.goal.GoalProgressCalculator
import com.timeledger.app.domain.goal.GoalProgressService
import com.timeledger.app.data.settings.SharedPrefsTermSettings
import com.timeledger.app.domain.plan.PlanBlockExpander
import com.timeledger.app.domain.plan.TermSettings
import com.timeledger.app.domain.repository.ActivityRepository
import com.timeledger.app.domain.repository.AppMappingRepository
import com.timeledger.app.domain.repository.CatalogSeeder
import com.timeledger.app.domain.repository.CategoryRepository
import com.timeledger.app.domain.repository.GoalRepository
import com.timeledger.app.domain.repository.PlanBlockRepository
import com.timeledger.app.domain.repository.TimeRecordRepository
import com.timeledger.app.domain.repository.UsageEventRepository
import com.timeledger.app.domain.review.DayReviewService
import com.timeledger.app.domain.service.EnhancedGoalProgressService
import com.timeledger.app.domain.service.GoalProgressOverrideService
import com.timeledger.app.domain.statistics.StatisticsCalculator
import com.timeledger.app.domain.statistics.StatisticsService
import com.timeledger.app.domain.usage.AppMappingService
import com.timeledger.app.domain.usage.NonUsageAppResolver
import com.timeledger.app.domain.usage.UsageAnalysisService
import com.timeledger.app.domain.usage.UsageSyncService

/**
 * 鎵嬪啓鐨勪緷璧栧鍣?鈥斺€?鍏?App 鍞竴缁勮渚濊禆鐨勫湴鏂广€? *
 * 涓轰粈涔堜笉鐢?Hilt/Koin锛歏1 鐨勪緷璧栧浘鍙湁銆屼竴涓暟鎹簱 + 鍏釜浠撳簱銆嶏紝
 * 鎵嬪啓瀹瑰櫒涓€鐪艰兘鐪嬪叏锛屾瀯寤烘湡涓嶉渶瑕佹敞瑙ｅ鐞嗗櫒锛? * 涔熶笉浼氫负浜嗘敞鍏ヨ€岀粰姣忎釜绫诲姞娉ㄨВ锛堥渶姹傚崄鍏妭绗?14 鏉★細涓嶄负浜嗐€岄珮绾с€嶅鍔犲鏉傚害锛夈€? * 灏嗘潵渚濊禆鍙樺鏃讹紝杩欎釜绫诲氨鏄縼绉诲埌 Hilt 鐨勫敮涓€鏀归€犵偣銆? *
 * 鐢熷懡鍛ㄦ湡锛氳窡鐫€ Application 璧帮紝鍏?App 鍗曚緥銆? * 鐢?`by lazy` 鑰屼笉鏄瀯閫犳椂鍏ㄩ儴鍒涘缓锛屾槸涓轰簡璁╂病鐢ㄥ埌鐨勪粨搴撲笉鍒濆鍖栥€? */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * 鏃堕棿涓婁笅鏂囩殑**鍞竴**鏉ユ簮锛氭椂鍖恒€侀€昏緫鏃ヨ竟鐣屻€佸綋鍓嶆椂闂撮兘浠庤繖閲屽彇銆?     * 娴嬭瘯閲屽彲浠ユ崲鎴愬浐瀹氭椂闂寸殑瀹炰緥锛屽洜姝や笟鍔′唬鐮佷笉鍏佽鐩存帴璋?System.currentTimeMillis()銆?     */
    val dayBoundary: DayBoundary = DayBoundary.system()

    private val database: TimeLedgerDatabase = Room.databaseBuilder(
        appContext,
        TimeLedgerDatabase::class.java,
        TimeLedgerDatabase.DATABASE_NAME,
    )
        // 杩佺Щ鍒楄〃鐢?TimeLedgerDatabase.ALL_MIGRATIONS 缁熶竴绠＄悊锛岃繖閲岃嚜鍔ㄦ帴涓婏紝
        // 閬垮厤浠ュ悗鏈変汉鍔犱簡杩佺Щ鍗村繕浜嗘敞鍐屻€?        // 鍒绘剰涓嶈皟鐢?fallbackToDestructiveMigration()锛氬畞鍙惎鍔ㄦ姤閿欙紝涔熶笉鑳介潤榛樻竻搴撱€?        .addMigrations(*TimeLedgerDatabase.ALL_MIGRATIONS.toTypedArray())
        .build()

    val categoryRepository: CategoryRepository by lazy {
        CategoryRepositoryImpl(database.categoryDao(), dayBoundary)
    }

    val activityRepository: ActivityRepository by lazy {
        ActivityRepositoryImpl(database.activityDao(), database.categoryDao(), dayBoundary)
    }

    val timeRecordRepository: TimeRecordRepository by lazy {
        TimeRecordRepositoryImpl(
            database.timeRecordDao(),
            database.categoryDao(),
            database.activityDao(),
            dayBoundary,
        )
    }

    val goalRepository: GoalRepository by lazy {
        GoalRepositoryImpl(
            database.goalDao(),
            database.categoryDao(),
            database.activityDao(),
            dayBoundary,
        )
    }

    val planBlockRepository: PlanBlockRepository by lazy {
        PlanBlockRepositoryImpl(
            database.planBlockDao(),
            database.categoryDao(),
            database.activityDao(),
            dayBoundary,
        )
    }

    val appMappingRepository: AppMappingRepository by lazy {
        AppMappingRepositoryImpl(
            database.appMappingDao(),
            database.categoryDao(),
            database.activityDao(),
            dayBoundary,
        )
    }

    val goalProgressOverrideService: GoalProgressOverrideService by lazy {
        GoalProgressOverrideService(database.goalProgressOverrideDao())
    }
    
    val usageEventRepository: UsageEventRepository by lazy {
        UsageEventRepositoryImpl(database.usageEventDao(), dayBoundary)
    }
    
    val enhancedGoalProgressService: EnhancedGoalProgressService by lazy {
        EnhancedGoalProgressService(
            goalRepository = goalRepository,
            timeRecordRepository = timeRecordRepository,
            calculator = GoalProgressCalculator(dayBoundary),
            overrideService = goalProgressOverrideService
        )
    }
    


    /**
     * 璁″垝灞曞紑鍣細鎶婃瘡鍛ㄩ噸澶嶇殑璇捐〃钀藉埌鏌愪竴澶╃殑鍏蜂綋鏃舵銆?     *
     * 瀹冩槸鏃犵姸鎬佺函璁＄畻鐨勭被锛堝彧渚濊禆 [dayBoundary]锛夛紝鍥犳娌℃湁瀵瑰簲鐨勪粨搴撴帴鍙ｏ紱
     * 鐣岄潰灞傜敤 `planBlockRepository.observePlans() + expander.expandForDay(...)` 缁勫悎鍗冲彲銆?     */
    val planBlockExpander: PlanBlockExpander by lazy {
        // 传「学期起始日」的读取函数而不是值：用户改了设置之后，
        // 下一次展开就该按新值算，不需要重建容器（模块 28）。
        PlanBlockExpander(dayBoundary) { termSettings.currentTermStart() }
    }

    /**
     * 瀛︽湡璧峰鏃ワ紙妯″潡 28锛夆€斺€?璇捐〃鎸夈€岀鍑犲懆銆嶅垽瀹氭墍闇€鐨勫弬鐓х偣銆?     *
     * 瀛樺湪 SharedPreferences 閲岋紙瑙佸疄鐜扮被鐨勬敞閲婏細涓轰竴涓缃」寮曞叆 DataStore
     * 闇€瑕佽仈缃戜笅杞戒緷璧栵紝涓嶅€煎緱鍐掓柇閾剧殑椋庨櫓锛涙帴鍙ｄ笉鍙橈紝灏嗘潵鍙暣浣撴浛鎹級銆?     * 鐢ㄦ埛杩樻病璁捐繃鏃惰鍒?null 鈥斺€?姝ゆ椂鎵€鏈夎鍒掓寜銆屾瘡鍛ㄩ兘涓娿€嶅鐞嗭紝
     * 涓庡姞鍛ㄦ杩欎竴鍒椾箣鍓嶇殑琛屼负瀹屽叏涓€鑷淬€?     */
    val termSettings: TermSettings by lazy { SharedPrefsTermSettings(appContext) }

    /**
     * 姣忔棩澶嶇洏鐨勮鍙栧叆鍙ｏ紙鐢ㄤ緥灞傦級銆?     * 鎶婃椂闂磋褰曘€佽鍒掋€佺洰鏍囦笁璺暟鎹悎鎴愪竴浠藉彧璇绘ā鍨嬶紝鐣岄潰鍙渶瑕佽闃呭畠涓€涓€?     *
     * 鍙﹀杩樼粰瀹冨師濮嬩簨浠朵笌銆岄潪浣跨敤绫?App銆嶇殑鍒ゅ畾锛堟ā鍧?24锛夛細
     * 绔栬酱涓婇偅浜涚┖鐧藉潡瑕佽兘璇存竻妤氥€岃繖娈垫椂闂存槸鎵嬫満娌″湪鐢紝杩樻槸浣犲湪闆剁鍦板垝鎵嬫満銆嶃€?     */
    val dayReviewService: DayReviewService by lazy {
        DayReviewService(
            timeRecordRepository = timeRecordRepository,
            planBlockRepository = planBlockRepository,
            goalProgressService = goalProgressService,
            planBlockExpander = planBlockExpander,
            dayBoundary = dayBoundary,
            usageEventRepository = usageEventRepository,
            nonUsageAppResolver = nonUsageAppResolver,
        )
    }

    /**
     * 鐩爣杩涘害鐨勮绠楀叆鍙ｏ紙鐢ㄤ緥灞傦級銆?     * 瀹冩病鏈夊搴旂殑銆屽疄鐜扮被銆嶁€斺€斿彧渚濊禆浠撳簱鎺ュ彛涓庣函璁＄畻鍣紝鏈韩宸茬粡鏄彲娴嬭瘯鐨勫叿浣撶被锛?     * 鍐嶆娊涓€灞傛帴鍙ｅ彧浼氬鍔犺烦杞垚鏈€?     */
    val goalProgressService: GoalProgressService by lazy {
        GoalProgressService(
            goalRepository = goalRepository,
            timeRecordRepository = timeRecordRepository,
            calculator = GoalProgressCalculator(dayBoundary),
        )
    }

    val catalogSeeder: CatalogSeeder by lazy {
        CatalogSeederImpl(database.categoryDao(), database.activityDao(), dayBoundary)
    }

    /** 鍛?/ 鏈?/ 骞寸粺璁＄殑璇诲彇鍏ュ彛锛堥渶姹傚崄涓夎妭锛夈€?*/
    val statisticsService: StatisticsService by lazy {
        StatisticsService(
            timeRecordRepository = timeRecordRepository,
            calculator = StatisticsCalculator(dayBoundary),
        )
    }

    /**
     * 绯荤粺鎰熺煡鐨勭紪鎺掑叆鍙ｏ紙闇€姹傚叚銆佷節鑺傦級锛氬師濮嬩簨浠?鈫?鍓嶅彴浼氳瘽 鈫?寰呯‘璁?宸茬‘璁よ褰曘€?     *
     * 瀹冩湰韬笉纰?Android API锛堝師濮嬩簨浠剁敱骞冲彴灞傞噰闆嗚惤搴擄級锛屽洜姝よ繖涓€娈甸€昏緫
     * 鍙互瀹屾暣璺戝湪 JVM 娴嬭瘯閲岋紱骞冲彴灞傚彧璐熻矗銆屾妸浜嬩欢鍙栧洖鏉ャ€嶈繖涓€浠朵簨銆?     */
    /**
     * 銆岃繖涓嶆槸鐢ㄦ埛鍦ㄤ娇鐢ㄣ€嶇殑鍒ゅ畾锛氭闈€佺郴缁熺晫闈€佽緭鍏ユ硶銆?     *
     * 鍗曠嫭鎻愬嚭鏉ュ叡浜粰閲囬泦鍒嗘瀽鍜屾瘡鏃ュ鐩橈紙妯″潡 24 鐨勭┖缂鸿В閲婁篃瑕佺敤鍚屼竴濂楀垽瀹氾級锛?     * 鑰屼笉鏄悇寤轰竴涓?鈥斺€?涓ゅ鍒ゅ畾涓嶄竴鑷翠細鍑虹幇銆岃褰曡娌″湪鐢ㄣ€佽В閲婅鏈?12 娆℃墦寮€銆嶃€?     */
    val nonUsageAppResolver: NonUsageAppResolver by lazy {
        AndroidNonUsageAppResolver(appContext)
    }

    val usageAnalysisService: UsageAnalysisService by lazy {
        UsageAnalysisService(
            usageEventRepository = usageEventRepository,
            appMappingRepository = appMappingRepository,
            timeRecordRepository = timeRecordRepository,
            dayBoundary = dayBoundary,
            // 妗岄潰 / 绯荤粺鐣岄潰 / 杈撳叆娉曚笉鏄€岀敤鎴峰湪浣跨敤銆嶃€傜敱骞冲彴灞傜敤鏍囧噯 API 瑙ｆ瀽
            // 锛圕ATEGORY_HOME / com.android.systemui / InputMethodManager锛夛紝
            // 涓嶇‖缂栫爜鍘傚晢鍖呭悕 鈥斺€?鐪熸満鏁版嵁鏄剧ず妗岄潰涓€澶╄兘鍑虹幇 559 娆°€
nonUsageAppResolver = nonUsageAppResolver,
            // 鐔勫睆璁板綍瑕佸綊鍒板唴缃垎绫汇€岀唲灞忋€嶏紙瑙?DefaultCatalog锛
categoryRepository = categoryRepository,
        )
    }

    /**
     * 閲囬泦缂栨帓锛氳绯荤粺浣跨敤璁板綍 鈫?钀藉簱 鈫?鍒嗘瀽銆?     *
     * 杩欓噷鏄?*骞冲彴灞備笌棰嗗煙灞傜殑鎺ョ紳**锛歚AndroidUsageEventReader` 鏄敮涓€纰?     * `UsageStatsManager` 鐨勫疄鐜帮紝涔熸槸鍞竴闇€瑕佺湡鏈洪獙璇佺殑閮ㄥ垎銆?     */
    val usageSyncService: UsageSyncService by lazy {
        val labelResolver = AndroidAppLabelResolver(appContext)
        UsageSyncService(
            reader = AndroidUsageEventReader(appContext),
            usageEventRepository = usageEventRepository,
            appMappingRepository = appMappingRepository,
            usageAnalysisService = usageAnalysisService,
            dayBoundary = dayBoundary,
            appLabelResolver = { packageName -> labelResolver.labelOf(packageName) },
        )
    }

    /** 璺冲埌绯荤粺璁剧疆鐨勩€屼娇鐢ㄦ儏鍐佃闂€嶉〉闈紙鐗规畩鏉冮檺娌℃湁瀵硅瘽妗嗗彲寮癸級銆?*/
    fun usageAccessSettingsIntent(): Intent = UsageAccess.settingsIntent()

    /**
     * App 鈫?琛屼负鏄犲皠鐨勮鍐欏叆鍙ｏ紙闇€姹備節鑺傦級銆?     * 鍒ゆ柇閫昏緫锛堟寜鏈€杩戜娇鐢ㄦ帓搴忋€佸彧绠楀墠鍙颁簨浠讹級閮藉湪瀹冮噷闈紝鍙敤 JVM 娴嬭瘯瑕嗙洊銆?     */
    val appMappingService: AppMappingService by lazy {
        AppMappingService(
            appMappingRepository = appMappingRepository,
            usageEventRepository = usageEventRepository,
            dayBoundary = dayBoundary,
        )
    }
}
