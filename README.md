# SillyTavern Manager

**SillyTavern Manager（STM）** 是一个独立实现的 Android 管理应用。

STM 不是 SillyTavern 官方项目，也不隶属于 SillyTavern 项目团队。

本地保存的 SillyTavern 官方源码及其他第三方研究资料仅用于开发参考，不属于本产品仓库，也不会进入产品 Git 历史。

## 当前状态

当前开发版本为 **v0.0.1**，已经包含首批可实际操作的管理功能，以及阶段 2 的安装前验证基础设施：

- Kotlin 与 Jetpack Compose 界面；
- Android 12（API 31）及以上；
- 应用标识 `io.github.styx798.sillytavernmanager`；
- 通过左侧边缘手势和三线按钮打开的抽屉导航；
- 主页、全屏 ST 入口、ST 管理、日志和设置五个基础页面；
- 展示固定官方 GitHub 来源及下载、Core 安装、预检和删除流程的 ST 管理界面；
- 优先通过 GitHub REST API 将官方 `release` 正式版或 `staging` 预览版解析为精确 commit；REST 不可用时，回退到严格解析 Git Smart HTTP `git-upload-pack` ref advertisement，再通过 Android 系统下载器获取绑定该 commit 的源码 ZIP，支持进度、取消和删除；
- 下载完成后持久记录精确 commit、归档 URL、字节长度和内容 SHA-256；当前来源没有签名目录，信任等级明确记录为降级的未签名目录，而不是把内容哈希等同于发布者签名；
- 由用户手动将已记录的归档通过只读 `ParcelFileDescriptor` 发送到 STM Core，选择完整安装或仅执行 `VERIFY` 预检；Core 独立复核官方仓库与精确 commit 身份、普通文件描述符、长度及 SHA-256；
- Core 使用带路径、链接、冲突、深度、数量和容量限制的安全 ZIP 解压流程，并从解压后的真实 SillyTavern 源码中提取归档根目录、版本、Node 要求、`package-lock.json` SHA-256 及许可证证据；
- 成功预检的证据作为 `VERIFY` job 的验证回执写入 Core 检查点，并在日志页与其他 Core 任务、内部 Slot 和 Manifest 技术记录统一查看；ST 管理页只保留来源、下载缓存和可管理的真实 ST 版本；
- 可从日志页通过 Android 系统文件选择器导出有容量上限的故障诊断文本，内容覆盖设备、WebView、Core、Slot、任务、STM 事件和近期 SillyTavern Node 日志，并对常见凭据字段执行脱敏；报告仍可能包含本机路径和技术信息，分享前应由用户检查；
- 从设置页进入 STM 文件管理器，浏览应用内部/外部数据目录，编辑不超过 1 MB 的常见文本文件，并在确认后删除文件或目录；STM Core 保留目录不会暴露给通用文件管理器；
- 可持久保存的系统/浅色/深色主题，以及系统/简体中文/English 语言选项；
- 可替换的 STM Core 控制、版本目录和日志接口；
- STM Core 0.1.0 与内部 Feather Engine，使用 Javet 5.0.9 内置的 Node.js 24.17.0；
- 独立 `:stm_core` 私有进程、一次性 Feather Engine session 和固定 Node 控制线程；
- 没有可运行活动槽位时使用合成健康检查；存在已验证活动槽位时从不可变程序树启动真实 SillyTavern，并将配置、用户数据、临时文件和日志路由到槽位之外；
- 真实 ST 只监听 `127.0.0.1`，启动门槛实际请求 `/version` 并核对固定版本；停止后释放端口并重新扫描完整不可变槽位；
- App 内 WebView 只加载 Core 当前报告的精确 IPv4 loopback origin，不暴露 JavaScript bridge，禁用文件、content 和 mixed-content 访问，并阻止主页面离开当前本机会话；
- 带 revision、operation ID、进程与 session 身份的单一状态快照，以及启动、停止、检查点恢复、持久恢复就绪门控和进程异常退出识别；
- 仅发布 `arm64-v8a` 原生库，并在 APK 中提供 Javet、Node.js、V8、Apache Commons Compress、Bouncy Castle 及其运行时依赖的许可证材料。

