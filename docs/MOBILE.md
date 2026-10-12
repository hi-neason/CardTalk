# 移动端工程与验收

## 平台与构建

继续使用传统 uni-app、Vue 3 和 TypeScript；Android/iOS 使用 `app-plus`，HarmonyOS NEXT 使用 `app-harmony`，不迁移 uni-app x。平台依赖与现有编译器固定为 `3.0.0-5020420260813003`（编译输出 5.24）。

要求 Node ≥22、npm ≥10。在仓库根目录执行：

```sh
npm ci
npm run build:android
npm run build:ios
npm run build:harmony
```

资源分别输出到 `frontend/dist/build/app-android`、`app-ios`、`app-harmony`。开发命令为 `npm run dev:app --workspace frontend` 与 `npm run dev:app-harmony --workspace frontend`。

CLI 资源构建成功只证明前端资源可以编译。含本地 UTS 插件的原生编译、APK/IPA/HAP/APP 签名、安装和设备能力验证还需要 HBuilderX 对应编译器与平台工具链。不要把这些输出称为可安装包。本机当前未安装 HBuilderX、DevEco Studio、完整 Xcode 或 Android SDK，因此没有签名包与真机验收证据。首次打包必须填写自己的 DCloud AppID、平台包名和签名资料；仓库不保存证书或密钥。

## 原生语音

`frontend/src/uni_modules/cardtalk-tts` 使用系统 API，不包含第三方付费语音 SDK：

- Android：`TextToSpeech`、已安装且不要求网络的中文 `Voice`，`UtteranceProgressListener` 提供开始/完成/失败/中断事件；服务查询声明在插件 AndroidManifest。需要系统安装中文离线音色。
- iOS：`AVSpeechSynthesizer`、`AVSpeechSynthesisVoice` 和 delegate；原生操作在主线程串行，按当前 utterance 忽略迟到回调。
- Harmony：`@kit.CoreSpeechKit`、`textToSpeech.createEngine`，官方参数 `online: 1` 表示离线，中文 `person: 0`。语音列表先真实创建引擎验证可用。`onComplete` 的 `type: 0` 仅代表合成结束，只有 `type: 1` 才代表播报完成。

TS 接入 `platform/nativeSpeech.ts`：`startNativeSpeech(text, rate, update, options)` 返回取消函数；`getNativeVoices()` 返回实际设备语音选项。排队成功不会上报开始。15 秒未开始或缺失完成事件会显式失败，取消后忽略迟到事件。用户确认表达后才能调用；不得以选择卡片/生成候选触发播报。已通过三个平台 CLI 构建和 UTS 转译，产出了 Kotlin / Swift / Harmony 插件代码；尚待平台原生编译、链接和真机核验，TypeScript 检查不会检查 `.uts` 文件。

## 远端语音与图片

`prepareNativeAudio(ArrayBuffer)` 返回临时文件 `{src, dispose}`。调用方在播放结束、失败、取消，以及等待落盘期间取消后拿到迟到结果时，都必须 `dispose()`。

Android/iOS 使用应用私有目录的 `plus.io.FileWriter.writeAsBinary`；由于此方法只有 DCloud 类型声明与社区实现证据，写完必须同时验证字节长度和 FileReader 回读 Base64。无法确认字节完整时删除并失败，不播放疑似损坏文件。写读有 15 秒超时，迟到回调清理文件。

Harmony 使用 FileSystemManager，通过本地 UTS 插件的宿主 `getContext().cacheDir` 取得缓存目录，没有硬编码设备沙箱路径，也不依赖未公开的 `uni.env`。`getContext` 从 API 18 起被标记废弃（当前仍支持）；后续平台升级需迁移为 Window UIContext 的 getHostContext，并重新验收。

`readNativeImage(path)` 将用户已选择的文件读为 data URL。Android/iOS 使用 FileReader，Harmony 使用 FileSystemManager 和 getImageInfo；结果仍交给共享图片格式、像素与内容校验，不恢复已取消的 200KB 限制。仅加载用户选择的本地文件，不后台扫描相册。

## 当前交付边界

目前已接入移动端图片选择、原生/远端语音和设备音色。系统文件备份适配已实现，原生账户已使用内存 Bearer 登录；原生备份和完整移动 UI 仍需真机验收。三端工程构建通过不能代替平台 SDK 编译、签名及真机验证。

## 设备验收清单

在 Android 手机/平板、iPhone/iPad 和 Harmony 手机/平板分别验证：

