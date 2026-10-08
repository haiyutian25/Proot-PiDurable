package com.lhzkml.spike.feature.runtime.api

/**
 * Runtime 功能的导航入口。
 *
 * api 模块刻意保持极薄：只暴露「其它模块需要知道的标识」，不含任何 UI 与实现，
 * 因此它不依赖 Compose —— 应用壳只要拿到这个 key 就能装配页面，
 * 不需要把整个 feature:impl（及其全部依赖）拽进自己的编译单元。
 */
object RuntimeNavKey {
    const val ROUTE = "runtime"
    const val TITLE = "Runtime"
}
