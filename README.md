# <应用名称>

<!--
  ============================================================================
  首次推送之前 —— 把下面这三处占位符换成真实内容，然后删掉这段注释。
  一份仍然写着「<应用名称>」的 README，是让项目看起来被放弃最快的办法。
  ============================================================================
-->

> **<一句话说明这个应用做什么、给谁用>**

<!--
  界面做出来之后，在这里放 2–4 张真实截图。这是访客决定要不要继续读下去时
  最强的单一信号。
  <p align="center">
    <img src="docs/screenshots/home.png" width="30%" />
  </p>
-->

## 功能

- <功能一>
- <功能二>
- <功能三>

## 截图

_等第一个可用版本出来之后补。_

## 环境要求

| 工具 | 本项目使用的版本 |
| --- | --- |
| JDK | 17（Gradle 工具链） |
| Android SDK Platform | 36（`compileSdk` / `targetSdk`） |
| 最低 Android 版本 | 24（`minSdk`） |
| Gradle | 9.1 |
| Android Gradle Plugin | 9.0.1 |
| Kotlin | 2.3.20 |

## 构建

```bash
git clone https://github.com/KAYOYAK/<REPO_NAME>.git
cd <REPO_NAME>

# 告诉 Gradle 你的 SDK 在哪里。这个文件是本机专用的，已加入 gitignore。
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 调试构建
./gradlew assembleDebug

# 单元测试
./gradlew testDebugUnitTest

# Lint
./gradlew lintDebug
```

调试 APK 会生成在 `app/build/outputs/apk/debug/app-debug.apk`。

Windows 上把 `./gradlew` 换成 `gradlew.bat`。

### 仪器化测试

它们需要一台设备或模拟器，而且设备上要有一个模型。下面这个脚本跑完之后不会把模型弄丢：

```powershell
./scripts/run-instrumented-test.ps1
./scripts/run-instrumented-test.ps1 -Class com.kayoyak.demoapp.inference.LiteRtLlmEngineTest
./scripts/run-instrumented-test.ps1 -ModelPath D:\path\to\another.litertlm
```

`gradlew connectedDebugAndroidTest` 是 Gradle 自带的那条路，它跑完会卸载应用 —— 应用私有目录里的模型也跟着一起没掉，所以那条路没法在不重新推一次模型的情况下跑第二遍。这个脚本直接驱动同一套 instrumentation：构建、安装两个 APK 且不卸载、先启动一次应用（它的 `filesDir` 是懒创建的）、如果模型还不在就复制一个进去，然后对你指定的测试类跑 `am instrument`。

引擎测试先看应用自己的 `files/models` 目录，找不到就退回 `/data/local/tmp/models` 里的任意 `*.litertlm` 文件，所以手动放进去一个模型就够了：

```bash
adb shell mkdir -p /data/local/tmp/models
adb push Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm /data/local/tmp/models/
```

找不到模型的测试会说明这一点然后返回，而不是失败；它打印的提示会写清用的是哪个文件。

没有流水线跑这一组测试。一个测试全部被跳过的模拟器任务，和一个测试全部通过的任务，报的都是绿色；而目录里最小的模型是 344 MB —— 所以当初的选择是：一个不可能诚实地失败的任务，或者干脆没有任务。

### 发布构建

发布签名是**故意**不配置在这个仓库里的。在本地提供你自己的 keystore，不要把它提交上去：

```bash
./gradlew assembleRelease \
  -Pandroid.injected.signing.store.file=/path/to/release.jks \
  -Pandroid.injected.signing.store.password="$STORE_PASSWORD" \
  -Pandroid.injected.signing.key.alias="$KEY_ALIAS" \
  -Pandroid.injected.signing.key.password="$KEY_PASSWORD"
```

绝不要提交 `.jks` 文件或它的密码。丢掉 keystore 意味着你再也无法给已经上架的应用发更新 —— 签名身份是换不掉的。

## 项目结构

```
app/src/main/java/...   应用源码（Jetpack Compose）
app/src/main/res/       资源：字符串、主题、drawable、图标
app/src/test/           JVM 单元测试，由 `testDebugUnitTest` 运行
app/src/androidTest/    仪器化测试，需要设备或模拟器
gradle/libs.versions.toml   依赖版本的唯一来源
```

## 隐私

<!--
  如果这个应用收集或传输任何东西，在这里说明，并在上架前补一份隐私政策。
  如果它确实什么都不收集，也要明确写出来 —— 这是一个卖点。
-->

这个应用什么都不收集。没有分析、没有崩溃上报、没有遥测，也没有账号。网络只用来下载模型，别的什么都不做；模型到了设备上之后，把网络关掉它照样工作。

完整细节，以及让这些说法保持为真的那些检查：[PRIVACY.md](PRIVACY.md)。

## 项目原则

三条约束不可谈判，并且约束每一次改动 —— 法律、隐私、开源可验证性。每一条都由一个会让构建失败的脚本强制执行，而不是靠文档里的一句承诺。

**[docs/PRINCIPLES.md](docs/PRINCIPLES.md)**

| 约束 | 由谁执行 |
| --- | --- |
| 许可证 | `scripts/check-licenses.ps1` |
| 隐私契约 | `scripts/check-privacy.ps1` |
| 打进发布 AAR 里的第三方声明 | `scripts/check-embedded-notices.ps1` |
| 法律声明与署名 | `scripts/check-legal-docs.ps1` |
| 文案集中在一处，并使用应用的语言 | `scripts/check-ui-copy.ps1` |
| 每个屏幕回调都在代码里接上了 | `scripts/check-ui-wiring.ps1` |
| 改过词的功能没有留下废弃文案 | `scripts/check-unused-strings.ps1` |
| 署名准确 | `.github/workflows/compliance.yml` |

