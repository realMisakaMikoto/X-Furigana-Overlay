# SOS Furigana 団

<img src="./SOS.webp" alt="SOS Furigana 団" width="96">

Android 悬浮工具，给 X / Twitter 当前屏幕上的日文内容标注假名读音，并把注音结果串成笔记、语法和词汇复习。

用无障碍服务读取当前屏幕文本，在本地筛出疑似日文的候选。点悬浮的 `SOS` 图标后，面板列出候选内容，你选一条，才会调用自己配置的 OpenAI-compatible API 生成注音。

流程：

1. 打开 X / Twitter。
2. 点屏幕上的 `SOS` 悬浮按钮。
3. 从候选列表里选一条日文内容。
4. 模型按语境给汉字标平假名。
5. 结果自动存进笔记，可以复制、看句子结构，或选词加进单词本。
6. 单词本里补释义、按计划复习，或导出 Anki TSV。

演示：<img src="./基本使用gif.gif" alt="基本使用演示" width="280">

## 功能

| 功能 | 实现 |
| --- | --- |
| 当前屏幕识别 | `AccessibilityService` 读目标 App 的屏幕文本 |
| 主动扫描 | 点悬浮按钮时主动识别当前屏幕，不等旧事件刷新 |
| 悬浮按钮 | `WindowManager` + `TYPE_APPLICATION_OVERLAY`，可拖动 |
| 结果面板 | 可移动、可调整大小、提示条可拖动 |
| 日文筛选 | 过滤 X / Twitter 的界面文案，优先保留含汉字和假名的内容 |
| 注音 | 调 OpenAI-compatible Chat Completions API 生成平假名读音 |
| Ruby 渲染 | WebView 里用 HTML `<ruby><rt>` 显示 |
| 汉字和数字 | 候选生成与 prompt 约束尽量覆盖原文里的汉字、数字；数字读音结合日期、年份、时间上下文处理 |
| 送り仮名 | 避免 `長持ち → ながもちち` 这类重复读音 |
| 多套 API | 保存多组地址 / Key / Model 并切换 |
| 缓存与词汇库 | 同一内容与模型的结果会缓存，确认过的读音用于后续本地推断 |
| 笔记 | 注音成功或缓存命中后自动保存，可查看、搜索、按时间筛选 |
| 句子结构 | 标注主题、主语、宾语、谓语等角色；默认后台分析，可关掉或手动触发，结果会缓存 |
| 单词本 | 从注音原文选词，支持 JLPT 等级筛选、词条详情、收藏、标签和汉字/假名搜索 |
| 词汇补全 | 加词后在后台补简体中文释义、JLPT 等级和词性，失败可手动重试 |
| 词条校订 | 读音、释义、词性和 JLPT 的修改要经当前模型结合原句核验；模型给的标签建议需用户确认后才保存 |
| 复习 | 语境填空：显示完整原句并用荧光标出目标词，只填符合语境的读音；自动评分，按 1/3/7/14/30/60/120/240 天阶梯排期 |
| Anki 导出 | 导出含词面、读音、释义和原句的 TSV |
| 检索 | 笔记和单词本都能用汉字或对应假名搜 |
| 导航 | 底部固定团部 / 笔记 / 单词 / 设置四区，复习作为全屏学习任务单独进入 |

首页的 GIF 可以点，点多了团长会出来说话。

## 不做什么

- 不 root，不用 Xposed / LSPosed。
- 不 OCR，不逆向 X / Twitter，不改它的 APK。
- 不批量上传当前屏幕文本或候选列表。
- 不读没配成目标包名的其他 App 内容。
- 不把 API Key 写死在代码里。

## 构建和安装

Windows：

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS / Linux：