当前 debug 实现已能从产品入口把已下载的精确官方源码归档交给 Core：在私有 staging 中使用 APK 内置的固定 npm CLI 初始化依赖、设备本地生成并冻结 `lib.js`、启动真实 ST 完成 runnable acceptance，再原子提交不可变 `READY` slot；安装不会自动激活或改写旧 active pointer。App 也能启动已激活的真实槽位，并在 WebView 打开 Core 报告就绪的本机页面。仅执行阶段 2 源码预检时仍只得到 `VERIFIED_NOT_READY`。当前仍未完成发布版可复现性、AI/Agent、备份、跨设备生命周期或功耗验收，因此这不是可发布结论。

Gate 0、Gate 1 与 Gate 2 已在 Android 12 / API 31 ARM64 模拟器完成当前实现验收。后续真实 ST 闭环另在一台 Android 15 / API 35 ARM64 真机通过；这些结果仍不等于 16 KB runtime image、跨机型、后台/锁屏/LMK 或功耗验收。

Gate 3A 已使用固定的 SillyTavern 1.18.0 commit、精确 `package-lock.json` 和经过扫描的实验依赖树，在同一 API 31 ARM64 模拟器完成 debug-only runnable feasibility：Feather Engine 通过 Javet 公开的 `allowEval(true)` 恢复标准 Node 主上下文所需的 `eval()` / `Function()` 语义，没有替换全局 `Function`，并让真实 `server.js` 两次完成启动、`/version` 与首页 HTTP 验证、停止及同端口复用。程序树、既有 slot 和 active-slot 指针在实验前后保持一致，真实 ST 没有被提升为 `READY` slot。

这项结论仍不是交付完成：实验供应由开发机准备并通过 ADB 放入 debug 专用缓存，不属于 APK 或产品仓库。修复后的低扰动采样器已在 2 GB 模拟器完成同设备单变量验证：默认 gzip cache 的 fresh Node→READY 为 121.144 秒、峰值 PSS 为 759,720 KB；`compression:false` 将 fresh 启动缩短到 18.705 秒，但峰值 PSS 升至 805,671 KB，cache 从约 11.8 MB 增至约 52.4 MB，所以它只保留为诊断与临时回退，不是产品架构。随后完成的 debug-only 预构建 bundle 原型，以固定 commit/源码/bundle SHA 整模块替换 `webpack-serve.js`，运行时禁止加载 Webpack、配置模块和 Terser：两个独立冷重启样本的 first start 为 2.195–2.296 秒、second start 为 1.135–1.420 秒，峰值 PSS 为 220,033–222,982 KB，四个 READY 5 分钟点为 169,580–173,236 KB，且没有创建 `_webpack`。

Stage 3B 仍在补证、尚未通过。2026-07-25 已由用户选择固定 npm CLI 作为 ST production dependencies 的正常初始化主路径，并选择在设备 staging 安装阶段本地生成 `lib.js` 与许可证后随不可变 slot 冻结；日常启动只提供固定 bundle，不运行 Webpack，也不要求 STM 维护者为每个 ST 版本手工构筑上传。固定 npm 11.6.2 工具树现在由可审计的 registry 发布物确定性转换为版本锁定的 APK asset；构建会验证 source identity、完整树、关键入口和许可证清单，最终 APK 会保留原始 asset 字节并避免外层二次压缩。Core 首次使用时从受控 staging 安全展开并按版本与 tree SHA 原子发布，后续只能完整复核后复用。

