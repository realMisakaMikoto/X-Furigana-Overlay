# SOS Furigana 団

<p align="center">
  <img src="./SOS.webp" alt="SOS Furigana 団" width="128">
</p>

<p align="center">
  <strong>给 X / Twitter 当前屏幕日文内容加注音，并串起笔记、语法与词汇复习的 Android 悬浮工具</strong>
</p>

<p align="center">
  <a href="https://github.com/realMisakaMikoto/X-Furigana-Overlay">
    <img alt="GitHub Repo" src="https://img.shields.io/badge/GitHub-X--Furigana--Overlay-181717?style=for-the-badge&logo=github">
  </a>
  <img alt="Android" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-View%2FXML-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white">
  <img alt="No Root" src="https://img.shields.io/badge/No-Root%20%2F%20Xposed-FFCF33?style=for-the-badge">
</p>

<p align="center">
  <em>听好了！既然日文内容里到处都是汉字，那在汉字上方出现假名就是理所当然的事。不是为了阿虚你才做的，只是本团长看不下去而已！</em>
</p>

---

## 団長命令

`SOS Furigana 団`，也就是这个仓库里的 `X-Furigana-Overlay`，是一款面向中文母语日语学习者的 Android 自用工具。

它会在你打开 X / Twitter 时，通过无障碍服务读取当前屏幕的文本，只在本地筛选疑似日文内容。你点击悬浮的 `SOS` 图标后，面板会列出当前屏幕识别到的候选内容；再由你亲自选择其中一条，才会调用你配置的 OpenAI-compatible API 生成 furigana。

简单说：

- 打开 X / Twitter。
- 点一下悬浮的 `SOS` 图标。
- 选择一条日文内容。
- 让模型根据语境给汉字标平假名。
- 结果自动保存到笔记；你可以复制、查看句子结构，或者选词加入单词本。
- 在单词本里补充释义、按计划复习，或者导出 Anki TSV。

哼，这才像个能让世界热闹起来的工具嘛。

---

## 演示

<p align="center">
  <img src="./基本使用gif.gif" alt="基本使用演示" width="340">
</p>

---

## 现在能做什么

| 功能 | 団長说明 |
| --- | --- |
| 当前屏幕识别 | 使用 `AccessibilityService` 读取目标 App 当前屏幕文本 |
| 主动扫描 | 点击悬浮按钮时主动识别当前屏幕，减少等待旧事件刷新的时间 |
| 悬浮 SOS 按钮 | `WindowManager` + `TYPE_APPLICATION_OVERLAY`，可拖动 |
| 可移动结果面板 | 悬浮面板支持移动、调整大小和拖动提示条 |
| 日文内容筛选 | 过滤 X / Twitter UI 文案，优先保留含汉字和假名的日文内容 |
| LLM 注音 | 调用 OpenAI-compatible Chat Completions API 生成平假名读音 |
| Ruby 渲染 | 使用 HTML `<ruby><rt>` 在 WebView 中显示注音 |
| 全汉字倾向标注 | 候选生成和 prompt 约束会尽量覆盖原文中的汉字与数字 |
| 送り仮名处理 | 尽量避免 `長持ち -> ながもちち` 这种重复读音问题 |
| 数字读音 | 结合日期、年份、时间等上下文处理数字读音 |
| 多 API 配置 | 可以保存多个 API 地址 / Key / Model 组合并切换 |
| 缓存与本地词汇库 | 同一内容和模型的结果会缓存，已确认读音会用于后续本地推断 |
| 笔记自动归档 | 注音成功或缓存命中后自动保存，可查看、搜索和按时间筛选 |
| 句子结构分析 | 对笔记标注主题、主语、宾语、谓语等语法角色；默认后台分析，可关闭或手动触发，结果会缓存 |
| 四区主导航 | 底部固定“团部 / 笔记 / 单词 / 设置”，复习作为全屏学习任务单独进入 |
| 单词本 | 从注音原文选词；支持独立词条详情、收藏、标签和汉字/假名搜索 |
| 词汇信息补全 | 加词后后台补充简体中文释义、JLPT 等级与词性，失败后可手动重试 |
| 词条校订 | 读音、释义、词性和 JLPT 的修改需经当前模型结合原句核验；模型标签建议由用户确认后保存 |
| 语境填空复习 | 显示完整原句并用荧光标出目标词，只填写符合语境的读音；自动评分并沿用 1/3/7/14/30/60/120/240 天复习阶梯 |
| Anki 导出 | 导出含词面、读音、释义和原句的 TSV 文件，供 Anki 手动导入 |
| 汉字/假名互搜 | 笔记和单词本支持用汉字或对应假名检索 |
| GitHub 入口 | 首页右上角 GitHub 图标可直接打开项目仓库 |
| 凉宫互动 | 首页 GIF 可以戳，戳多了团长会亲自训话 |

