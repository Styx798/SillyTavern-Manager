# SillyTavern Manager

**SillyTavern Manager（STM）** 是一个面向 Android 的 SillyTavern 管理器。

STM 希望让用户像管理容器一样管理不同版本的 SillyTavern：安装、启动、停止、切换和恢复由 Android 原生界面统一处理，SillyTavern 本身则尽可能保持接近官方上游。

> STM 是独立实现的第三方项目，不是 SillyTavern 官方项目，也不隶属于 SillyTavern 项目团队。

## 产品优势

- **接近官方上游**：以官方 SillyTavern 为运行主体，不重写其核心业务。
- **版本相互隔离**：不同版本使用独立、不可变的运行槽位，程序与用户数据分离。
- **升级可以回退**：新版本安装失败时保留原有可用版本，为切换和恢复提供基础。
- **Android 原生管理**：通过原生界面管理下载、安装、运行状态、日志和应用文件。
- **本机私有运行**：SillyTavern Core 在独立进程中运行，只向应用内的本机回环地址提供服务。
- **安装过程可验证**：对来源、归档和运行槽位执行完整性检查，并保留可诊断的状态记录。
- **兼顾易用与自主性**：计划提供快速安装的预构建运行层，同时保留设备本地构建能力作为备用方案。

## 当前进度

当前版本为 **v0.0.1 开发预览版**。

项目已经打通 Android 上下载、验证、安装、启动、停止和打开 SillyTavern 的基础闭环，并具备初步的版本槽位、日志、诊断和文件管理能力。

目前正在完善快速分发、安装恢复、异常中断处理和跨设备兼容性。项目尚未发布稳定版，不建议作为唯一的生产环境使用。

当前支持范围：

- Android 12 及以上；
- ARM64（`arm64-v8a`）设备；
- 简体中文和 English 界面。

## 构建

需要 JDK 17 和 Android SDK 36。

```bash
./gradlew assembleDebug
```

运行单元测试和 Android Lint：

```bash
./gradlew testDebugUnitTest lintDebug
```

调试 APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 项目结构

- `app/`：Android 应用、原生界面和管理功能；
- `stm_core/`：SillyTavern 运行、安装、槽位和恢复能力。

第三方源码、研究资料、私人配置、密钥、签名文件和构建产物不属于本仓库。
