# CardTalk

面向口语有限的儿童与家庭，以及卒中后有沟通困难的成人与家庭的中文 AAC 辅助沟通产品。

当前本机版本已提供：创建/恢复/结束同机聊天室、分类搜索选卡、联网 AI 扩写、候选确认与修改、声音播放、本机历史及理解反馈。单次表达最多 20 张卡片，最多 3 个候选；AI 不会自动确认或发声。

## 在 MacBook 本机运行

需要 Node.js 22+（附带 npm 10+）、JDK 21+、Maven 3.6.3+。开发时无需 Docker、数据库或云部署。

```sh
npm ci
cp backend/.env.example backend/.env
```

在本机编辑 `backend/.env`，填入 `CARDTALK_LLM_API_KEY`。首期供应商为 DeepSeek，模型名称可通过 `CARDTALK_LLM_MODEL` 配置。密钥只放在后端；未配置时仍可选卡、展示原始表达、播放固定卡片声音和查看本机记录，扩写会明确提示服务未配置。

打开两个终端，在仓库根目录分别运行：

```sh
npm run dev:backend
```

```sh
npm run dev
```

访问 **http://127.0.0.1:5173**。H5 开发服务器把 `/api` 代理给本机后端 8080。启动脚本自动读取 `backend/.env`，不执行文件中的代码、不展开变量；外部环境变量优先。前后端默认仅监听本机。若修改后端端口，需要同步修改 `frontend/vite.config.ts` 的代理。

设置中提供三个独立、保存在本机的开关：

- **AI 扩写**：开启后用 DeepSeek 整理选卡；关闭后直接使用原始表达。
- **语音合成**：开启后用科大讯飞拟人合成；关闭后用设备发声。Web 中文声音依赖系统/浏览器；小程序不能直接调用系统朗读新句子，保留固定卡片声音和文字展示。
- **情绪识别**：根据明确选中的开心、难过、生气、害怕卡片传递语气。无情绪、冲突、否定或修改过的表达使用自然语气，不猜测本人情绪。

默认开启扩写，关闭讯飞合成与情绪识别。三个开关互相独立；关闭扩写仍可合成已确认的原始表达。切换开关会取消相关等待/播放，扩写或情绪设置变化会清除待确认候选，保留原始选卡与已确认历史。

讯飞凭据在 `backend/.env` 配置 `CARDTALK_XFYUN_APP_ID`、`CARDTALK_XFYUN_API_KEY`、`CARDTALK_XFYUN_API_SECRET`；`CARDTALK_XFYUN_VCN` 必须使用账号已开通的超拟人音色。普通超拟人接口未提供这里所需的显式四情绪参数。情绪合成使用官方多风格协议，需要设置 `CARDTALK_XFYUN_ENGINE=voice-clone` 并填写已授权多风格资源的 `CARDTALK_XFYUN_RESOURCE_ID`。只消费已有资源，本应用不采集录音或训练音色。设置页展示配置状态；不支持情绪的音色会明确提示，不模拟情绪合成。

DeepSeek 与讯飞分别开通和配置；修改后端环境文件后重新启动 `npm run dev:backend`。接口和授权边界见 [后端文档](backend/API.md)。

## 微信小程序与其他平台

```sh
npm run build:weixin
```

使用微信开发者工具导入 `frontend/dist/build/mp-weixin`，配置自己的 AppID。连接后端时在 `frontend/.env.local` 设置 `VITE_API_BASE_URL` 为可达的 HTTPS 地址，重新构建，并在微信后台设置 request 合法域名。本机测试可用开发者工具的域名检查设置；正式发布仍需 HTTPS 与合法域名配置。

小程序将沟通数据保存在本机，联网时限额体验扩写。固定卡片声音在资源已下载并保留时可离线使用；新句子需要启用已配置的讯飞语音。H5 当前没有 Service Worker，不支持断网后首次加载/刷新页面的离线保证。

首期采用传统 uni-app + Vue 3 + TypeScript CLI，已验证 H5 与 mp-weixin 构建。Android/iOS/HarmonyOS NEXT 手机和平板仍属于后续交付与真机验证范围；本版本不提供原生桌面程序。Web 已提供本机家庭管理第一阶段；微信体验版不开放高级管理入口。分析与支付尚未接入。

## 家庭管理（本机第一阶段）

Web 顶栏「家庭管理」提供：多使用人资料、自定义卡片、场景的新增/编辑/归档/恢复。支持兴趣、性格、表达习惯、带记录人与日期的家人观察；卡片支持分类、词语/整句、内置图案和本机照片；场景支持活动/物品描述与推荐卡片。

在创建聊天室时选择使用人和场景，并可单独填写本次状态。沟通固定当时的资料快照，后续编辑或归档不会改写当前会话和历史。扩写只发送相关资料与卡片文字，不发送照片或长期观察。设备试听不调用付费语音；照片与家庭资料保存在当前浏览器，并随本机备份导出。

家庭模块通过三个独立的**开发开关**控制，在 `backend/.env` 配置并重启后端：

```dotenv
CARDTALK_PROFILE_MANAGEMENT_ENABLED=true
CARDTALK_SCENARIO_MANAGEMENT_ENABLED=true
CARDTALK_CUSTOM_CARDS_ENABLED=true
```

示例默认档案/场景关闭、自定义卡片开启；功能关闭时现有资料只读，基础沟通与历史保留。实际付费授权、家庭账户和云同步仍待后续实现，不能把开发开关当成生产权限。本轮已为当前 Mac 本机开启三个模块。详见 [家庭管理说明](docs/FAMILY.md)。

## 验证

```sh
npm test
npm run typecheck
npm run test:backend
npm run build
npm run build:weixin
```

浏览器回归使用系统 Chrome；先启动上面的前后端，再运行：

```sh
npm run test:e2e
```

浏览器测试中的候选由明确的测试 fixture 提供；后端测试使用本机 HTTP/WebSocket 测试服务，不消耗真实 DeepSeek/TTS 额度。另有浏览器用例验证真实本机后端未配置时的错误和原始表达回退。详细证据及边界见 [MVP 交付说明](docs/MVP.md)。

## 数据与技术文档

沟通记录保存在当前浏览器/微信本机存储，刷新可恢复；清理客户端数据会移除记录，请通过设置导出备份。联网扩写会向后端和供应商发送本次选卡及必要场景/参与者上下文。CardTalk 后端不保存沟通正文；仅保存匿名体验额度与去重元数据。这不代表供应商不留存数据。

当前后端使用单进程本地 JSON 元数据存储，适用于开发验证。生产账户、可靠限额、PostgreSQL、云同步、授权管理与上线部署尚未实现。

- [产品全景与分阶段功能](docs/PRODUCT.md)
- [体验与信息架构](DESIGN.md)
- [MVP 交付说明与选型决策](docs/MVP.md)
- [本机家庭管理、数据与授权边界](docs/FAMILY.md)
- [后端接口、配置与数据边界](backend/API.md)
