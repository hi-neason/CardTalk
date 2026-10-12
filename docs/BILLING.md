# 家庭权益与订单持久基础

权益按家庭独立记录 `profiles`（使用人管理）、`scenarios`（场景管理）、`analytics`（分析）三项功能。已有历史和已知资料的读取始终不因购买或到期被关闭；高阶写入是否允许同时取决于当前家庭角色及有效权益。helper 始终不能购买或写入，family owner 也不能自行授予测试权益。

当前基础实现采用 PostgreSQL，沿用家庭数据库连接，使用独立 `ct_billing_schema_version` 和 V004 迁移，不更改家庭、图片或云端迁移。支付提供器默认 `none`；未配置真实支付时，下单明确返回 `503 PAYMENT_NOT_CONFIGURED`，不插入假订单、不展示虚假成功，也不产生购买权益。

## 配置

- `cardtalk.billing-products-file`：服务端 JSON 配置路径，默认空。`backend/config/billing-products.example.json` 只有空产品列表，不预设价格或商业周期。
- `cardtalk.billing-operator-account-ids`：逗号分隔的明确操作员账号 ID，默认空。只有这些已认证账号可授予人工测试权益。
- `cardtalk.billing-enforced`：默认 false，也可使用环境变量 `CARDTALK_BILLING_ENFORCED`。显式开启时要求 PostgreSQL，云同步 profile/scene/report 的新写入分别要求 profiles/scenarios/analytics 有效权益；未开启时保留开发行为，但不伪造购买权益。
- `cardtalk.billing-notify-url`：提供给支付适配器的受验证回调地址；真实支付的商户、应用、密钥等属于具体适配器配置。

产品文件格式为 `{ "products": [...] }`，每个产品必须且仅包含：`id`、`feature`、`title`、`amountMinor`、`currency`、`durationDays`。feature 为上述三项之一，currency 固定 CNY，金额是 1–100000000 的整数分，durationDays 为 1–3660 的整数。整个文件最多 64 KiB、100 个不重复产品，标题最多 100 字符。格式不正确时启动拒绝，不采用默认价格。已创建订单冻结产品、金额和周期，不因目录更新被改价。

## 家庭接口

家庭接口沿用独立 family cookie/Bearer 身份；trial token 不可使用。Cookie 写入须可信 Origin，家庭接口禁止缓存。

- `GET /api/v1/families/{familyId}/entitlements`：所有家庭成员可读，返回 `{configured,workspace:"family",features:[{feature,active,reason,expiresAt,sources,actions:{readExisting,writeAdvanced,purchase}}]}`。sources 标记当前权益来自 manual-test 或 purchase，不能把人工测试标作真实购买。readExisting 恒为 true；到期/撤销只影响后续高阶写入。此读取接口不删除已有数据。
- `GET /families/{familyId}/billing/products`：返回 `{products,provider,providerConfigured,canPurchase}`。
- `POST /families/{familyId}/billing/orders`：owner/admin 提交 `{productId,requestId}`，价格全部取服务端；返回 `{order,providerConfigured}`。
- `GET /families/{familyId}/billing/orders`：owner/admin 查询最近 100 单。
- `GET /families/{familyId}/billing/orders/{orderId}`：owner/admin 读取已持久化状态。
- `POST /families/{familyId}/billing/orders/{orderId}/query`：向已配置且验证应答的提供器主动查询。
- `POST /families/{familyId}/billing/orders/{orderId}/close`：关闭未支付订单。
- `POST /families/{familyId}/billing/orders/{orderId}/refund`：owner/admin 提交 `{reason}` 发起整单退款。当前基础不提供部分退款。
- `POST /families/{familyId}/billing/manual-grants`：仅配置操作员提交 `{feature,startsAt,expiresAt}`；记录 `source:"manual-test"`，响应 `realPurchase:false`。这不是购买接口。

上面缩写的家庭路径同样包含 `/api/v1` 前缀。Order 字段为 `id,familyId,actorId,productId,feature,title,amountMinor,currency,durationDays,provider,status,outTradeNo,outRefundNo,checkoutUrl,createdAt,updatedAt`。状态为 CREATED/PENDING/PAID/CLOSED/REFUND_PENDING/REFUNDED/FAILED。

## 幂等和支付事实

下单 requestId 在同家庭内唯一，并绑定原账号和 productId 摘要；不同账号或内容复用返回 409。订单先持久保存 CREATED 和唯一 outTradeNo，再联系提供器；响应丢失时使用同一 requestId/outTradeNo 重试。前端跳转回来不构成付款证据。

`PaymentProvider` 独立接口包括 createCheckout/queryPayment/close/refund/queryRefund/verifyNotification。默认 NoPaymentProvider 全部拒绝。真实适配器必须校验提供器签名及所有成功应答；基础层收到 ProviderEvent 后仍与订单冻结的 provider、merchantId、appId、amountMinor、currency 比较。

提供器回调入口为 `POST /api/v1/billing/notifications/{provider}`，最多 64 KiB，不接受浏览器自行声明 paid。只有已配置适配器验签返回的事件可以处理。收件 inbox（提供器+eventId、原文摘要）、订单状态、grant 同一个 PostgreSQL 事务提交，提交成功后才 ACK；数据库失败返回非 2xx。重复同内容事件不重复授予，不同内容复用同 eventId 返回冲突。

成功支付对单个 order 创建一次 `source:"purchase"` 权益。退款复用固定 outRefundNo，必须匹配原金额；成功退款仅撤销对应订单 grant，不影响人工测试或其他订单权益。PAID 不因晚到 CLOSED/FAILED 降级，REFUNDED 不因晚到 PAID 重授。退款失败事件也不能推翻已完成退款。

## 集成边界与验证

`requireAdvancedWrite` 提供权益加角色判断。正式模式的云同步在同一数据库事务和家庭 advisory lock 内调用 Connection 版本，逐项检查当前成员和有效 grant，避免检查后撤权或嵌套加锁。profile/scene/report 的创建、更新、恢复及删除都受对应独立权益控制；room/card、所有既有资料拉取不加购买门槛。已成功操作的同编号幂等重试仅返回原确认，不产生新写，因此到期后仍可获取原确认。批次中任一新写未获权益时整批回滚。云端 status 返回 billingEnforced，明确区分开发与正式执法。本机离线编辑及其他功能开关仍按原契约，不宣称所有平台写入口都已改成付费模式。扩写/报告生产额度持久化属于独立模块，本模块不把当前内存额度声明为生产可用。未配置商价及真实商户时不能完成真实购买。

真实 PostgreSQL 测试按测试创建随机 schema，覆盖独立功能、到期仍读、owner 自授拒绝、操作员授权、nonce/角色/跨家庭、未知支付结果重试、签名伪造、金额币种商户不一致、持久 inbox 幂等、退款乱序及只撤对应 grant。另覆盖正式云写入的独立功能、未授权、到期、跨家庭、退款撤权、helper 角色约束、幂等重试、批次回滚及读取保留。测试提供器与价格只存在于 test 源码，生产没有模拟支付成功接口。
