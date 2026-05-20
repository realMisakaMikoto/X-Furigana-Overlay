# XJapaneseFuriganaOverlay

一个自用 Android APK，用于在 Android 端 X / Twitter App 中辅助识别当前屏幕上的日文 post，并通过 OpenAI-compatible LLM 生成符合语境的 furigana 注音结果。

项目不需要 root，不使用 Xposed / LSPosed，不修改 X / Twitter APK。核心实现基于 Android `AccessibilityService`、`WindowManager` 悬浮窗和传统 View / WebView。

## 致谢

感谢凉宫春日黑客松galcode项目对于本项目创作者的大量情绪价值。
项目地址：https://github.com/sjyinzju/Galcode_island

## 功能概览

- 通过无障碍服务读取目标 App 当前屏幕中的文本节点。
- 默认只监听 X / Twitter 相关包名，可在设置中配置目标包名。
- 从当前屏幕文本中筛选疑似日文 post。
- 显示可拖动悬浮按钮 `ふ`。
- 点击悬浮按钮后显示悬浮面板，列出当前识别到的日文 post。
- 用户点击某条 post 后，请求 LLM 生成 furigana。
- 使用 HTML ruby 渲染注音结果。
- 支持复制注音结果。
- 支持缓存同一条 post 的注音结果，减少重复请求。
- 支持多个 API 配置，可切换不同 Base URL / API Key / 模型。
- 成功注音的 post 会自动保存到笔记。
- 支持从笔记中打开加词页面。
- 支持单词本。
- 手动加词时，用户圈选文本后可让 LLM 根据原文上下文识别选区读音。
- X post 注音时会尝试识别数字表达式，例如年份、日期、时间等，并结合上下文生成读音。

## 技术栈

- Kotlin
- Android View / XML
- AccessibilityService
- WindowManager `TYPE_APPLICATION_OVERLAY`
- WebView
- OkHttp
- Kotlin Coroutines
- SharedPreferences
- Gradle Kotlin DSL

## 环境要求

- Android Studio
- JDK 17
- Android SDK 36
- Android 8.0+，`minSdk = 26`
- 可用的 OpenAI-compatible Chat Completions API

## 构建

Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS / Linux:

```bash
./gradlew :app:assembleDebug
```

构建产物：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 初次使用

1. 安装 APK 并打开应用。
2. 在主界面开启无障碍设置，启用 `XJapaneseFuriganaOverlay` 服务。
3. 开启“显示在其他应用上层”权限。
4. 在 API 设置中填写：
   - API 名称
   - API Base URL
   - API Key
   - 模型名
5. 确认目标包名列表包含 X / Twitter 包名，例如：

```text
com.twitter.android
com.x.android
```

6. 打开启用开关。
7. 打开 X / Twitter App。
8. 点击悬浮按钮 `ふ`。
9. 在悬浮面板中选择识别到的日文 post。
10. 等待注音结果生成。

## API 配置

本项目调用 OpenAI-compatible Chat Completions API。

Base URL 可以填写完整 endpoint：

```text
https://api.openai.com/v1/chat/completions
```

也可以填写到 `/v1`：

```text
https://api.openai.com/v1
```

应用会自动补全为 `/chat/completions`。

API Key 不会写死在代码中，会保存在本机应用私有存储的 SharedPreferences 中。

## 使用流程

### 注音当前屏幕 post

1. 打开 X / Twitter。
2. 等待无障碍服务识别当前屏幕文本。
3. 点击悬浮按钮 `ふ`。
4. 在列表中点击一条 post。
5. LLM 返回注音后，WebView 会显示 ruby 结果。
6. 可以复制结果，或进入加词页面。

示例渲染：

```html
<ruby>人気<rt>ひとけ</rt></ruby>がない道だった。
```

### 笔记

每次成功注音的 post 会自动保存到笔记中。可以在主界面进入笔记列表查看历史记录。

笔记中支持进入加词页面，方便从历史 post 中选择词汇加入单词本。

### 单词本

从注音结果或笔记进入加词页面后，可以手动圈选想保存的词。点击 `使用选中文本` 后，应用会把选区文本、原文上下文和选区范围发送给 LLM，让模型返回该选区的平假名读音。

这比本地硬分词更适合处理：

- 汉字 + 平假名混合词
- 汉字 + 片假名混合词
- 数字 + 日期 / 时间表达式
- 人名、地名、网络语等上下文相关读法

## 隐私说明

本工具需要无障碍权限读取目标 App 当前屏幕文本。读取到的文本仅用于本地筛选和用户主动触发的注音流程。

当前设计遵守以下边界：