---

## 不许做的事

本项目明确不做这些事：

- 不 root。
- 不使用 Xposed / LSPosed。
- 不 OCR。
- 不逆向 X / Twitter。
- 不修改 X / Twitter APK。
- 不批量上传当前屏幕文本或候选列表。
- 不读取未配置为目标包名的其他 App 内容。
- 不把 API Key 写死在代码里。

要是敢乱读别人的 App，本团长可是会发火的。

---

## 使用方法

### 1. 构建 APK

Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS / Linux:

```bash
./gradlew :app:assembleDebug
```

生成位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 2. 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. 开启权限

打开 `SOS Furigana 団` 后，按提示开启：

- 无障碍服务权限
- 显示在其他应用上层权限

### 4. 配置 API

从底部导航进入“设置”，填写或新增 API 配置：

- API 地址
- API Key
- Model

支持 OpenAI-compatible Chat Completions API。API 地址可以填写 Base URL，也可以填写完整的 Chat Completions 地址：

```text
https://api.openai.com/v1
https://api.openai.com/v1/chat/completions
```

### 5. 开始注音

1. 打开 X / Twitter。
2. 点击屏幕上的 `SOS` 悬浮按钮。
3. 等候当前屏幕日文内容列表出现。
4. 点击要注音的内容。
5. 查看 ruby 注音结果。
6. 结果会自动保存到笔记；你可以复制结果或选词加入单词本。
7. 在笔记中查看句子结构，在单词本中收藏、加标签、进入词条详情，或启动语境读音复习。
8. 复习时根据完整原句中荧光标出的词填写读音；答案与本地记录不一致时，应用会调用当前模型进行语境核验。

---

## 权限与隐私

本工具需要无障碍权限，是因为普通 Android App 没有读取其他 App 屏幕文本的常规 API。

处理原则如下：

- 默认只处理 `com.twitter.android`、`com.x.android` 的窗口内容；目标包名可在 App 内配置。
- 当前屏幕节点文本只用于本地筛选候选内容，候选列表只保存在当前应用进程中。
- 用户点击某条候选内容后，该条原文会发送到当前选中的 API 配置，用于生成注音。
- 注音成功或缓存命中后会自动保存笔记。若“自动分析句子结构”处于开启状态（默认开启），笔记原文还会发送到同一 API；可关闭该开关，改为在笔记详情中手动触发。
- 添加单词时，如果本地无法确定选区读音，会发送完整原文、选中文字和选区位置请求读音。保存单词后，会在后台发送词面、读音及原句前 200 个字符，用于补充中文释义、JLPT 等级和词性。
- 搜索、时间筛选、常规复习调度和 Anki TSV 导出均在本地完成；仅当复习答案不是已记录读音时，才把原句、目标词和答案发送给当前模型做二次核验，核验通过后缓存为该词的可接受读音。
- 用户编辑词条的读音、释义、词性或 JLPT 时，会把修改内容与原句发送给当前模型核验；核验失败或 API 不可用时不会保存。标签推荐也会调用模型，但必须由用户确认后才保存。
- API 配置、缓存、笔记、句子结构结果、本地词汇库、单词本和复习进度保存在应用私有 `SharedPreferences` 中，并排除在 Android 云备份与设备迁移之外。
- API Key 没有写死在代码中；它保存在应用私有 `SharedPreferences`，当前未额外加密。
- 所有模型请求都发送到用户当前选择的 OpenAI-compatible API；项目本身不内置统计或广告 SDK。

这不是可疑工具，这是团长批准过的日文学习装备。

---

## 技术栈

