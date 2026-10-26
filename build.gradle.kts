// 根构建脚本：只声明插件版本，不在这里配置任何模块。
// 具体配置放在各模块的 build.gradle.kts 里，避免根脚本变成一个什么都往里塞的大杂烩。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