- 默认只监听配置的目标包名。
- 不读取非目标 App 内容。
- 不会自动上传整个屏幕文本。
- 只有用户点击某条 post 后，才会把该 post 文本发送给 LLM。
- 只有用户在加词页点击 `使用选中文本` 后，才会把选区文本及其原文上下文发送给 LLM。
- API Key 保存在本机应用私有存储中，不写死在代码里。
- 笔记、缓存和单词本保存在本地 SharedPreferences 中。

注意：如果使用第三方 LLM API，文本会发送到对应 API 服务商。请自行选择可信服务。

## 项目结构

```text
app/src/main/java/com/example/xjapanesefuriganaoverlay/
├── MainActivity.kt
├── NotesActivity.kt
├── WordbookActivity.kt
├── AddWordActivity.kt
├── accessibility/
│   └── XTextAccessibilityService.kt
├── data/
│   ├── CurrentPostRepository.kt
│   ├── FuriganaCache.kt
│   ├── NoteRepository.kt
│   ├── SettingsRepository.kt
│   └── WordbookRepository.kt
├── furigana/
│   ├── FuriganaAnnotationCodec.kt
│   ├── FuriganaClient.kt
│   ├── FuriganaJsonParser.kt
│   ├── FuriganaModels.kt
│   ├── FuriganaPromptBuilder.kt
│   ├── JapaneseNumberReading.kt
│   ├── RubyAnnotationExtractor.kt
│   └── RubyHtmlRenderer.kt
├── japanese/
│   └── JapaneseTextDetector.kt
├── overlay/
│   └── OverlayController.kt
└── util/
    └── TextHash.kt
```

关键资源文件：

```text
app/src/main/AndroidManifest.xml
app/src/main/res/xml/accessibility_service_config.xml
app/src/main/res/layout/activity_main.xml
```

## 核心模块说明

### MainActivity

负责权限状态展示、API 配置、目标包名配置、服务启停、笔记和单词本入口。

### XTextAccessibilityService

监听目标 App 的窗口变化事件，通过 `rootInActiveWindow` 递归读取当前屏幕文本节点，筛选疑似日文 post，并写入内存仓库。

### OverlayController

负责悬浮按钮和悬浮面板。悬浮面板支持显示当前 post 列表、注音结果、复制、加词、关闭、拖动和调整大小。

### FuriganaClient

使用 OkHttp 调用 OpenAI-compatible Chat Completions API。支持：

- post 注音
- 手动选区读音识别
- JSON mode 兼容重试
- 网络失败和 JSON 解析失败处理

### FuriganaPromptBuilder

构建 LLM prompt。post 注音使用候选 id 协议，减少模型输出体积。手动选区读音识别会提供完整原文、选区范围和选中文本。

### FuriganaJsonParser

解析 LLM 返回结果，并校验读音合法性。对标准数字表达式会尝试本地修正读音。

### JapaneseNumberReading

负责常见数字表达式读音，例如：

```text
2025年 -> にせんにじゅうごねん
7月1日 -> しちがつついたち
12時30分 -> じゅうにじさんじゅっぷん
```

### RubyHtmlRenderer

将原文和注音结果渲染为完整 HTML ruby 页面，用 WebView 显示。

## 当前限制

- AccessibilityService 获取到的文本质量取决于 X / Twitter 当前 UI 结构。
- X / Twitter 改版可能影响识别结果。
- 悬浮窗不会把 ruby 精确叠到原 App 文本位置上，而是在独立悬浮面板中显示结果。
- LLM 读音质量取决于模型能力和 API 响应质量。
- 数字读音规则只覆盖常见年份、日期、时间、数量场景，不是完整日语数词系统。
- 缓存、笔记、单词本目前使用 SharedPreferences，适合 MVP 和个人使用，不适合大量数据。
- API Key 存在应用私有存储中，但没有额外加密。

## 开发命令

构建 debug APK：

```powershell
.\gradlew.bat :app:assembleDebug
```

运行 lint：

```powershell
.\gradlew.bat :app:lintDebug
```

清理构建：

```powershell
.\gradlew.bat clean
```

## 安全边界

本项目明确不做以下事情：

- 不 root。
- 不使用 Xposed / LSPosed。
- 不逆向 X / Twitter。
- 不修改 X / Twitter APK。
- 不绕过 Android 权限模型。

## 后续改进方向

- 使用 Room 替代 SharedPreferences 管理笔记、缓存和单词本。
- 为 API Key 增加 Android Keystore 加密。
- 增加更完整的数字、数量词和日期读音规则。
- 增加导出笔记和单词本功能。
- 增加可选 OCR 识别路径，处理无障碍节点拿不到完整文本的场景。
- 优化悬浮面板 UI 和移动端长文本阅读体验。
- 为核心解析和数字读音逻辑添加单元测试。

