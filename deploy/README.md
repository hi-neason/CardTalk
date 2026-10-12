# HTTPS 部署与恢复

这里是可审查的部署配置，不代表已经部署。生产机器需要 Docker Compose、可访问的域名和 80/443 端口。本机已启动 Docker daemon；官方镜像拉取匿名 token 超时，现有备用镜像源返回未授权。因此完整多阶段镜像构建与 Caddy/HTTPS 尚未验证；缓存 JRE 镜像的真实服务及恢复运行已经验证，见下文。

1. 复制 `api.env.example` 为忽略的 `api.env`，设置供应商配置；不要提交密钥。需要个人音色时，将授权目录放在忽略的 `deploy/config/voices.json`，在 `deploy/api.env` 设置 `CARDTALK_VOICE_CATALOG_FILE=/config/voices.json`（目录只读挂载）；格式见 `docs/VOICES.md`。
2. 配置 `CARDTALK_DOMAIN` 与强随机 `CARDTALK_DATABASE_PASSWORD`。Compose 对缺失值拒绝启动；不要把 `docker compose config` 的完整展开输出发布到日志，它包含环境变量。
3. 从仓库根目录执行 `docker compose -f deploy/compose.yaml config --quiet`，随后 `docker compose -f deploy/compose.yaml up --build -d`。
4. Caddy 自动管理 HTTPS；只有 Web 暴露端口，数据库与后端留在容器网络。App/小程序须配置同一个 HTTPS API 地址及各平台合法域名。
5. 验证 `/api/v1/health`、注册/登录 Cookie、真实家庭跨权限负例、显式同步、删除/退款流程后再开放用户。默认没有支付商品或商户，不会产生购买成功。

正式部署启用 PostgreSQL 身份、同步与资源元数据以及独立权益检查。已有 JSON 家庭数据不会自动迁移。首次部署必须使用空数据库或经过另行验证的导入；不要将生产目录指向开发机数据。部署配置已启用扩写与报告 PostgreSQL 额度。不能把 JSON 文件挂入共享卷便宣称多实例安全。

## 备份

数据库与图片对象一起备份；单独的数据库备份不足以恢复图片。不要使用 `docker compose down -v`，它删除持久卷。

```sh
mkdir -p backups
chmod 700 backups
docker compose -f deploy/compose.yaml exec -T db pg_dump -U cardtalk -d cardtalk -Fc > backups/cardtalk.dump
docker compose -f deploy/compose.yaml exec -T api tar -C /data -cf - . > backups/application.tar
chmod 600 backups/cardtalk.dump backups/application.tar
```

维护窗口先停止写入，再备份两者，保存时间与版本信息。对象存储 S3 模式需独立导出相应 bucket 或开启版本及备份策略；上述 tar 不包含 S3 对象。备份包含敏感资料，应加密、限制访问并在隔离环境验证恢复。

## 隔离恢复演练

在独立 Compose project、新卷和独立端口中启动数据库，先 `pg_restore --list` 检查归档，再向空数据库恢复。图片恢复到该隔离 project 的 application 卷；不要将归档解压覆盖正在运行的生产目录。启动相同应用版本，逐项核对家庭权限、记录/报告、资源校验和订单状态。升级时再运行目标版本迁移；回滚应用前检查新 schema 是否向后兼容。恢复后仍需与支付渠道核对备份时间之后的订单和退款，不重放旧通知冒充当前状态。

隔离恢复演练已实际通过：`scripts/verify-isolated-restore.py` 使用专用 loopback cardtalk_test 与 UUID 数据库/Schema、缓存 Java 21 镜像验证 pg_dump/pg_restore、图片归档、恢复后账户登录和图片 HTTP 字节一致。它不操作真实用户数据。生产上线仍需完整镜像、HTTPS、公网供应商及生产备份策略验收。

可通过 CARDTALK_IMAGE_REGISTRY 显式选择自己信任的镜像前缀，默认 docker.io/library。不自动修改 Docker 全局代理、注册表或信任配置；第三方镜像源的供应链验证由部署环境负责。