## 语言

**这个应用是中文的，面向中文使用者。** 没有翻译层，也没有做翻译层的计划：文案就在 `app/src/main/res/values/strings.xml` 这一个文件里，而目录提供的每一个模型都是能进行中文对话的。

最后这一条是正确性要求，不是偏好。这个应用的第一个版本把一个只会英文的 360M 模型放在列表最上面，一个中文问题回来的是一本正经的英文，说的还是别的事 —— 那读起来像应用坏了，而不是「模型选错了」。现在每个条目都记录它能用什么语言对话，列表会显示出来，而且 `ModelCatalogue.validate()` 会拒绝一个第一个（默认）模型不会中文的目录。

**目录里只放中文模型。** 别处发布的模型可能很优秀，但在这里仍然是错误的选择：它可能不能进行中文对话，它的主机可能在中国大陆不可达，它的发布者对它在那边能不能用毫无利害关系。`validate()` 同样会拒绝第一个下载源是 `huggingface.co` 的目录，应用也出于同样的原因先试镜像。列表从 329 MB 起步，这样存储吃紧的设备也能用这个应用。

源码注释保持英文 —— 这是代码库的约定，也是外部贡献者读的东西；这份 README 用中文，因为它是给使用者看的。这是关于贡献者的决定，不是关于使用者的决定。

## 它能做什么

和一个完全跑在手机上的模型进行文字对话。

- **模型。** 下载、从文件导入、删除。下载可续传，并按记录的摘要校验；先试镜像，因为这个应用面向的那个国家里，发布者的主机是被墙的。
- **对话。** 流式回复、中途停止、重新生成回答、删除一条消息或一整轮对话、编辑最后一个问题再问一遍。代码块、标题、列表、引用和行内强调都会渲染；一条回复可以整条复制，也可以一次只复制一个代码块。每条消息都带发送时间，回复带它花了多久、每秒多少 token —— 这才让两个模型可比。
- **对话内搜索。** 长回复正是「想再把某个词找出来」变得重要的场合。匹配数量会显示出来，所以一段被截短的记录永远不会读起来像丢了消息。
- **历史。** 跨标题和消息正文搜索、重命名、删除、置顶，以及把一段对话当作文本分享出去。每一行都显示它占多少空间。
- **备份。** 把所有对话导出成一个文件保存，也可以再导入回来。这个应用把一切都留在本地，也声明不做云备份，所以这是对话能活过换手机的唯一方式：「不同步」不能等于「搬不走」，否则隐私就变成了笼子。
- **从别的应用分享进来的文字。** 在这里分享一段文字，它会进到输入框里等你编辑再发。它不会被自动发送 —— 发不发仍然是用户的动作。
- **设置。** 用哪个处理器、系统提示词、回复长度、temperature、浅色/深色/跟随系统外观，以及文字大小。内置五条指令作为起点，你自己的措辞可以起个名字保存下来复用 —— 凭记忆复现一条好指令，是没人会做的事。
- **关于。** 什么在跑、什么不会离开设备、以及怎么把一切抹掉。

## 免责声明与条款

这个应用在你的设备上跑一个语言模型，把它说的话显示给你。没有人审过那些输出，中间也没有服务器可以审 —— 让你的对话保持私密的那个性质，同时也意味着这里没有任何东西可以被审核。

**它不是专业建议，绝不能用它做医疗、法律、财务或安全方面的决定。** 它跑的模型小到能装进手机，会很有把握地说错话。

- **[DISCLAIMER.md](DISCLAIMER.md)** —— 担保、责任、输出是什么，以及你要为什么负责。
- **[docs/legal/TRADEMARKS-AND-CLAIMS.md](docs/legal/TRADEMARKS-AND-CLAIMS.md)** —— 与任何模型发布者无关联、本项目指名提到的商标、它不分发什么，以及怎么提出侵权主张。

这两份文件都由 `scripts/check-legal-docs.ps1` 强制：任何一份缺失、没有被链接，或者不再为目录里点名的发布者署名，构建就会失败 —— 如果应用自己不再显示免责声明，也会失败。应用会离线显示一份，在**模型 → 关于**里，因为挂在网站上的文档，从它描述的那台设备上是够不到的。

## 参与贡献

欢迎报告问题和提交 PR。见 [CONTRIBUTING.md](CONTRIBUTING.md)。
安全问题请按 [SECURITY.md](SECURITY.md) 里说的私下报告。

## 许可证

[MIT](LICENSE) © 2026 KAYOYAK

<!--
  趁仓库还年轻换许可证很容易，一旦有了外部贡献就很难了。如果你更想要：
    Apache-2.0  —— 增加明确的专利授权；在 Android 生态里很常见（AOSP 本身就是）。
                   如果你以后可能想要企业贡献者，推荐这个。
    GPL-3.0     —— copyleft：衍生作品必须保持开源。如果你想要这个就用它。
  替换 LICENSE，然后更新这一节和下面的徽章。
-->

[![CI](https://github.com/KAYOYAK/<REPO_NAME>/actions/workflows/ci.yml/badge.svg)](https://github.com/KAYOYAK/<REPO_NAME>/actions/workflows/ci.yml)

## 已经做了什么，接下来做什么

[docs/ROADMAP.md](docs/ROADMAP.md) 列出了这个项目决定要做的、决定不做的、以及还没决定的每一项 —— 一行一项，并写明它基于什么。
