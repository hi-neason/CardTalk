# 支持、隐私申请与运营处理

这是用户主动提交的工单工作流，不自动收集沟通历史、卡片、报告、使用人照片、日志或联系方式，也不发送邮件及第三方消息。联系信息完全由用户自填。隐私删除申请不执行删除；处理状态只是人工工作流状态，不能证明家庭、云同步、备份或第三方数据已删除。

## 启用和权限

默认 `CARDTALK_SUPPORT_ENABLED=false`，接口返回 HTTP 503 `SUPPORT_DISABLED`，不把内容伪装成已提交。启用必须配置 PostgreSQL 家庭存储与 `CARDTALK_FAMILY_JDBC_*`，使用 V007 持久化；启动失败不会回退本机文件。匿名身份通过 TrialService 验证，生产建议同时启用其 PostgreSQL 模式。

- `CARDTALK_SUPPORT_DAILY_LIMIT=5`：每可信身份、上海日期每日最多五张新单。请求重试不重复计数，撤回不返还额度。
- `CARDTALK_SUPPORT_RETENTION_DAYS=90`：已关闭或撤回单据的保留天数（1–3650）。结束时写入明确 retainUntil；修改配置只影响后续关闭的单据。处理中单据不自动到期。
- `CARDTALK_SUPPORT_OPERATOR_ACCOUNT_IDS`：逗号分隔的明确运营账户 ID。家庭 owner/admin 身份不会自动获得运营权限。运营身份需要有效家庭账户 session，但不要求其加入用户家庭。

支持接口明确使用 `X-CardTalk-Support-Scope: trial|account`：

- trial：忽略家庭 cookie，使用 `X-CardTalk-Trial-Token`，缺省才读取 Authorization trial Bearer。服务端身份为已验证安装 clientId 的摘要，token 轮换后仍可访问同一安装单据。
- account：家庭 cookie `cardtalk_family` 优先，否则使用 Authorization family Bearer，归属账户而非当前选中的家庭。
- 未提供 scope 时，有家庭 cookie 则 account，否则 trial。前端应显式设置并展示归属，避免混淆。
- `/operations/*` 固定使用账户身份，不接受匿名体验身份。所有 cookie 写请求必须通过配置的 Origin 检查；32 KiB 请求体限制适用。

匿名安装清理后无法凭联系信息认领原单，其他安装和其他账户也不能查询或撤回别人的单据。姓名或联系方式不是权限凭证。

## 用户接口

`GET /api/v1/support/status` 验证所选身份，返回：

```json
{"enabled":true,"dailyLimit":5,"retentionDays":90,"privacyDeletionAutomatic":false,"actorScope":"account","canOperate":false}
```

`POST /api/v1/support/tickets`：

```json
{"requestId":"support-unique-id","kind":"support","message":"用户主动填写的问题","contact":"可选自填信息","familyId":null}
```

kind 为 `support` 或 `privacy_deletion`。message 必填、不超过 4000 字符；contact 可空、不超过 200；requestId 为 1–100 位字母数字下划线或连字符。同身份同编号绑定完整内容，不同内容返回 `SUPPORT_REQUEST_CONFLICT`。不要上传自动背景快照。familyId 可选，仅账户身份且当前为该家庭成员时接受；它仅关联申请，不授予删除其他成员资料的权力。隐私申请具体范围由用户在 message 写明，运营须另行核实身份、数据范围与删除授权。

`GET /api/v1/support/tickets?cursor=0&limit=20` 只返回本人单据。分页返回 `{tickets,nextCursor,hasMore}`，limit 为 1–50，按新到旧排列，后续请求使用 nextCursor。

Ticket 字段为 `id,kind,message,contact,familyId,status,reply,deletionEvidence,createdAt,updatedAt,retainUntil`。状态为 `submitted,in_progress,resolved,rejected,withdrawn`。日期均为 UTC ISO 字符串，未进入保留期时 retainUntil 为 null。

`POST /api/v1/support/tickets/{id}/withdraw` 不需请求体，仅 submitted 可撤回，重复撤回幂等；已经开始处理则返回 `SUPPORT_ALREADY_PROCESSING`。撤回保留审计和保留期内文本，不表示立刻删除。

## 运营接口与审计

- `GET /api/v1/operations/tickets?cursor=0&limit=20`：运营列表，包含用户自填文字和联系信息。
- `POST /api/v1/operations/tickets/{id}/updates`：`{requestId,status,reply,deletionEvidence?}`。status 可为 in_progress/resolved/rejected；reply 必填、最多 4000 字符，deletionEvidence 最多 2000。同运营账户、同单据、同编号绑定完整内容，重试不重复审计。
- `GET /api/v1/operations/tickets/{id}/audit`：返回按操作顺序排列的审计数组，字段 `id,actor,action,reply,deletionEvidence,createdAt`。actor 是账户/安装身份摘要。提交、撤回、运营变更在同一事务内写入追加审计。
- 已关闭单据不可继续变更。隐私删除申请 resolved 必须填写独立 deletionEvidence，单写“已处理”回复无法关闭；这是运营记录的证据说明，不是系统验证外部删除的证明。rejected 表示拒绝申请，应解释原因，不需要伪造删除证据。

`POST /api/v1/operations/retention/purge` 是明确运营维护入口，仅清理 retainUntil 已到期且 resolved/rejected/withdrawn 的支持单据及其审计，返回 `{removed:n}`。未处理单据、家庭/沟通/支付数据不会被该操作删除。系统不自动调用此入口；运营需按计划执行，并负责相关备份的独立保留策略。每次清理在 `ct_support_maintenance_audit` 留存操作者摘要、数量、时间，清理记录不含单据正文，当前不自动清理。清除单据也结束对应编号的幂等保留期。

## 验证

PostgresSupportTest 使用真实 PostgreSQL 隔离 schema，覆盖身份隔离与token轮换、显式 trial/account scope、可信家庭关联、cookie Origin、大小与格式限制、跨实例日限额、分页、nonce幂等与冲突、运营权限、隐私证据门槛、撤回边界、追加审计、到期维护和默认禁用。使用本地测试数据，无外部邮件、通知或真实删除执行。

支持、内容、指标与账单运营权限分别配置，互不隐式授权。
