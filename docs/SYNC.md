# 显式云端同步

云端同步使用 PostgreSQL，默认关闭。需要 `CARDTALK_FAMILY_REPOSITORY_MODE=postgres`、有效家庭 JDBC 配置，以及 `CARDTALK_CLOUD_ENABLED=true`。本机资料不会因启用账号功能自动上传。未配置时 push/pull 返回 `503 CLOUD_NOT_CONFIGURED`，不回退到本机 JSON 或伪造成功。

## HTTP 契约

所有接口位于 `/api/v1/families/{familyId}/sync`，使用独立家庭账号 cookie `cardtalk_family` 或家庭 Bearer token；试用扩写 token 无效。Cookie 写入要求可信 Origin，所有响应禁止缓存。

- `GET /status`：`{configured,enabled,workspace:"cloud",familyId,assetReferencesSupported,billingEnforced}`，家庭成员均可查询。
- `POST /push`：`{consent:{photos:boolean,observations:boolean},operations:[{operationId,entityType,entityId,baseRevision,deleted,payload}]}`。最多 50 项，整批原子提交。类型为 `profile/card/scene/room/report`；删除 payload 必须为 null。新记录 `baseRevision=0`，后续必须使用准确的已知 revision。
- Push 成功：`{results:[{operationId,entityType,entityId,revision}],cursor:string}`。**Push cursor 仅表示本批确认版本，不能替代 pull 已消费游标**，否则可能跳过其他设备尚未拉取的记录。
- `GET /pull?cursor=0&limit=50`：`{entities:[{entityType,entityId,revision,profileId,deleted,payload}],cursor:string,hasMore:boolean}`。limit 为 1–100；cursor 为服务端非负十进制修订号，不使用客户端时间。客户端仅在成功处理拉取页后推进 pull 游标。
- 修订冲突：`409 {code:"SYNC_CONFLICT",operationId,entityType,entityId,currentRevision,remote}`。remote 包含当前服务端记录或 null。客户端必须显式选择处理方式，不能无提示覆盖。
- 重试必须复用同一 operationId、同一操作内容和 consent；已确认操作返回原修订号。相同编号内容变化或不同账号复用返回 `409 OPERATION_REUSED`。失败整批不留下幂等记录，可安全重试。

## 权限与删除

服务端根据实体结构推导访问范围，不接受独立客户端授权声明：profile 使用 entityId；room 使用 context.profileId，所有表达快照必须属于相同 profile；report 使用 filters.profileId。card/scene、未关联档案房间以及全部/体验者报告属于家庭共享资料。

默认开发模式下，owner/admin/member 可以读写。显式设置 `CARDTALK_BILLING_ENFORCED=true` 后，profile/scene/report 新写入（含删除与恢复）分别检查 profiles/scenarios/analytics 的当前有效权益；未获权益返回 `403 FEATURE_NOT_ENTITLED`，原资料和所有拉取仍保留。检查与写入共用数据库事务及家庭锁，到期或退款撤销无法通过旧前端开关绕过。room/card 不增加购买门槛，成功操作的幂等确认仍可重取。helper 禁止 push，只能读取明确授予的 profile 范围内的 profile/room/report，不能读取家庭共享记录。每个数据库事务在获取家庭写入使用的同一 advisory lock 后重新读取当前成员与 profile 授权，成员撤权与同步写入串行。删除依据已存 scope 校验，不使用删除请求伪造的资料内容。

删除保留 tombstone 与新的 revision。旧副本因 revision 不匹配无法复活记录；只有用户明确解决冲突、以最新 tombstone revision 重新写入才能恢复。pull 仅返回调用者当前可见记录，分页游标不会跳过未返回的可见记录。权限范围变化后客户端应重新从 cursor 0 拉取并清理已撤权的本地云工作区缓存。

## 数据与图片同意

请求体最多 2 MiB，单实体 payload 最多 512 KiB。实体字段白名单、类型、基础范围及集合数量受服务端校验；房间最多 1000 条表达，每次最多 20 张卡。云端不接受任意账号、密码或无关 JSON。

`observations=false` 时，任何非空 observations/observer/observedAt 都会被拒绝。图片 base64 无论是否同意都拒绝；图片引用使用严格的 `assetRef:{id}`，必须 `photos=true`。客户端应将每个 image 转为独立受权上传的图片资源引用，不能直接塞入 data URL。

服务端递归检查 profile/card/scene 与房间快照中的引用，通过 AssetService.metadata 读取已发布元数据并实时核验家庭及使用人权限。asset.familyId 必须等于同步家庭；asset.profileId 必须等于实体推导的 profileId；共享实体使用 entityId 作为资源 scope。客户端渲染收到的引用时，通过同一家人的受权资源下载接口读取内容，不把引用当公共 URL。

## 数据库与验证

`V002__cloud.sql` 使用独立 `ct_cloud_schema_version`，不改变家庭 V001 的版本/校验和。实体表保存当前版本及 tombstone；operation 表保存操作账号、规范化请求 SHA-256 和确认版本。修订序列允许失败事务产生间隙，客户端不得假定连续数字。

真实 PostgreSQL 测试使用 `CARDTALK_TEST_PG_URL/USER/PASSWORD`，每项创建独立随机 schema 并清理。覆盖双实例冲突、丢响应重试、编号盗用、删除恢复、分页、helper 越权与撤权、同意拒绝、批次回滚、图片 scope、独立迁移，以及 HTTP cookie/Bearer/Origin/请求体上限。
