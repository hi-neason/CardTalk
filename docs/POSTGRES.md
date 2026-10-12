# 家庭账户 PostgreSQL 存储

本阶段将家庭账户、登录会话、成员与邀请持久化到 PostgreSQL。沟通记录、卡片、图片及使用人资料仍保存在客户端；切换数据库不会自动上传这些数据。

## 选择存储模式

`CARDTALK_FAMILY_REPOSITORY_MODE` 仅接受 `json` 或 `postgres`，缺省为 `json`。其他值明确拒绝启动。`json` 使用原有本机文件，仅适用于单进程开发；`postgres` 使用下面的独立配置：

```dotenv
CARDTALK_FAMILY_REPOSITORY_MODE=postgres
CARDTALK_FAMILY_JDBC_URL=jdbc:postgresql://127.0.0.1:55432/cardtalk
CARDTALK_FAMILY_JDBC_USER=cardtalk
CARDTALK_FAMILY_JDBC_PASSWORD=填入本机环境文件
```

地址与凭据均由环境或忽略的 `backend/.env` 提供。启动脚本按数据读取环境文件，不能 `source`。不要把真实密码放进 JDBC URL、命令参数、仓库或日志。远程 PostgreSQL 连接按部署环境配置 TLS，例如 URL 添加 `sslmode=verify-full` 并安装合适的服务端证书信任配置。

PostgreSQL 模式的连接失败、迁移失败均会拒绝启动，不会回退到 JSON。已有 JSON 文件不会导入、覆盖或删除。改回 `json` 会重新使用原文件；两种模式的数据彼此独立。不要在未经明确迁移的情况下把切换模式当作数据迁移。

公开部署前还需配置 HTTPS、`CARDTALK_FAMILY_COOKIE_SECURE=true`、明确的 `CARDTALK_FAMILY_ALLOWED_ORIGINS`，并根据实际注册策略设置 `CARDTALK_FAMILY_REGISTRATION_ENABLED`。数据库用户需要当前应用 schema 的建表和写入权限；应用启动会执行版本迁移。

## 表与迁移

`backend/src/main/resources/db/migrations/V001__families.sql` 与迁移入口合计建立八张表（七张业务表、一张版本表）：

| 表 | 内容 |
| --- | --- |
| `ct_family_accounts` | 账户 ID、唯一用户名、密码盐与派生哈希 |
| `ct_family_sessions` | 会话 token 哈希、账户、到期时间 |
| `ct_families` | 家庭名称与 ID |
| `ct_family_members` | 家庭/账户成员关系、角色 |
| `ct_family_profile_access` | helper 可读使用人的授权 ID |
| `ct_family_invitations` | 一次性邀请码哈希、家庭、发出者、角色与到期时间 |
| `ct_family_invitation_access` | 邀请赋予的使用人授权范围 |
| `ct_family_schema_version` | 迁移版本、SHA-256 校验值和执行时间（由迁移入口建立） |

成员与授权采用联合主键，账户名与 token 哈希有唯一约束，关联数据有外键，角色、有效期和授权 ID 有字段约束。数据库不保存原始密码、原始会话 token 或原始邀请码。

迁移运行于事务中，并获取 PostgreSQL transaction advisory lock。并发实例只会执行一次迁移。已有版本必须匹配代码中的迁移校验值；未知版本或修改过的已应用迁移会拒绝启动。不要直接修改已经在目标数据库应用过的 V001；后续结构变更需要新版本迁移及执行器支持。

## 事务与多实例

读取使用 `REPEATABLE READ` 只读事务，多个表来自一致快照。

写入使用 `READ COMMITTED` 事务，先获取数据库级 advisory transaction lock，再加载最新快照、执行领域操作，最后只对变化行做 upsert、对移除行做按主键删除。父记录先写、子记录先删；提交成功后才向调用者返回。SQL 失败或领域操作抛出异常会回滚。两个服务实例不能读取同一旧快照后相互覆盖写入。

该实现保留现有 `FamilyRepository` 快照接口，因此每次操作仍加载整组账户和家庭表，所有家庭写入共用一把事务锁。它提供多实例一致性，尚未做大规模数据性能优化。后续应按实际规模引入更细粒度查询接口与连接池，不能仅通过增加服务实例提升写吞吐。

驱动使用 Spring Boot BOM 管理的 PostgreSQL JDBC 版本。缺省连接超时 5 秒、socket 超时 20 秒，服务端语句超时 10 秒、锁等待超时 5 秒。SQL 异常日志仅记录 SQL state；异常 cause 保留用于受控诊断，不返回给 HTTP 客户端。

官方连接参数依据：[pgJDBC — Initializing the Driver](https://jdbc.postgresql.org/documentation/use/)。

## 真实 PostgreSQL 测试

`PostgresFamilyTest` 不使用 H2 或 JDBC mock。测试需要显式提供：

- `CARDTALK_TEST_PG_URL`
- `CARDTALK_TEST_PG_USER`
- `CARDTALK_TEST_PG_PASSWORD`

缺少 URL 时测试明确跳过，不能把跳过视为 PostgreSQL 验证通过。测试用户需要建 schema 权限；每个测试创建唯一 `ct_test_<UUID>` schema，并且只清理该 schema，不清空其他表。只能指向专用测试数据库。

本机隔离环境可将变量写入权限为 `0600`、已被 Git 忽略的 `.cardtalk/pgtest.env`。以下方式将它作为数据读入子进程环境，不输出凭据、不执行文件内容：

```python
from pathlib import Path
import os
import subprocess

values = dict(
    line.split('=', 1)
    for line in Path('.cardtalk/pgtest.env').read_text().splitlines()
    if line and not line.startswith('#')
)
subprocess.run(
    ['mvn', '-f', 'backend/pom.xml', 'test'],
    env={**os.environ, **values},
    check=True,
)
```

覆盖多仓库并发新增不丢数据、重启后的登录/权限/会话撤销、一次性邀请、helper 授权变化、事务失败回滚、数据库外键/唯一约束、并发重复迁移及校验值不匹配拒绝启动。