同一 API 31 ARM64 模拟器已经使用设备内字节与本地一致的 debug APK 验证这一载体：运行前不存在 Core toolchain 目录，首轮从 APK asset 展开后以 npm CLI 生成固定依赖树并启动真实 ST，第二轮完整复扫复用同一工具树，再由独立的短生命周期 Javet runtime 在设备本地生成与固定 oracle 相同的 1,947,206 字节 `lib.js`，随后完成 `/version`、首页、bundle、停止和端口释放验证。两轮都保持既有 slots 和 active pointer 不变，并恢复 Core 状态、CWD 与 environment。第二轮本地构筑峰值 RSS 为 776,416 KiB、VmHWM 为 785,912 KiB，期间 Android lowmemorykiller 回收了四个无关系统/Google 进程；STM/Core 没有被 LMK 杀死，但该内存压力仍是正式 Gate 风险。随后产品 UI 与正式 coordinator 路径完成 3 次端到端冷构筑，队列到完成分别为 428.542、424.955 和 420.188 秒：每次都复核 38,459,064 字节源码归档、复用固定工具树、运行 npm CLI、设备本地构筑同 SHA 的 `lib.js`、生成 adapter/tree/license/SBOM/provenance sidecar、在 staging 中完成真实 ST runnable acceptance，并原子提交 `READY` slot。样本之间只通过产品维护入口移除新建且未激活的测试 slot，旧 active pointer 的 SHA-256 始终为 `552507fb3a0fb548bfe9a5846be0484f198cb246208ae221ac8101e74a5c73a3`；失败的前置样本也证明目标 slot 与 staging 清理且旧 active pointer 不变。现有 Arborist 和签名预构建结果只保留为历史对照、故障注入与回归证据；正式 Gate 仍缺 ARM64 真机构筑、真实文件系统 ENOSPC、实际下载断网、取消/超时/进程死亡恢复和用户阈值冻结。prune policy 当前保持 `LOCKFILE_COMPLETE`，SBOM 与许可证清单已随 slot 冻结；npm 工具树另有 9 个 package instance、当前 ST 依赖树另有 41 个 package instance 缺少 package-local 许可证正文，因此当前结果不得称为 release-cleared。

另已取得 Stage 4 模拟器最小闭环的前置证据，但它不替代所选 npm CLI + 本地 bundle 构筑管线的 Gate 3B 验收，也不据此改变 Plan v2 的正式进度。`4-st-lifecycle` instrumentation 在同一 API 31 ARM64 模拟器上对保留的真实 ST 1.18.0 槽位执行两次 Core 启停，启动到 READY 分别为 1.483 秒和 1.441 秒；两次均使用 `127.0.0.1:8000`，核对 `/version`、734,643 字节首页和 SHA-256 固定的 1,947,206 字节 `lib.js`，停止后端口释放且完整槽位复核通过。随后从实际 Compose 界面启动 Core 并进入 WebView，确认 SillyTavern 欢迎与 Persona 页面真实渲染、外部文档导航被同源门槛拦截、提示可关闭、系统返回键回到总览、优雅停止后重新验证槽位。该轮 logcat 没有发现 App/Core 相关 FATAL、ANR、OOM、LMK 或 native crash；它仍不替代 Android 15 物理真机、WebView 上传/下载、后台/锁屏/进程回收和功耗验证。

同一闭环随后在 Samsung SM-G9980、Android 15 / API 35、WebView 149 上复验。覆盖安装保留既有数据；debug 签名供应从 staging 提交为真实 1.18.0 槽位后，`4-st-lifecycle` 两次启动到 READY 分别为 2.867 秒和 2.403 秒，并通过 `/version`、首页、`lib.js`、停止端口释放及完整槽位复核。实际 WebView 完成欢迎页渲染、输入法唤起、系统返回和 Core 继续驻留检查。真机同时暴露并修复了 WebView 在宿主首次布局前加载导致垂直 viewport 单位冻结为零、底部输入栏错位到顶部的问题：WebView 现在以 `MATCH_PARENT` 完成布局后再加载页面，不注入或改写 SillyTavern 页面。真机诊断报告通过系统文件选择器成功导出，最终系统审计未发现 FATAL、ANR、OOM、LMK 或 native crash；覆盖安装造成的旧进程退出由系统明确记录为 `PACKAGE UPDATED`。该单机结果仍不覆盖上传/下载、后台/锁屏/进程回收、长时间稳定性和功耗验收。

