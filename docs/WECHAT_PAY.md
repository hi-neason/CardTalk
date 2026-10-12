# 微信支付 H5 接入

当前实现普通商户 API v3 H5 支付，默认 `CARDTALK_BILLING_PROVIDER=none`。未配置时购买接口明确返回不可用，不会创建模拟到账。价格、功能期限由现有服务端商品配置决定，本文不提供默认价格。

## 商户配置

在 `backend/.env` 或部署密钥管理系统配置，禁止提交真实密钥：

| 变量 | 说明 |
|---|---|
| `CARDTALK_BILLING_PROVIDER` | 设置 `wechat-h5` 启用；默认 `none` |
| `CARDTALK_WECHAT_MCHID` | 商户号 |
| `CARDTALK_WECHAT_APPID` | 与商户关联的 App ID |
| `CARDTALK_WECHAT_MERCHANT_SERIAL` | 商户 API 证书序列号，用于请求 Authorization |
| `CARDTALK_WECHAT_PRIVATE_KEY_FILE` | 商户 RSA PKCS#8 `PRIVATE KEY` PEM 文件，至少 2048 位 |
| `CARDTALK_WECHAT_PUBLIC_KEYS_FILE` | 公钥 ID 到 PEM 文件路径的 JSON 映射文件 |
| `CARDTALK_WECHAT_API_V3_KEY` | API v3 密钥，UTF-8 恰好 32 字节，通知 AES 解密使用 |
| `CARDTALK_BILLING_NOTIFY_URL` | 商户配置的可公开访问 HTTPS 通知地址；应用回调路由 `/api/billing/notifications/wechat-h5` |
| `CARDTALK_WECHAT_ENDPOINT` | 默认且仅允许 `https://api.mch.weixin.qq.com`；不得带路径、查询、用户信息 |
| `CARDTALK_WECHAT_ALLOW_LOOPBACK_HTTP` | 默认 false，只为本地测试允许字面量回环 IP HTTP |
| `CARDTALK_WECHAT_TIMEOUT_SECONDS` | 全响应硬截止时间，默认 15 秒，1–120 秒 |
| `CARDTALK_WECHAT_MAX_CONCURRENT` | 最大并发请求数，默认 4，1–32 |

公钥文件映射示例（路径可相对映射文件）：

```json
{
  "PUB_KEY_ID_your_current_key": "wechat-current-public.pem",
  "PUB_KEY_ID_your_previous_key": "wechat-previous-public.pem"
}
```

公钥文件必须为 RSA X.509 `PUBLIC KEY` PEM。通过微信商户平台可信渠道取得公钥及 ID；轮换时预先并存新旧公钥，更新配置并重启。响应的 `Wechatpay-Serial` 必须精确命中此映射；它不是商户证书序列号。当前不支持旧的平台证书模式、自动拉取未知公钥或动态信任响应提供的密钥。

`wechat-h5` 缺任何必要配置、密钥格式错误、未知 provider 都使启动失败，不静默降级为 none。默认 none 无需凭据。

## 支付证据与退款

请求使用 JDK HttpClient、Jackson 和 JDK RSA，不依赖支付 SDK。当前适配器保守限制商品描述为 127 个 UTF-8 字节、退款原因为 80 个 UTF-8 字节；过长请求不会发送。JSON 只序列化一次，同一字节串用于签名和发送。规范签名输入为方法、原始路径和查询、时间戳、随机串、原始正文，各段以换行结尾。

所有响应先验证微信 RSA 签名、已固定公钥 ID 和五分钟时间戳窗口，再解析原始正文；204 关单响应也验证空正文签名。签名探测串、未知公钥、篡改内容、重定向、超大正文和超时均不能转成支付成功。响应最多 64 KiB，并有并发上限。

H5 下单仅返回微信 `https://wx.tenpay.com` 支付链接和商户订单号。官方 H5 链接有效期为五分钟，链接返回与用户回跳不是到账依据。只有已验签的订单查询 `SUCCESS` 或验签并解密的 `TRANSACTION.SUCCESS` 通知产出 `PAID`。`REFUND` 交易状态不能当作退款完成。

退款申请返回受理结果，保留相同 `out_refund_no` 重试；不会直接撤销权益。退款查询 `PROCESSING` 返回未完成；只有已验签 `SUCCESS` 或成功退款通知产出 `REFUNDED`。商户订单号、金额、币种由账单服务再次与原始订单比对。退款 API 不包含 appid 时，使用已认证请求上下文绑定配置 appid；可用的 mchid/appid 字段必须一致。通知 ID 作为稳定事件 ID；查询事件 ID 为 `payment:<交易流水或订单号>:<状态>`、`refund:<退款单号>:<状态>`，重复查询不创建新事件。

通知必须首先对原始请求字节验 RSA，再接受 `AEAD_AES_256_GCM`、12 字节 nonce、128 位认证 tag 和 API v3 密钥进行解密。`associated_data` 允许空字符串，任何 AAD/tag 错误拒绝。通知事件类型和解密状态必须一致，交易通知核验 mchid/appid，退款通知核验 mchid。官方退款通知不含 appid 和 amount.currency；仅在通知验签、解密及 mchid 验证之后，将缺省 appid/CNY 绑定配置与账单服务持久化原订单。若可选 appid/currency 存在则必须匹配，查询响应必须明确包含 CNY，缺失不能用通知规则兜底。账单服务负责持久事件收件箱、幂等处理、原订单金额绑定和权益变更。

## 验证与上线边界

`WechatPaymentTest` 使用本地生成 RSA 密钥和 AES 通知、本地 HTTP 服务独立验证请求规范签名，再签署响应；涵盖中文正文、查询、空 204、原始空白、多公钥、篡改、未知 key、过期时间戳、整数金额、通知认证失败、退款处理中、限流、超时和体积上限。测试中的 PAID 仅为本地密码学协议夹具，不是真实到账。

没有发起真实微信商户请求或真实交易。商户开户、H5 产品权限、域名配置、公网 HTTPS 回调、真实下单/查单/退款联调仍需部署环境验收。此适配器仅支持 H5 商户链路；微信小程序支付、iOS App Store/Google Play 等应用内购买及各渠道上架规则未实现或验收。

## 官方依据

- [H5 下单](https://pay.wechatpay.cn/doc/v3/merchant/4012791834)
- [商户订单号查询](https://pay.wechatpay.cn/doc/v3/merchant/4012791838)
- [关闭订单](https://pay.wechatpay.cn/doc/v3/merchant/4012791839)
- [申请退款](https://pay.wechatpay.cn/doc/v3/merchant/4012810597)
- [查询退款](https://pay.wechatpay.cn/doc/v3/merchant/4012810601)
- [请求签名](https://pay.wechatpay.cn/doc/v3/merchant/4012365336)
- [微信支付公钥](https://pay.wechatpay.cn/doc/v3/merchant/4013053249)
- [时间戳校验](https://pay.wechatpay.cn/doc/v3/merchant/4013053420)
- [通知 AES 解密](https://pay.wechatpay.cn/doc/v3/merchant/4012071382)
