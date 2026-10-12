# 家庭图片对象存储

后端提供独立、不可覆盖的图片对象 API。每次上传生成新对象 ID；替换资料图片应更新资料中的引用，而旧图片对象继续保留，避免破坏历史快照。本阶段尚未把前端本机图片自动迁移到服务端，也没有永久删除 API。

## API

登录账户 cookie 与家庭 API 相同；原生客户端也可使用家庭会话 Bearer token。匿名体验 token 无效。所有响应禁止缓存。

上传：

```http
POST /api/v1/families/{familyId}/assets?profileId={profileId}
Content-Type: image/png

<图片原始字节>
```

仅接受 `image/png`、`image/jpeg`、`image/webp`；不是 multipart、JSON、远端 URL 或文件名。浏览器使用 cookie 时必须通过已有 Origin 白名单检查。家庭 ID 与使用人 ID 限制为 1–100 位字母、数字、下划线或连字符。`profileId` 必填，上传需要该家庭使用人资料的写权限，helper 不具备上传权限。

成功返回 HTTP 201，字段为：

```json
{
  "id": "生成的 UUID",
  "familyId": "家庭 ID",
  "profileId": "使用人 ID",
  "mediaType": "image/png",
  "bytes": 345678,
  "width": 640,
  "height": 480,
  "sha256": "图片字节的 SHA-256 十六进制摘要",
  "createdAt": "2026-10-12T00:00:00Z"
}
```

下载：`GET /api/v1/families/{familyId}/assets/{assetId}`。每次请求从不可变元数据读取 `profileId`，再根据当前家庭成员关系和 helper 白名单校验访问权限。返回原始字节、真实 Content-Type、Content-Length、`X-Content-Type-Options: nosniff` 与 `Cache-Control: no-store`。移除成员或撤销会话后，原链接不会继续提供图片。URL 不含凭据，也不作为公开分享链接。

## 图像校验和容量

默认设置：

```dotenv
CARDTALK_ASSET_STORE_MODE=local
CARDTALK_ASSET_DIRECTORY=./data/assets
CARDTALK_ASSET_MAX_BYTES=16777216
CARDTALK_ASSET_MAX_DIMENSION=16384
CARDTALK_ASSET_MAX_PIXELS=40000000
```

单对象默认上限 16 MiB，宽和高各不超过 16384，总像素不超过四千万；容量、尺寸与像素预算分别可配置。没有原先 200 KiB 的上传限制。像素预算用于在完整解码前限制内存分配。

- PNG 校验签名、chunk 长度、CRC、IEND 终止位置，并用 JDK ImageIO 完整解码。
- JPEG 校验标记结构、最终 EOI 与文件结束位置，并用 ImageIO 完整解码。
- WebP 校验 RIFF 容器长度、chunk 边界、VP8/VP8L/VP8X 标记与尺寸一致性。JDK 没有默认 WebP 解码器，本实现**不声称完整解码验证 WebP 压缩流**；不接受动画 WebP。
- SVG、格式与 Content-Type 不符、截断 PNG/JPEG、文件尾追加内容、超容量或超尺寸图片均拒绝。

只有规范化路径上的 `POST /families/{familyId}/assets` 使用图片容量预算。其他账户/家庭 JSON 请求保持 32 KiB 限制；Origin、SameSite cookie 与无缓存规则继续生效。

## 本机不可变文件实现

`AssetStore` 隔离对象字节的写入、读取，以及未发布对象清理，已有本机和原生 JDK S3 两种实现。目前的 `LocalAssetStore` 使用服务器生成的 UUID 文件路径，不采纳上传者文件名。`CREATE_NEW` 防止覆盖对象，读取不跟随对象文件软链接。

`AssetCatalog` 协调对象字节和 `AssetMetadataRepository`。默认 JSON 元数据实现将版本化清单保存在同一对象目录的 `catalog.json`。发布顺序为：写新对象并强制落盘 → 写临时元数据并强制落盘 → 原子替换目录清单 → 发布内存状态。元数据写失败不会返回成功，已写但未发布的对象被清理。启动时校验已有元数据、对象长度和摘要；损坏或对象缺失时拒绝启动，不自动清空。随后清理未被清单引用的对象和遗留临时清单文件，已引用对象不会被回收。

本机目录和 JSON 清单只允许**单个应用实例**管理。即便家庭账户使用 PostgreSQL，本机对象清单仍不是多实例共享元数据。多实例应同时选择 S3 对象存储和 PostgreSQL 元数据；仅将字节后端替换为 S3 并不足够。

## 验证

`AssetTest` 覆盖大于 200 KiB 的真实 PNG、PNG/JPEG 校验、WebP 容器边界、SHA-256 与原始字节、两个图片版本不互相覆盖、重启读取、helper 与跨家庭权限、移除成员后的访问、类型和路径注入、容量限制、元数据写失败、孤立对象清理及损坏存储拒绝加载。

`AssetHttpTest` 使用独立临时数据目录和随机端口启动真实 Spring Boot HTTP 服务，通过 JDK HTTP 客户端完成注册、家庭创建、cookie 上传下载与注销后拒绝访问。它不访问正在运行的开发账户文件或图片目录，不调用付费模型服务。


## PostgreSQL 共享元数据

显式设置 `CARDTALK_ASSET_METADATA_MODE=postgres`，并同时设置 `CARDTALK_FAMILY_REPOSITORY_MODE=postgres`。两者复用同一个家庭 JDBC 地址与凭据，后者先完成家庭表迁移。配置不一致或连接失败会拒绝启动，不回退到本机清单。