API 31 模拟器镜像自带的 WebView 91.0.4472.114 不支持 SillyTavern 1.18.0 使用的动态 viewport 单位；在该旧内核中，ST 的 `#sheld` 会按内容高度展开，底部输入栏不会贴住屏幕底边。当前实现没有通过页面注入去覆盖第三方 CSS。Android 12 测试设备应先更新 Android System WebView；OS 最低版本本身不代表内置 WebView 已满足当前 ST 前端要求。

## 构建

要求：JDK 17、Android SDK 36。

```bash
./gradlew assembleDebug
```

运行单元测试和 Android Lint：

```bash
./gradlew testDebugUnitTest lintDebug
```

固定 npm tool asset 及其最终 debug APK 副本可单独复核：

```bash
./gradlew :stm_core:verifyBundledNpmToolAsset :app:verifyDebugApkBundledNpmToolAsset
```

调试 APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

STM Core 的 Android 集成验收需要 Android 12 / API 31、`arm64-v8a` 设备或模拟器。先构建并安装应用和测试 APK：

```bash
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Gate 1 验证随机端口健康检查、标准 Android HTTP 客户端兼容、端口释放、session 重建、Node 模块语义、异线程终止、远程进程异常退出、UI 进程存活和检查点恢复。`gate` 省略时默认也是 `1`：

```bash
adb shell am instrument -w -r \
  -e gate 1 \
  io.github.styx798.sillytavernmanager.test/io.github.styx798.sillytavernmanager.stmcore.StmCoreGate1Instrumentation
```

Gate 2 使用调试专用合成归档验证安全解压、完整性证据、slot 提交、A/B 激活、回滚、进程中断恢复及用户数据隔离。该入口用于在设备或模拟器上复验；单独的代码或构建成功不能替代该 instrumentation：

```bash
adb shell am instrument -w -r \
  -e gate 2 \
  io.github.styx798.sillytavernmanager.test/io.github.styx798.sillytavernmanager.stmcore.StmCoreGate1Instrumentation
```

Gate 1 与 Gate 2 的最终验收应按上面两个独立 instrumentation 入口执行，并在两者之间冷启动模拟器。两组测试都会故意杀死 Core 进程；`-e gate all` 连续触发五次故障注入时，Android 可能把私有 Service 的自动重启退避提高到 256 秒，超过当前 90 秒状态等待预算，因此该组合入口只保留为调试便利，不作为通过证据。

已在 debug 缓存准备固定 Gate 3A 程序树时，可单独运行约 17 分钟的分阶段性能补测。它不会替代 Gate 3A 功能验收，也不会把真实 ST 提升为 `READY` slot：

```bash
adb shell am instrument -w -r \
  -e gate 3a-perf \
  io.github.styx798.sillytavernmanager.test/io.github.styx798.sillytavernmanager.stmcore.StmCoreGate1Instrumentation
```

另有两个仅供已准备相应调试制品的诊断入口：`gate=3a-perf-no-compression` 用于 Webpack cache 压缩 A/B，`gate=3a-perf-prebuilt` 用于固定 SHA 预构建 bundle 原型。二者都不是生产安装或激活入口。

已在 debug 缓存准备固定 Stage 3B 签名供应和精确官方源码 ZIP 时，可运行真实不可变槽位门槛。`3b-ready-slot` 保留一个未激活的 READY 槽位并验证 Core 重建；`3b-ready-slot-cold` 使用唯一临时槽位完成一次全新装填后删除；`3b-fault-matrix` 验证五类签名与绑定故障、处理中取消和注入 ENOSPC 共七类失败关闭。`3b-tree-diff` 会重新验签并绑定预构建 sidecar，再与 npm CLI 和 Arborist 的成功树证据做逐路径比较；`3b-npm-cli-cancel`、`3b-arborist-cancel` 及对应的 `-bounded-interruption` 入口验证取消、超时和 Core 进程状态恢复。这些入口都不是发布版安装或候选裁决入口：

```bash
adb shell am instrument -w -r \
  -e gate 3b-ready-slot \
  io.github.styx798.sillytavernmanager.test/io.github.styx798.sillytavernmanager.stmcore.StmCoreGate1Instrumentation
