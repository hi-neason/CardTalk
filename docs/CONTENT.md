# 公共资源版本与安装

公共资源服务默认关闭。开启需 `CARDTALK_CONTENT_ENABLED=true`、家庭存储使用 PostgreSQL，以及 `CARDTALK_CONTENT_OPERATOR_ACCOUNT_IDS` 配置具有发布权的账户 ID（逗号分隔）。不会把家庭管理员自动视为平台运营者。数据库启动迁移 V008，仅创建公共资源专用表。

资源内容仅支持文字、随产品发布的内置图标键、既定分类及包内场景关联。无图片 URL、照片、观察、使用人或其他私密字段；未知字段拒绝。每个不可变版本必须填写来源 `source` 和许可 `license`，至多 70 张卡片和 50 个场景。运营者负责核实声明与实际内容一致；系统不从第三方下载素材。

## 运营 API

沿用家庭会话 Cookie 或原生 Bearer。浏览器变更请求须带允许的 Origin；`/api/v1/operations/` 由家庭请求过滤器保护。

1. `POST /api/v1/operations/content/versions` 保存不可变版本；重复 id 返回 409。
2. `GET /api/v1/operations/content/versions` 查询最近 100 个版本。
3. `GET /api/v1/resources/current` 读取 `{revision, package}`；未发布时 package 为 null。公共只读不需要登录，也不接收用户资料。
4. `POST /api/v1/operations/content/release`，正文 `{"versionId":"daily-v1","expectedRevision":0}`。发布与审计在 PostgreSQL 同一事务完成。修订号不符返回 409，调用方必须重新获取状态，不能盲重试。
5. 回滚使用相同 release API，versionId 指向已保存的旧版本，并提交当前 expectedRevision。回滚也产生新的修订号与审计记录，不修改旧版本。
6. `GET /api/v1/operations/content/audit` 查询最近 100 次切换及执行账户。

示例版本（纯文字为示例，发布者应替换为自己的实际来源与许可）：

```json
{
  "id": "daily-v1",
  "title": "日常沟通资源",
  "source": "本项目自编文字",
  "license": "CC0-1.0",
  "cards": [{"id":"sip","label":"小口喝水","category":"actions","icon":"drink","kind":"phrase","description":"表达饮水需求","sceneIds":["break"]}],
  "scenes": [{"id":"break","name":"活动间歇","description":"休息与饮水时表达当下需求","recommendedCardIds":["sip"]}]
}
```

资源 id 在不同版本保持稳定；版本 id 每次改变。单一当前发布指针是完整目录快照，不是多个包自动合并。

## 本机安装

`PublicResources.vue` 接收 `state`、`persist(next):boolean`、`canInstallCards` 和 `canInstallScenes`。外层应在 persist 再检查当前家庭权益/角色，并使用现有安全存储路径。组件不自动获取，也不会轮询；用户点击后先预览来源/许可，再逐项选中，最后点击安装。

安装只新增 `custom-card-public-{id}` 与 `custom-scene-public-{id}`。同 id（即使归档）或同名冲突将拒绝整个本次安装，用户可取消冲突项后重试。有关联的资源必须全部显式选择，缺失依赖会报错，绝不自动勾选。选择状态初始为空。权限不足可以预览，不能安装。

安装在复制的 State 上验证容量/完整性后一次保存，失败不改变源数据。公共资源回滚不会删除已安装资源；再次发布不能覆写用户修改。历史房间、表达快照及家庭照片均保持原样。

## 验证

`npm --prefix frontend test -- resources.test.ts` 覆盖私密字段拒绝、明确选择、缺依赖、冲突原子失败与历史保持。

配置隔离测试 PostgreSQL 环境变量 `CARDTALK_TEST_PG_URL/USER/PASSWORD` 后，`mvn -f backend/pom.xml -Dtest=PostgresContentTest test` 覆盖权限、不可变版本、重启、原子并发发布、回滚审计与默认关闭。测试创建并删除随机独立 schema，不操作现有业务资料。

## 运营界面

公共资源面板下方的“公共内容运营入口”打开独立面板。点击“核验运营权限并刷新”后才显示 JSON 编辑、预览及发布表单。前端同时检查当前登录身份，服务端再次检查配置的运营账户；任何核验失败会清空权限预览。每次写操作前重新核验权限，未知网络结果应刷新版本/审计，不自动重试变更。

“保存此不可变版本”只保存内容；“切换到所选版本”另行展示当前修订号并要求明确确认。并发切换返回冲突后，需要重新核验刷新。版本列表及审计各展示最近 100 条，旧版本不会被删除。

## 来源追溯

安装的卡片和场景保存只读 `provenance` 元数据：`packageId`（不可变资源版本 id）、`title`、`source`、`license`，以及存在时的发布 `revision`。发布回滚后的修订号同样被记录，因此同一版本在不同次发布后的安装可区分。用户编辑标签或场景描述不会替换该来源记录。

进入沟通时，卡片及场景快照复制来源元数据；后续编辑不会改变既有表达。备份导入及云同步均校验该嵌套对象，只接受上述字段；旧记录没有 provenance 仍然合法。新卡片快照只复制表达字段（含 provenance），不混入 sceneIds、archived、updatedAt 管理字段；上传旧存量快照时只在快照/草稿卡片范围清除这些管理字段，不修改本机原件或独立卡片实体。