`V003__asset_metadata.sql` 创建 `ct_asset_metadata`，对象 UUID 为主键，家庭 ID 外键关联 `ct_families`；使用人范围、类型、字节数、尺寸、摘要、时间均保存为独立列。独立的 `ct_asset_schema_version` 保存 V003 校验值，通过事务 advisory lock 串行化迁移。数据库触发器拒绝修改或删除已发布元数据。时间统一为 UTC 微秒精度，保证跨 JDBC 往返后的不可变记录一致。

每次查询直接读取数据库，不在单个实例缓存授权范围或元数据。多个实例可以独立插入不同对象，主键与不可变约束防止覆盖。对象完整回读并通过字节长度和 SHA-256 校验后，才在数据库事务中发布元数据。

只有独占的 **local 对象 + JSON 清单** 组合会在启动时清理孤立对象。S3 或 PostgreSQL 任一共享模式均不执行启动清理，否则可能删除另一个实例尚在上传、未提交元数据的对象。

## S3 对象存储

只连接已存在的私有 bucket，不创建 bucket、不设置公共 ACL、不生成公开或预签名下载地址。配置示例仅说明字段，真实凭据放在本机环境文件或部署密钥管理系统：

```dotenv
CARDTALK_ASSET_STORE_MODE=s3
CARDTALK_ASSET_METADATA_MODE=postgres
CARDTALK_FAMILY_REPOSITORY_MODE=postgres
CARDTALK_S3_ENDPOINT=https://s3.us-east-1.amazonaws.com
CARDTALK_S3_REGION=us-east-1
CARDTALK_S3_BUCKET=已有的私有bucket
CARDTALK_S3_ACCESS_KEY=从部署密钥注入
CARDTALK_S3_SECRET_KEY=从部署密钥注入
CARDTALK_S3_PATH_STYLE=true
CARDTALK_S3_ALLOW_LOOPBACK_HTTP=false
CARDTALK_S3_TIMEOUT_SECONDS=30
```

endpoint 仅接受 scheme、主机和可选端口，不接受用户名密码、URL 路径、query 或 fragment。默认必须 HTTPS；只有显式开启 `ALLOW_LOOPBACK_HTTP` 且目标为经过回环校验的本机地址，才允许 HTTP，供本机 fixture/兼容服务测试使用。生产地址不能通过此选项绕过 HTTPS。`PATH_STYLE=false` 将 bucket 放在主机名前缀；需要匹配的 DNS 与 TLS 证书。对象键固定为 `cardtalk-assets/<UUID>.blob`，不接受外部对象路径。

使用原生 JDK HttpClient、HMAC-SHA256 与 AWS SigV4，不新增 S3 SDK：

- PUT 对真实字节计算 SHA-256，签名包含 `x-amz-content-sha256`、时间、host、Content-Type 和 `If-None-Match: *`；已有对象返回 412/409 时不覆盖，也不自动重试未知结果的写入。
- GET 同样签名，响应体受图片字节预算与完整请求 deadline 约束；读取后由清单校验 SHA-256 和长度。HTTP 错误、响应超量、超时不返回伪造图片。
- PUT 成功必须返回可用 ETag。只在当前上传器持有未发布凭证时，才允许回滚 DELETE，并签入 `If-Match: <ETag>`，防止误删后来被外部改变的对象。成功发布或结果不确定后会放弃回滚凭证；已发布图片不提供删除接口。
- 客户端永远只访问已鉴权的家庭对象 API，不接触 S3 凭据。S3 请求不跟随重定向；程序不记录访问密钥、密钥或 Authorization。

当前支持静态访问密钥，未实现 STS 会话 token、IAM 角色凭据链或自动轮换。兼容服务需实现上述条件 PUT、ETag 和条件 DELETE 语义；本轮尚未在真实 AWS 或外部 MinIO 账户上上传验证，不能将本机 fixture 测试称为外部供应商验证。

签名与条件请求依据：

- [AWS IAM — Create a signed AWS API request](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_sigv-create-signed-request.html)
- [Amazon S3 — PutObject](https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html)

## 对象与数据库的双写边界

对象存储和元数据数据库不是同一个事务。流程为不可覆盖 PUT → 回读并核对完整性 → 插入元数据并提交 → 放弃未发布回滚凭证。

明确未提交且数据库确认未发布时，可以删除本次上传的对象。提交响应丢失时先读取元数据；若已存在且对象校验通过，可确认成功。若提交结果仍不确定，保留对象并返回错误，不冒险删除可能已经发布的图片。网络中断也可能留下未引用对象；共享模式下需后续有租约/保留期与引用核验的回收流程，本阶段不自动清理。

家庭账户 PostgreSQL 迁移、对象元数据迁移和对象字节迁移是独立工作。改变配置不会自动把已有本机图片上传到 S3，也不会把 JSON 清单转换成 PostgreSQL 行。

## 共享存储验证

`PostgresAssetTest` 使用专用 PostgreSQL 环境变量，每次测试创建并清理独立 schema，覆盖双仓库并发发布、重启可见、不可变触发器、家庭外键、已失败上传回滚，以及不确定提交保留对象。没有真实 PostgreSQL 配置时明确跳过。

`S3AssetStoreTest` 使用本机 HTTP 服务，根据官方 SigV4 规则独立验证 canonical headers、HTTP 方法、路径、空 query、字节摘要和最终签名，并验证条件创建、防覆盖、仅未发布对象回滚、服务错误、响应体超量与读取超时。没有真实外部对象上传或 bucket 创建。