```

已在 debug 缓存准备精确官方源码输入时，`3b-npm-cli-runnable` 会从 APK asset 准备固定 npm CLI、生成 dependencies 并使用历史固定 bundle 做 runnable acceptance；`3b-npm-cli-local-bundle-runnable` 还会在 fresh Javet runtime 中调用上游构建入口，本地生成 bundle 后再运行真实 ST。二者都会检查 slots、active pointer、Core 状态、CWD 和 environment，但仍使用临时 debug workspace，不是正式 installer 或 slot admission：

```bash
adb shell am instrument -w -r \
  -e gate 3b-npm-cli-local-bundle-runnable \
  io.github.styx798.sillytavernmanager.test/io.github.styx798.sillytavernmanager.stmcore.StmCoreGate1Instrumentation
```

保留并激活 `3b-ready-slot` 生成的真实槽位后，可运行 Stage 4 生命周期入口。它会启动两次真实 ST，核对 `/version`、首页、固定 `lib.js`、数据目录隔离、稳定本机端口、停止后的端口释放和完整槽位复核；它不替代 App 内 WebView 的人工/界面自动化验证：

```bash
adb shell am instrument -w -r \
  -e gate 4-st-lifecycle \
  io.github.styx798.sillytavernmanager.test/io.github.styx798.sillytavernmanager.stmcore.StmCoreGate1Instrumentation
```

`process.exit()`、未捕获异常和未处理 Promise rejection 会结束当前私有 Core 进程。为避免 Android 对连续崩溃服务实施指数退避，这三项故障实验应分别安装测试包并使用 `-e fatalExperiment PROCESS_EXIT`、`UNCAUGHT_EXCEPTION` 或 `UNHANDLED_REJECTION` 独立运行。

默认构建使用完整 ICU 的 i18n Javet 产物。固定 ST 1.18.0 依赖已经证明非 i18n 变体无法解析所需的 Unicode property regular expression；非 i18n 只保留为 `-PstmJavetI18n=false` 的体积与回归对照，不能作为当前真实 ST 的运行候选。

Feather Engine 会为 ST 主 Node 上下文启用 Javet 的字符串代码生成能力，以匹配标准 Node 依赖对 lexical `eval()` 和原生 `Function()` 的使用。这个运行层不是不可信 JavaScript 的安全沙箱；当前结果也不代表所有 `node:vm` 次级 context 都已具备相同语义。

安全 ZIP 解压使用 Apache Commons Compress 1.28.0，以及构建解析到的 Commons Codec 1.19.0、Commons IO 2.20.0 和 Commons Lang 3.18.0。构建会将这些依赖随包提供的 Apache 许可证和 notice 与 Javet、Node.js、V8 许可证一起生成到 APK 的 `third_party/` assets；版本和生成规则以 `stm_core/build.gradle.kts` 为准。

API 31 不提供 Ed25519 的 JCA `Signature` / `KeyFactory`。STM Core 使用 Bouncy Castle 1.85.1 的轻量 Ed25519 原语校验 RFC 8410 公钥和 detached signature，不向系统安装或全局选择 Bouncy Castle provider；MIT 许可证随 APK 的 `third_party/` assets 提供。

## 工程边界

工程通过应用模块和独立运行层模块隔离职责：

- `core/`：App 使用的 STM Core、版本和日志契约；
- `data/`：Android 下载、文件、设置与 STM Core 控制实现；
- `ui/`：Compose 页面和主题；
- `app/`：依赖组装，不向用户开放插件或自定义界面系统。
- `stm_core/`：权威 Core 状态、检查点、私有进程 Service、IPC，以及承载 Javet/Node 的内部 Feather Engine。

第三方源码、研究资料、私人配置、密钥、签名文件和构建产物不得进入本仓库。
