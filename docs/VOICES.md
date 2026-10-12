# 按使用人选择远端音色

设备自带朗读继续使用 deviceVoiceURI。远端合成可以保存独立的逻辑 remoteVoiceKey，并在播放请求中传入 voiceKey 与 audience（children/adult）。选择只影响声音，不改变已确认表达，不推断年龄、性别、诊断或情绪。

`GET /api/v1/voices` 返回数组，每条只有 `key,label,audience,emotionSupported,configured`，响应 no-store。key=`default` 是明确使用旧系统默认音色；其他条目来自服务器目录。configured 只表示本机配置齐备，不证明讯飞控制台已授权、服务可用或用户觉得适合。上游拒绝仍保留 TTS_VOICE_NOT_AUTHORIZED 等真实错误。

`POST /api/v1/speech` 在已有 requestId/text/rate/emotion 上增加可选 `voiceKey`（1–100 字母数字下划线连字符）与 `audience`（children/adult）。没有选择时省略 voiceKey，不传空串。选择顺序为显式key、匹配audience的首条、audience=all的首条、旧默认。显式未知key返回422 TTS_VOICE_UNKNOWN，不能悄悄换声；显式default始终选择旧默认。调用取消仍按身份+请求编号，不按音色分组，改音色不能绕过取消、重复在途或全局并发限制。

## 服务端目录

`CARDTALK_VOICE_CATALOG_FILE` 未设置时完全保留原有单音色行为。设置后读取本机JSON，最多64KiB/50条；非法、重复、跨引擎或未知字段配置会阻止启动，不忽略错误回退。所有条目使用已有全局 CARDTALK_XFYUN_ENGINE 与endpoint/密钥，不增加客户端自定义端点或供应商资源能力。

super-smart示例（vcn必须替换为已授权的真实发音人标识）：

```json
{"voices":[
  {"key":"child-choice","label":"儿童候选音色","audience":"children","vcn":"AUTHORIZED_CHILD_VCN"},
  {"key":"adult-choice","label":"成人候选音色","audience":"adult","vcn":"AUTHORIZED_ADULT_VCN"}
]}
```

voice-clone模式使用 resourceId 字段替代vcn，协议vcn保持x6_clone。resourceId指向已由其他流程合法创建、获得授权的资源；本功能不上传训练音频、不训练或克隆声音。目录audience允许children/adult/all，标签及适用群体由运营人工配置，不是系统声学评价。资源标识仅接受1–200位字母数字下划线连字符点号冒号，不接受URL。

emotionSupported按当前引擎的实际实现返回：voice-clone已配置条目支持现有style映射，super-smart不假装支持情绪参数。明确情绪仍来自使用者所选情绪卡，不能根据音色、年龄或preset推断。费用和体验额度政策未改变。

CloudPayload只允许preferences的可选remoteVoiceKey逻辑标识（或空串表示未选）；不会接受vcn、resourceId、端点或凭据。目录键被移除后，历史所存key仍是原选择，新合成会明确提示不可用。

## 验证

VoiceCatalogTest 使用loopback WebSocket验证两种引擎的children/adult差异、显式选择覆盖、未知key无上游调用、不同音色共享请求取消/并发边界、公开目录不包含内部资源/密钥及非法目录启动失败。CloudRemoteVoiceTest验证新增可选字段严格校验和旧偏好兼容。真实讯飞授权与音质需要部署方使用已授权资源验收，本地协议测试不等价于真实授权。

## 客户端选择与验收

沟通偏好支持全局与个人远端音色 key；个人偏好冻结进会话和历史快照。默认按儿童／成人匹配服务端目录，显式选择 default 使用服务端默认声音。目录仅点击获取时请求，试听先取得体验身份，停止或关闭后迟到响应不触发合成。确认表达的播放请求只传文本、语速、所选情绪、逻辑音色和 audience，不传照片、设备 voice URI 或档案细节。未知音色错误保留已确认表达。

部署使用 api.env 的 CARDTALK_VOICE_CATALOG_FILE=/config/voices.json 与只读配置目录；不要提交实际凭据或未经授权音色。已通过目录、快照、请求、试听取消及错误保留测试；真实授权、音质和平台播放仍需对应环境验收。
