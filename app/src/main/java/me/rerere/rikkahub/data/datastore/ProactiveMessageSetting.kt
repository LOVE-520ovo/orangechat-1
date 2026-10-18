/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

@Serializable
data class ProactiveMessageSetting(
    val enabled: Boolean = false,
    val minIntervalMinutes: Int = 30,
    val maxIntervalMinutes: Int = 90,
    val assistantId: String = "",
    // 是否允许 AI 根据上下文判断后强制跳转屏幕到聊天界面
    val allowForceJump: Boolean = false,
    val jumpIdleThresholdMinutes: Int = 120, // 用户多久没回复(分钟)才允许跳转屏幕，默认2小时
    // 激进模式：每次手机切换应用/开屏锁屏/回桌面都触发AI思考
    val aggressiveModeEnabled: Boolean = false,
    // 激进模式下两次AI思考之间的最小间隔（秒），防抖+限流
    val aggressiveMinIntervalSeconds: Int = 60,
    // 激进模式下，检测到设备事件（切应用/开关屏/回桌面）后，
    // 等待多少秒的防抖时间才真正触发 AI 思考。原来硬编码 30 秒，现在可调节。
    val aggressiveDebounceSeconds: Int = 30,
    // 悬浮球：主动消息到达时以 Telegram 风格悬浮球提醒，点击直接进入聊天页
    val floatingBubbleEnabled: Boolean = false,

    // ==================== Kli Wake Activation ====================
    // "kli" = 节律引擎；"random" = 旧的 30~90 分钟随机
    val wakeMode: String = "kli",
    // 活跃度总旋钮（次/小时）
    val lambdaBase: Float = 0.70f,
    // 允许醒来的星期：1=周一 … 7=周日
    val wakeDays: String = "5,6,7",
    // 半天日子的起始分钟（周五 17:30 = 1050），其他日子从 00:00 开始
    val wakeHalfDayStartMinute: Int = 1050,
    // 每日静默段：02:30 = 150，05:30 = 330
    val quietStartMinute: Int = 150,
    val quietEndMinute: Int = 330,
    val quietLambda: Float = 0.05f,
    // 醒来记录写到谁名下
    val wakeLogOwner: String = "gabriel",
    val supabaseUrl: String = "",
    val supabaseKey: String = "",
    // 刚说完话后强制沉默的分钟数
    val wakeMinSpeakGapMinutes: Int = 20,
    // 唤醒时是否携带 MCP 工具
    val wakeMcpEnabled: Boolean = true,
)