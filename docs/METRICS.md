# 运行指标

`CARDTALK_METRICS_ENABLED=false` 为默认值，采集和入口均关闭，`GET /api/v1/operations/metrics` 返回 503 `METRICS_DISABLED`。启用后仅 `CARDTALK_METRICS_OPERATOR_ACCOUNT_IDS` 明确配置的账户可查看；使用家庭 cookie，或无 cookie 时的家庭 Bearer。普通家庭 owner/admin、匿名体验 token 和未认证请求没有查看权限。成功和错误响应均由 operations 请求保护标记 no-store。

指标只存在当前服务实例内存中，重启清零；startedAt 与 uptimeSeconds 用于识别窗口。多实例各自采集，负载均衡后的连续请求可能来自不同实例。此接口不是长期 BI、用户行为追踪或付费账单。默认不导出、不发送外部监控服务。

## API 契约

响应顶层字段：

- `storage: "process_memory"`、`startedAt`（UTC ISO）、`uptimeSeconds`。
- `api`：固定类别数组。
- `providers`：固定 expansion_llm/report_llm/speech_tts 数组。
- `cost: {status:"unknown",reason:"provider_billing_not_measured",amount:null,currency:null}`。

每个计数项含 `category,started,completed,inFlight,errors,statusClasses,durationBuckets,usage`。

API 类别固定为 health、trial_session、expansion、report_narrative、speech、speech_cancel、account、family、sync、asset、billing、support、operations、content、other_api。原始 URL、查询参数、家庭/用户/请求 ID 不会成为标签。家庭扩写与匿名扩写归入同一 expansion 类。

API `statusClasses` 是 1xx/2xx/3xx/4xx/5xx 数量；errors 是响应状态不小于 400 的数量。未处理异常按 500 记录。started 包含在途请求，completed 与耗时桶仅计已结束请求。当前同步 Servlet API 在请求结束时记账。指标查询本身也计入 operations，返回快照可能包含正在执行的这次查询。

非累积 `durationBuckets` 字段固定为：

| 字段 | 区间 |
|---|---|
| lt_100ms | 小于 100ms |
| 100ms_to_500ms | 100ms 至不足 500ms |
| 500ms_to_2s | 500ms 至不足 2s |
| 2s_to_10s | 2s 至不足 10s |
| 10s_to_30s | 10s 至不足 30s |
| gte_30s | 30s 及以上 |

## 实际上游调用与用量

Gateway 在发起模型 HTTP 请求或语音 WebSocket 连接时开始计数，候选/音频有效返回后计成功。缓存命中、未授权、未配置、请求校验拒绝和本地并发拒绝不会虚构上游调用。provider errors 包含网络失败、上游拒绝、格式错误、超时和取消；成功的 HTTP 状态若没有有效候选也属于失败。语音连接尝试失败仍是一次真实尝试。provider 的 statusClasses 为空，因为这些指标表达业务调用完成情况，不能与 HTTP 服务入口状态混同。

API usage 为 null。provider usage 为：

```json
{"status":"partial","source":"provider_response_usage","reportedCalls":1,"promptTokens":10,"completionTokens":5,"totalTokens":15}
```

仅当上游 usage 的 prompt_tokens/completion_tokens/total_tokens 均为非负、合理范围整数且总和一致时累计。source 明确为上游自报，reportedCalls 表明覆盖多少次调用；未覆盖所有已完成调用时 status 为 partial。没有可用报告时 status 为 unknown、source 为 unavailable、token 数为 null，不以 0 冒充已测量用量。讯飞当前协议未采集可核验账单用量，保持 unknown。

未接入账单或定价配置，费用统一 unknown，既不根据 token 自行推算，也不把没有数据显示为免费或零花费。计数不保存 prompt、回复、原始 usage JSON、联系方式、密钥、token、模型名称或提供商 URL，只读取白名单数字并立即丢弃原始对象引用。

## 验证

MetricsHttpTest 启动真实随机端口 Spring HTTP 服务，以真实本机家庭账户 session 验证运营、普通 owner、匿名及缺失身份；loopback HTTP/失败 WebSocket 上游验证实际调用次数、缓存不增加调用、错误归类、在途数量、usage来源与unknown费用。快照断言不含输入正文、候选、密钥、token、URL及路径 ID；另验证默认关闭、no-store、计数重置和非法usage拒绝。既有模型、语音、报告测试用于确认插入指标不改变业务结果。

支持、内容、指标与账单运营权限分别配置，互不隐式授权。
