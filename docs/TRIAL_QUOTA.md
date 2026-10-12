# 扩写体验额度持久化

`CARDTALK_TRIAL_REPOSITORY_MODE=json` 是默认单进程开发存储，继续使用 `CARDTALK_DATA_FILE`。生产多实例设置为 `postgres`，使用 `CARDTALK_FAMILY_JDBC_URL/USER/PASSWORD`。PostgreSQL 模式不读取或写入 JSON 文件；连接、迁移失败会阻止启动，不自动回退。现有 JSON 体验元数据不自动导入数据库，切换存储属于新环境初始化。

V006 建立三个业务表：`ct_trial_sessions` 保存安装 clientId 和 token SHA-256；`ct_trial_daily` 保存 clientId、上海日期、成功次数；`ct_trial_requests` 保存请求编号摘要、请求内容摘要、预留日期、状态、尝试 ID 和租约到期时间。没有卡片、资料、场景、候选正文或明文 token。FamilyExpansionController 的身份/权益检查保持独立，授权后的扩写仍使用同一匿名安装额度，不自动变成无限量服务。

## 并发与幂等

- 每次创建 session 轮换 token；旧 token 立即失效，但同 clientId 的成功次数和请求去重不清零。
- 请求编号按 clientId 绑定内容摘要；家庭调用另含服务端授权的家庭/账户 scope。同编号不同内容返回 `REQUEST_ID_CONFLICT`。
- PostgreSQL 事务内按 clientId 获取 advisory lock。当前日成功次数加有效预留不得超过日额度；多个服务实例共享这一限额。生成中的同编号请求返回 `REQUEST_IN_PROGRESS`。
- 模型失败释放预留，保留编号到摘要的绑定，允许相同内容重试。模型成功后完成标记和日计数在同一事务提交，重复完成不重复记账。
- 成功编号跨重启、跨午夜保留；本进程五分钟候选缓存仍在时返回相同候选，否则返回 `REQUEST_ALREADY_COMPLETED`，客户端应使用已保存结果或新编号。候选缓存不进入数据库。
- 租约五分钟。崩溃预留过期后可重试，新尝试 ID 会阻止旧尝试完成或释放新预留。上游成功但记账前进程崩溃时，重试可能再次调用模型；外部模型调用不承诺 exactly-once。
- 午夜在途请求记入预留日，完成不会覆盖新一天已用次数。响应 remaining 根据当前上海日期计算。token 轮换期间已获准的在途请求仍记账，但旧 token 不能再发起请求。

同一安装自行改变 clientId 仍能创建新体验额度；匿名额度不等于账户付费计费或完整反滥用策略。历史去重元数据当前不自动清理，删除它会缩短去重保留期，应按产品保留策略维护。

## 验证

`PostgresTrialTest` 使用真实 PostgreSQL 随机隔离 schema，覆盖多实例并发上限、重启和token轮换、跨午夜去重和在途计数、失败释放、内容冲突、租约恢复与旧尝试隔离、最大 clientId、服务层失败/缓存/重启/家庭scope行为及生产模式不读取JSON。`ApiTest` 继续验证原有 JSON 行为。