1. 全新安装、横竖屏、大字体、触控防误触、20 卡限制、最多三候选及显式确认。
2. 断网固定卡声音、系统中文朗读；未安装音色时明确提示，禁止误报成功。
3. 系统朗读中取消、切换使用人/场景、切后台、连续快速播放；只记录真实结束状态。
4. 远端返回 MP3 后落盘、播放、取消和清理；读写失败、迟到回调及低存储空间。
5. 图片选择、相机权限拒绝、大图、重启后读取、删除后清理；不自动上传照片。
6. 局域网开发 API 使用电脑可达地址与允许的 Origin；生产 API 使用 HTTPS。手机的 `127.0.0.1` 指手机本身，不能指向 Mac 后端。
7. 原生内存 Bearer 登录、退出、冷启动重新登录、云同步预览/冲突、授权过期和数据保留在实际平台验证。Web 使用 HttpOnly Cookie；原生不依赖 Cookie 持久化，现有测试不证明真机运行。

## 依据

- [DCloud CLI](https://uniapp.dcloud.net.cn/quickstart-cli.html)
- [App 运行与发行](https://uniapp.dcloud.net.cn/tutorial/run/run-app.html)
- [Harmony 运行与发行](https://uniapp.dcloud.net.cn/tutorial/harmony/runbuild.html)
- [UTS Harmony（宿主 context）](https://uniapp.dcloud.net.cn/plugin/uts-for-harmony.html)
- [UTS Android](https://uniapp.dcloud.net.cn/plugin/uts-for-android.html)、[UTS iOS](https://uniapp.dcloud.net.cn/plugin/uts-for-ios.html)、[多次回调 keepAlive](https://uniapp.dcloud.net.cn/plugin/uts-plugin.html)
- [Android UtteranceProgressListener](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener)
- [Apple AVSpeechSynthesizerDelegate](https://developer.apple.com/documentation/avfaudio/avspeechsynthesizerdelegate)
- [Harmony TTS API V13](https://developer.huawei.com/consumer/cn/doc/harmonyos-references-V13/hms-ai-texttospeech-V13)
- [writeAsBinary 社区实现](https://github.com/zhetengbiji/image-tools/blob/master/index.js)（非官方稳定 API 保证）

## 系统文件备份

`platform/nativeDocument.ts` 提供以下接口，导入结果继续走现有的校验、预览和二次确认，不会自动替换数据：

```ts
exportText(filename, text): Promise<{ status: 'saved' | 'cancelled' }>
chooseJSON(): Promise<string | null>
cancelNativeDocument(): void
```

Android 通过 Storage Access Framework 的 `ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT` 让用户选择文件，使用 ContentResolver 流读写；不申请整个存储目录权限，也不持久保留文件访问授权。iOS 使用 UIDocumentPicker 和 UTType.json，现代初始化 API 要求 **iOS 14 或以上**，导出临时文件在完成或取消时删除，导入只读取用户选择的文件。Harmony 使用 DocumentViewPicker 和文件描述符，操作结束必定关闭文件。

读取按 `MAX_LOCAL_STATE_CHARACTERS × 3` 限制 UTF-8 字节（当前 15 MiB），原始备份传输仅按实际字节数限制，允许合法格式化 JSON 或待恢复原文超过 5 MiB 字符；解码后由 domain 对解析后的紧凑状态执行 5 MiB 字符容量检查；这是备份容量边界，不是恢复图片的 200KB 限制。UTF-8 解码拒绝截断、多余延续字节、过长编码和非法 Unicode。导出只有系统确认选定目标、字节实际写入完成后才返回 saved；剪贴板不计作文件备份。

同一时刻只允许一个文档选择请求。页面卸载或显式取消时应调用 cancelNativeDocument；不要因系统文件选择器触发 App onHide 就取消选择。五分钟未完成选择会向原生请求取消；不能确认时再等待最多 30 秒并报告“结果尚未确认”，不虚报已取消。迟到回调不能触发读取结果或数据替换。Android 用原子状态转换协调取消与提交：取消获胜时不会打开或截断目标；写入先取得提交权时不接受取消，等待真实 saved/failed。iOS 导出由系统选择器拥有复制过程，应用内取消不会抢先声称系统已取消，必须等待选择器完成或用户取消；导入选择可以主动关闭。Harmony 的 DocumentViewPicker 没有关闭 API，取消只使旧结果失效，系统窗口由用户关闭；同步写入已开始时只报告真实结果，旧窗口返回后不会开始读写。写入失败可能留下不完整文件，只有真实完成才报告保存成功。

验证边界：TS 适配层与 UTF-8/容量/取消回调测试可本机运行；平台原生文件选择、iCloud/系统文件提供者、SAF provider 和真机权限仍需在签名应用中验收。

官方参考：[Android SAF](https://developer.android.com/training/data-storage/shared/documents)、[Apple UIDocumentPickerViewController](https://developer.apple.com/documentation/uikit/uidocumentpickerviewcontroller)。Harmony 签名同时按固定版本编译器附带的 `@ohos.file.picker` / `@ohos.file.fs` 声明核对。
