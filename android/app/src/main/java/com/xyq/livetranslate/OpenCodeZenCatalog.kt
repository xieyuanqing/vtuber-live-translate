package com.xyq.livetranslate

/**
 * OpenCode Zen 免费模型目录与白名单校验。
 *
 * 官方依据与实测结论（详见 docs/09-free-services-verification.md 及 /tmp/vtuber-stage1-review-feedback.md）：
 * 1. 官方文档（https://opencode.ai/docs/zen/）：
 *    - big-pickle：输入与输出均明确标注为 Free。
 *    - 隐私条款：Big Pickle 免费期间数据可能用于模型改进；请勿发送敏感资料。
 *    - GET /zen/v1/models：仅返回 id、object、created、owned_by，无价格字段。
 *    - 规则：严禁将全列表当成免费，严禁使用 contains("free") 猜测。
 * 2. 真实 API 实测证据（2026-09-12 curl 测试）：
 *    - POST /zen/v1/chat/completions 使用 public 密钥返回 HTTP 400：
 *      {"type":"error","error":{"type":"MissingSessionID","message":"Error from provider (Console): OpenCode's free tier can only be used in OpenCode"}}
 *    - 结论：免费接口目前限制仅 OpenCode 官方客户端使用，第三方 App 未验证可用。
 *    - 本 App 绝不伪造 x-opencode-session 绕过限制，不将 big-pickle 标为已验证可用。
 *    - 保留候选配置与测试入口供用户复测，杜绝自动切换付费服务。
 * 3. 内部使用固定 public 密钥，UI 不要求用户填写 Key，绝不向 Zen 发送 Gemini Key。
 */
object OpenCodeZenCatalog {
    const val BASE_URL = "https://opencode.ai/zen/v1"
    const val PUBLIC_KEY = "public"
    const val CANDIDATE_MODEL = "big-pickle"
    const val DOCS_URL = "https://opencode.ai/docs/zen/"
    const val PRIVACY_NOTICE = "免费模型可能使用提交内容改进模型，请勿发送敏感资料"

    /**
     * 官方文档明确确认为免费的模型白名单。
     * 来源：https://opencode.ai/docs/zen/
     */
    val OFFICIAL_FREE_ALLOWLIST: List<String> = listOf(
        "big-pickle",
    )

    /**
     * 将远端返回的模型 ID 与官方免费白名单取交集。
     * 若远端未返回任何白名单模型（如接口变更或模型下线），必须返回空列表，
     * 绝不能把不存在的模型重新塞回列表谎报可用。
     */
    fun filterAvailableFreeModels(remoteModels: List<String>): List<String> {
        val remoteSet = remoteModels.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return OFFICIAL_FREE_ALLOWLIST.filter { it in remoteSet }
    }

    /**
     * 校验模型 ID 是否属于官方免费白名单。
     * 严禁任意指定未确认免费的付费模型。
     */
    fun isAllowedFreeModel(model: String): Boolean =
        OFFICIAL_FREE_ALLOWLIST.contains(model.trim())

    /**
     * 对 OpenCode Zen 的特定限制错误进行准确本地化说明。
     */
    fun localizeZenError(errorMessage: String?): String {
        val msg = errorMessage.orEmpty()
        if (msg.contains("OpenCode's free tier can only be used in OpenCode", ignoreCase = true) ||
            msg.contains("MissingSessionID", ignoreCase = true)
        ) {
            return "当前免费接口限制仅 OpenCode 客户端使用，本 App 未验证可用；可重新测试或切换服务"
        }
        return msg
    }
}