```text
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

---

## 当前版本与验证

- 当前版本：`1.0.7`，最低支持 Android 8.0（API 26）。
- 本地逻辑测试：66 个，覆盖读音候选与选区解析、Ruby 渲染、模型 JSON 校验、句子结构渲染、语境答案归一化和复习调度。
- 验证命令：Windows 使用 `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`，macOS / Linux 使用 `./gradlew :app:testDebugUnitTest :app:assembleDebug`。
- 当前没有 `app/src/androidTest`；真机上的无障碍识别、悬浮窗交互和真实 API 链路仍需人工验证。

---

## 项目结构

```text
app/src/main/java/com/sosdanfurigana/
├── MainActivity.kt
├── NotesActivity.kt
├── NoteDetailActivity.kt
├── WordbookActivity.kt
├── WordDetailActivity.kt
├── AddWordActivity.kt
├── ReviewActivity.kt
├── SettingsActivity.kt
├── AppBottomNavigation.kt
├── SelectedReadingResolver.kt
├── AppUi.kt
├── accessibility/
│   ├── XTextAccessibilityService.kt
│   ├── ScreenTextScanner.kt
│   ├── PostDetectionPipeline.kt
│   └── ScanMetrics.kt
├── data/
│   ├── CurrentPostRepository.kt
│   ├── FuriganaCache.kt
│   ├── FuriganaLexiconRepository.kt
│   ├── NoteRepository.kt
│   ├── GrammarAnalysisFetcher.kt
│   ├── SettingsRepository.kt
│   ├── WordbookRepository.kt
│   ├── WordMeaningFetcher.kt
│   ├── ReviewScheduler.kt
│   └── ReadingReviewLogic.kt
├── furigana/
│   ├── FuriganaClient.kt
│   ├── FuriganaJsonParser.kt
│   ├── FuriganaPromptBuilder.kt
│   ├── GrammarAnalysisClient.kt
│   ├── GrammarHtmlRenderer.kt
│   ├── WordMeaningClient.kt
│   ├── WordVerificationClient.kt
│   ├── ReadingAnswerVerifier.kt
│   ├── JapaneseNumberReading.kt
│   ├── RubyHtmlRenderer.kt
│   └── ...
├── japanese/
│   ├── JapaneseTextDetector.kt
│   └── JapaneseSearch.kt
└── overlay/
    └── OverlayController.kt
```

---

## 素材

项目使用了凉宫主题视觉元素：

- `SOS.webp`：应用图标与悬浮按钮图标。
- `haruhi-gif/`：首页互动 GIF 素材。
- `基本使用gif.gif`：基础使用演示。

特别鸣谢 **凉宫春日应援团** 提供图片素材。

再特别鸣谢 **凉宫春日黑客松 Galcode 项目** 提供情绪价值支持。没有这种程度的精神补给，阿虚肯定又要磨磨蹭蹭。

---

## Star History

既然都看到这里了，点个 Star 也是很合理的吧？才、才不是本团长想要，只是为了让更多人发现这个项目而已！

<p align="center">
  <a href="https://www.star-history.com/#realMisakaMikoto/X-Furigana-Overlay&Date">
    <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=realMisakaMikoto/X-Furigana-Overlay&type=Date">
  </a>
</p>

---

## 已知限制

- 无障碍节点来自 X / Twitter 当前 UI，页面结构变化可能影响识别效果。
- LLM 响应速度取决于你配置的服务商、模型和网络。
- 人名、地名、网络语、熟字训仍可能需要人工确认。
- 句子结构、中文释义、JLPT 等级和词性由模型生成，可能不准确。
- 自动句子结构分析默认开启，会产生额外请求与 token 消耗；可在“设置”中关闭。
- 语境读音核验和词条校订依赖当前 API；网络或模型不可用时不会把未核验答案写入可接受读音，也不会保存词条修改。
- 选词读音会优先使用已注音结果里的 reading hints，本地无法确定时才会调用 LLM。
- 悬浮窗在不同系统 ROM 上可能有权限和显示差异。
- 当前缺少真机 UI、无障碍与悬浮窗端到端自动化测试。

这些问题不是借口，是下一次团长命令的素材。

---

## License

MIT License。

既然已经这么自由了，就给我拿去做点能让世界更热闹的东西。否则，罚金。