```bash
./gradlew :app:assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次打开后按提示开启无障碍服务和「显示在其他应用上层」权限。

## 配置 API

底部导航进「设置」，填 API 地址、API Key、Model。支持 OpenAI-compatible Chat Completions API，地址可以填 Base URL 或完整地址：

```text
https://api.openai.com/v1
https://api.openai.com/v1/chat/completions
```

## 用法

打开 X / Twitter，点 `SOS` 悬浮按钮，等当前屏幕的日文内容列表出现，点要注音的那条，看 ruby 结果。结果自动进笔记，在笔记里看句子结构，在单词本里收藏、打标签、进词条详情或启动读音复习。复习时按原句里荧光标出的词填读音；答案和本地记录不一致时，应用会调当前模型做语境核验。

## 权限与数据

无障碍权限用于读取其他 App 的屏幕文本，普通 Android App 没有别的方式拿到这些内容。

- 默认只处理 `com.twitter.android`、`com.x.android` 的窗口内容，目标包名可以在 App 里改。
- 当前屏幕的节点文本只用于本地筛候选，候选列表只存在当前进程里。
- 你点了某条候选，那条原文才会发到当前选中的 API 配置去生成注音。
- 注音成功或缓存命中会存进笔记。若「自动分析句子结构」开着（默认开），笔记原文也会发到同一个 API；可以在设置里关掉，改成在笔记详情里手动触发。
- 加单词时如果本地判断不出选区读音，会发送完整原文、选中文字和选区位置请求读音。保存后在后台发送词面、读音和原句前 200 个字符，用于补中文释义、JLPT 等级和词性。
- 搜索、时间筛选、常规复习调度和 Anki TSV 导出都在本地做。只有复习答案不是已记录读音时，才把原句、目标词和答案发给当前模型复核，通过后缓存为该词的可接受读音。
- 编辑词条的读音、释义、词性或 JLPT 时，会把修改内容和原句发给当前模型核验，核验失败或 API 不可用时不会保存。标签推荐也会调模型，但必须由你确认才保存。
- API 配置、缓存、笔记、句子结构结果、本地词汇库、单词本和复习进度都存在应用私有的 `SharedPreferences`，已排除在 Android 云备份和设备迁移之外。
- API Key 不在代码里，存在应用私有 `SharedPreferences`，目前没有额外加密。
- 所有模型请求都发往你当前选的 OpenAI-compatible API，项目本身不含统计或广告 SDK。

## 技术栈

```
Kotlin
Android View / XML
AccessibilityService
WindowManager TYPE_APPLICATION_OVERLAY
WebView
OkHttp
Kotlin Coroutines
SharedPreferences
Gradle Kotlin DSL
JUnit 4
```

## 版本与验证

当前版本 `1.0.8`，最低 Android 8.0（API 26）。

69 个本地逻辑测试，覆盖 JLPT 等级筛选、读音候选与选区解析、Ruby 渲染、模型 JSON 校验、句子结构渲染、语境答案归一化和复习调度。

```
# Windows
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug

# macOS / Linux
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

仓库里没有 `app/src/androidTest`，真机上的无障碍识别、悬浮窗交互和真实 API 链路需要人工验证。

## 代码结构

```text
app/src/main/java/com/sosdanfurigana/
├── MainActivity.kt / NotesActivity.kt / WordbookActivity.kt / ReviewActivity.kt / SettingsActivity.kt
├── accessibility/   屏幕文本扫描、推文识别管线、扫描指标
├── data/            笔记、缓存、词汇库、单词本、设置、复习调度
├── furigana/        注音、语法分析、词汇补全、词条核验、Ruby 渲染
├── japanese/        日文文本检测与汉字/假名检索
└── overlay/         悬浮窗控制
```

## 已知限制

- 无障碍节点来自 X / Twitter 当前的界面结构，页面改版会影响识别效果。
- 注音速度取决于你配的服务商、模型和网络。
- 人名、地名、网络语和熟字训仍可能要人工确认。
- 句子结构、中文释义、JLPT 等级和词性由模型生成，可能不准。
- 自动句子结构分析默认开着，会产生额外请求和 token 消耗，可在设置里关。
- 语境读音核验和词条校订依赖当前 API；网络或模型不可用时，未核验的答案不会写进可接受读音，词条修改也不会保存。
- 选词读音优先用已注音结果里的 hints，本地判断不出才调 LLM。
- 悬浮窗在不同 ROM 上可能有权限和显示差异。
- 没有真机 UI、无障碍和悬浮窗的端到端自动化测试。

## 素材

- `SOS.webp`：应用图标和悬浮按钮图标。
- `haruhi-gif/`：首页互动 GIF。
- `基本使用gif.gif`：使用演示。

图片素材来自 **凉宫春日应援团**。情绪价值支持来自 **凉宫春日黑客松 Galcode 项目**。

## 许可

MIT License。
