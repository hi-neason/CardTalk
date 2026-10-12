# AI 统计说明（本机分析体验）

AI 统计说明是对本机已有统计数字的可选解释，显示时应与原始统计分开。它不是沟通原始记录、能力评分、诊断或疗效结论，不能替代使用者确认或家人反馈。

## 接口及权限

`POST /api/v1/reports/narrative` 使用匿名体验的 `Authorization: Bearer <trial-token>`。后端先验证体验身份，再检查该 session 的 `capabilities.analytics` 为 true。这是当前本机分析的开发体验入口，不是正式云家庭报告权限；家庭账户 cookie 不授权此接口。

请求仅包含请求编号和整数汇总：

```json
{
  "requestId": "report-unique-id",
  "facts": {
    "confirmedCount": 4,
    "roomCount": 2,
    "activeDays": 2,
    "understood": 1,
    "clarify": 1,
    "notUnderstood": 1,
    "unrecorded": 1,
    "raw": 1,
    "ai": 1,
    "edited": 1,
    "saved": 1,
    "processCollected": 3,
    "processMissing": 1
  }
}
```

`requestId` 为 1–100 位字母、数字、下划线或连字符。全部十三个统计字段必填，为 0–500000 的 JSON 整数，不接受数字字符串或小数。

- 四类反馈合计、四类表达来源合计、两类过程采集合计，分别等于 `confirmedCount`。
- `roomCount` 和 `activeDays` 不超过已确认表达数；非零表达时二者不能为零。
- 没有反馈与没有过程记录属于未知，不能视作失败或没有操作。

后端只从严格的整数 DTO 重新序列化这十三个字段，发送给 DeepSeek。额外输入字段不会传给模型；客户端应只构造此白名单，不提交姓名、日期、照片、卡片、表达正文、家人观察或其他身份信息。请求总容量由接口请求保护限制。

成功返回：

```json
{
  "requestId": "report-unique-id",
  "text": "AI 统计说明：……",
  "promptVersion": "aac-report-counts-2026-10-12-v1",
  "provider": "deepseek",
  "remaining": 4
}
```

文字上限为 1500 字符，HTTP 响应不缓存。界面应明确标为 AI 统计说明，保留原有统计数字作为事实来源。系统提示要求模型不得新增原因、意图、趋势或医疗事实，明确未知及小样本限制；提示约束不能证明模型永不出错，前端不应把说明变成事实、能力评估或诊疗建议。

## 服务配置和失败行为

复用已有的 `CARDTALK_LLM_BASE_URL`、`CARDTALK_LLM_MODEL` 和 `CARDTALK_LLM_API_KEY`。没有有效配置时返回 `LLM_NOT_CONFIGURED`，不生成假说明。额外配置：

```dotenv
CARDTALK_REPORT_DAILY_LIMIT=5
CARDTALK_REPORT_TIMEOUT_SECONDS=30
CARDTALK_REPORT_MAX_CONCURRENT=2
CARDTALK_REPORT_QUOTA_MODE=memory
```

使用原生 JDK HttpClient 与 Jackson。请求包括响应体接收都有超时上限；上游响应最多 32 KiB；通过信号量限制并发。上游拒绝、超时、破损 JSON、文字超长及并发繁忙均返回真实错误，不替换为模板成功结果，不扣成功额度。不会把 API 密钥发给客户端。

## 独立报告额度

每天默认可成功生成五次统计说明，按 `Asia/Shanghai` 日期和体验 token 哈希隔离，不占用卡片扩写次数。请求编号也保存为哈希，并绑定统计摘要；同 token 同编号配合不同统计返回 409。

同请求成功重试时，临时结果缓存仍在则返回同一响应，不重复调用模型或扣次。缓存最多保留 256 份结果；淘汰正文后仍保留当日完成摘要，此时重试返回 `REPORT_ALREADY_COMPLETED`，需要使用新编号。失败重试不扣次，但仍沿用原统计摘要；同一 token 每日最多记录 100 个不同编号，避免无限失败请求占用内存。

`CARDTALK_REPORT_QUOTA_MODE=memory` 仅用于开发，重启会重置额度。生产设置为 `postgres`，复用 `CARDTALK_FAMILY_JDBC_*`，通过 V005 独立表持久保存身份、请求编号、统计的 SHA-256 摘要及预留状态；连接或迁移失败不会回退内存。报告正文和原始统计均不写入额度表。体验 token 更换仍可产生新额度，因此匿名体验不能替代账户反滥用控制。

PG 多实例按身份和上海日期在事务中串行预留，成功与尚未过期的预留共同占用每日额度。失败释放预留，但请求编号继续绑定原摘要；重复完成不会重复扣次，旧尝试不能释放或完成新尝试。生成中的同编号请求返回 `REPORT_IN_PROGRESS`。进程崩溃留下的预留五分钟后可重试；这是恢复租约，不保证外部模型调用 exactly-once（模型成功但进程在记账前崩溃，重试可能再次调用）。已成功记账的编号跨重启返回 `REPORT_ALREADY_COMPLETED`，不会重新调用或扣次，因为正文缓存不持久化。数据库历史元数据目前不自动清理，需要按保留策略维护。

## 正式家庭入口

`POST /api/v1/families/{familyId}/reports/narrative` 使用家庭 cookie 或家庭 Bearer token，接受相同十三项计数 DTO。开启 `CARDTALK_BILLING_ENFORCED=true` 时检查当前家庭成员关系及 analytics 有效权益；helper 只读，不能生成新报告。开发关闭此开关时仍要求 owner/admin/member。该入口不依赖匿名体验的全局 analytics flag；额度按家庭 ID 与账户 ID 的组合摘要隔离，默认每账户每家庭每日五次。重新登录不会重置这一身份。家庭 cookie 写请求经过 Origin 校验。

客户端必须明确选择家庭后调用；接口不自动读取、上传或同步本机历史、照片和观察。权益和成员关系在调用模型前检查，调用期间发生的撤权不取消已在执行的上游请求。

## 测试

`ReportTest` 使用 loopback HTTP 服务返回固定模型响应，不调用真实 DeepSeek。覆盖只传统计白名单、权限和缺失配置、计数分组一致性、严格整数、幂等与冲突、每日五次限制、失败不扣、跨 token 隔离、上游错误/超时/超容量/破损响应、并发限制和结果缓存淘汰。现有卡片扩写 quota 在报告生成后保持不变。

`PostgresReportQuotaTest` 使用真实 PostgreSQL 隔离 schema，验证跨实例并发预留上限、完成幂等、失败释放、摘要冲突、租约恢复、上海午夜换日、存储列白名单，以及正式家庭 cookie/权益授权、helper/外部账户拒绝、撤权生效、重启后成功请求不重复调用模型。
