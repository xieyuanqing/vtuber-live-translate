# 免费服务接入：官方依据与真实验证

核验时间：2026-09-12（UTC+08）。以下区分官方文档、真实 API 请求、模拟协议测试；三者不能互相替代。

## Gemini

- 密钥申请：https://aistudio.google.com/apikey
- 官方操作说明：https://ai.google.dev/gemini-api/docs/api-key
- 官方价格：https://ai.google.dev/gemini-api/docs/pricing
- 价格页的 **Gemini 3.5 Live Translate** 条目列出精确模型 `gemini-3.5-live-translate-preview`，标准请求免费档输入与输出均为 `Free of charge`。免费档不是无限使用，区域、模型可用性、配额及数据处理以 Google 当时政策为准。
- 申请教程应以“登录 Google → 创建/选择或导入项目 → 创建密钥 → 复制回 App 粘贴”为短流程，始终保留官方说明入口。官方说明目前还包含标准 Key 向授权 Key 迁移的要求，不在 App 中绑定易过时的网页按钮位置。
- 本轮环境没有用户提供的 Gemini 测试 Key，**尚未完成真实 Google Live 鉴权与音频翻译验证**。不使用其他服务的凭据或 CLI OAuth 代替 App API Key。
- App 的连接自检必须请求当前 Live 模型并等待 `setupComplete`；WebSocket 打开、列出模型、普通 `generateContent` 成功均不算 Live 模型连接成功。仅完成 setup 时必须明确未测试音频翻译，不承诺端到端时延或长期连接质量。

## OpenCode Zen

官方说明：https://opencode.ai/docs/zen/

官方客户端实现：https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/provider/provider.ts

文档核实：

- `big-pickle` 当前输入/输出免费，且兼容 `https://opencode.ai/zen/v1/chat/completions`。
- `https://opencode.ai/zen/v1/models` 实际返回模型列表，但各条只有 `id/object/created/owned_by`，**没有定价或接口兼容性字段**，不能把整个列表标成免费，也不能只凭名字包含 `free`。
- 官方客户端代码在没有账户 Key 时过滤输入价格非零的模型，使用 `public` 作为 Key；这只能证明客户端行为，不能证明第三方 App 可直接使用。
- 免费模型可能限时提供。Big Pickle 免费期间提交数据可能用于模型改进；不适合发送敏感资料。

### 真实请求结果

对官方端点使用标准 curl HTTP 客户端，发送一个简短、明确标记的公开测试材料。请求使用 OpenAI 兼容消息、`response_format: {"type":"json_object"}`，要求返回 App 所需字符串字段 `sessionContext` 与 `note`；没有个人 API Key、用户视频链接、用户背景或音频。

```http
POST https://opencode.ai/zen/v1/chat/completions
Authorization: Bearer public
Content-Type: application/json
```

模型：`big-pickle`。

真实响应：**HTTP 400**。

```json
{
  "type": "error",
  "error": {
    "type": "MissingSessionID",
    "message": "Error from provider (Console): OpenCode's free tier can only be used in OpenCode"
  }
}
```

此前 Python urllib 默认客户端请求被边缘层以 HTTP 403 / `error code: 1010` 拒绝；换标准 curl 后取得上述应用层限制，不把 WAF 错误与模型推理失败混为一谈。

**结论：本轮未验证 OpenCode Zen `public` 推理可用于本 App，不能默认标为可用或承诺一键免费。** 不通过伪造 OpenCode 客户端会话标识绕过服务限制。保留明确限制提示及重新测试/切换服务，不自动切换付费模型。实际支持的服务预设需与这个事实一致。

## 应用语言官方依据

https://developer.android.com/guide/topics/resources/app-languages

使用 AndroidX AppCompat 标准应用语言 API，兼容旧 Android 并接入 Android 13+ 系统应用语言。界面语言只影响 UI，不替换既有翻译方向或运行会话快照。截图、重建状态保留测试、JVM 回归、Lint 和 APK 构建结果应单独记入开发日志，以实际执行为准。
