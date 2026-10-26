// 仓库镜像顺序说明：
// 阿里云镜像放在最前面，因为国内直连 dl.google.com / repo1.maven.org 基本不可达。
// google() / mavenCentral() 作为兜底保留，方便这个项目在其它网络环境下也能构建；
// 若在受限网络下构建卡住，可以把它们删掉，只留阿里云三个镜像。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 禁止子模块自己声明仓库，保证依赖来源只有一个地方可查。
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "TimeLedger"

// V1 只有 app 一个模块。数据层目前放在 app 内部按包分层，
// 不提前拆成 :core:data / :feature:xxx —— 那是「以后可能有用」的范围扩张。
// 将来真要拆模块时，包边界已经是清晰的（core / domain / data），迁移成本很低。
include(":app")
