# 蓝星网络 (LanXing Network)

[![Android](https://img.shields.io/badge/Android-5.0%2B-green.svg)](https://developer.android.com/about/versions/lollipop)
[![License](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

基于 [ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid) 定制的 V2Board 订阅客户端，集成了 V2Board 账号登录、订阅自动同步、多域名回退等功能，同时完整保留原版 Clash Meta 的代理能力。

---

## ⚠️ Regional Restrictions / 地区限制

**This software is strictly prohibited from use in mainland China (People's Republic of China).**

This application is designed exclusively for overseas Chinese communities, including but not limited to users in:
- Singapore
- Malaysia
- Taiwan
- Hong Kong
- Macau
- Other regions outside mainland China

By using this software, you acknowledge and agree that you are not located in mainland China and will not use this software within mainland China.

---

**本软件严格禁止在中国大陆（中华人民共和国）境内使用。**

本应用程序专为海外华侨设计，包括但不限于以下地区的用户：
- 新加坡
- 马来西亚
- 台湾
- 香港
- 澳门
- 中国大陆以外的其他地区

使用本软件即表示您确认并同意您不在中国大陆境内，并且不会在中国大陆境内使用本软件。

---

## 功能特性

### 代理模式对比

本应用提供两种代理模式，适用于不同用户需求：

| 功能 | 普通用户模式（VPN） | Root 用户模式（透明代理） |
|------|---------------------|--------------------------|
| **权限要求** | 无需 root 权限 | 需要 root 权限 |
| **代理方式** | Android VPN Service | iptables 透明代理 |
| **状态栏图标** | 显示 VPN 图标 | 无额外图标 |
| **代理范围** | 应用层流量 | 全部流量（含系统服务） |
| **DNS 处理** | 应用内 DNS | 支持 DNS 劫持 |
| **后台保活** | WakeLock + Watchdog | 支持锁定后台 |
| **兼容性** | 通用兼容 | 部分设备可能冲突 |
| **安全性** | 系统级保护 | 需信任应用 |

#### 普通用户模式（推荐新手）

- ✅ 无需 root 权限，兼容性好
- ✅ 使用 Android 系统 VPN Service，稳定可靠
- ✅ 不会触发安全软件警告
- ✅ 系统级保护，VPN 断开时自动恢复网络

#### Root 用户模式（高级用户）

- ✅ 无 VPN 图标，状态栏更干净
- ✅ 透明代理所有流量，包括系统级流量
- ✅ 更底层的网络控制，兼容性更好
- ✅ 支持 DNS 劫持，DNS 解析更可靠
- ✅ 支持锁定后台，防止系统杀进程

### V2Board 集成

- **账号登录** — 首次安装自动跳转 V2Board 登录页，登录状态持久化
- **订阅自动同步** — 支持自定义同步间隔，后台静默完成
- **多域名自动回退** — 内置域名不可用时自动探测备用域名，保障可用性
- **账户信息展示** — 主页显示流量使用情况与到期时间
- **帮助/知识库** — 内置 V2Board 知识库与关于页面

### 代理核心能力

- 支持 Shadowsocks、VMess、VLESS、Trojan、**Hysteria2**、TUIC、WireGuard 等主流协议
- 基于 mihomo 内核，兼容 Clash Meta 配置格式
- TUN 模式全局代理（需 VPN 权限）
- 规则路由、DNS 覆盖、Fake-IP 等高级功能
- 支持 GeoIP / GeoSite 数据库进行地域分流

### 高级优化特性

项目集成了完整的性能优化模块，位于 `core/src/main/golang/native/optimize/`：

| 优化类别 | 具体内容 | 预期收益 |
|----------|----------|----------|
| **TCP 连接池** | 复用 TCP/TLS 连接，支持 VMess/VLESS/Trojan/SS | 减少握手开销，降低延迟 50-100ms |
| **TCP 并发拨号** | 默认启用多 IP 并发连接 | 多 IP 场景下连接建立更快 |
| **DNS 超时优化** | 默认超时从 5s 降至 3s | 加快 DNS 失败时的 fallback |
| **UDP 缓冲区优化** | 增大到 1MB+ 级别 | 提升高带宽传输稳定性 |
| **连接跟踪优化** | 优化超时时间为 10/60 秒 | 减少内存占用，提升并发 |
| **队列优化** | 增大网络队列到 8192 | 减少高负载丢包 |
| **BBR 算法** | 启用 TCP BBR 拥塞控制 | 提升 TCP 吞吐量 |
| **Hysteria2/QUIC** | 专用内核参数优化 | 优化 QUIC 协议性能 |
| **对象池优化** | 缓冲区复用机制 | 减少内存分配开销 |
| **并发优化** | 批量命令并行执行 | 提升初始化速度 |
| **连接池复用** | Shell 会话复用 | 减少 su 启动开销 |
| **自适应 FEC** | 根据丢包率动态调整冗余度 | 提升高丢包环境稳定性 |
| **智能重传** | 基于 RTT 判断重传时机 | 减少不必要的重传 |
| **QUIC 动态配置** | 根据丢包率调整窗口大小 | 优化 QUIC 协议性能 |
| **QUIC 多路径** | 支持多路径传输 | 提升连接可靠性 |
| **定时清理** | 每 60s 清理过期连接，释放资源 | 防止连接泄漏，稳定内存占用 |
| **异常保护** | try-catch 包裹所有优化器调用 | 优化器异常不影响主服务运行 |
| **YAML 配置注入** | 自动优化 Hysteria2/TUIC 协议参数 | 开箱即用的高性能配置 |

### 后台保活机制

应用实现了多层级的后台保活策略，确保服务在息屏、内存压力、被系统回收等场景下持续运行：

| 机制 | 说明 | 适用模式 |
|------|------|---------|
| **WakeLock** | PARTIAL_WAKE_LOCK 持续持有，防止 CPU 休眠 | VPN / Root |
| **AlarmManager Watchdog** | 每 60s 一次精准闹钟，服务被系统杀死后自动拉活 | VPN / Root |
| **Watchdog KeepAlive** | 每 50s 续期闹钟，避免闹钟在服务存活时误触发 | VPN / Root |
| **onTaskRemoved 重启** | 用户从最近任务划掉后自动重启服务 | VPN / Root |
| **模式感知重启** | 看门狗根据 VPN 开关只重启正确的服务，避免资源浪费 | VPN / Root |

### 稳定性保障（v2.0）

v2.0 进行了全面的稳定性重构，修复了闪退、运行一段时间后自动关闭等核心问题，共 **26 项修复**：

#### 崩溃防护（Crash Prevention）

| 机制 | 说明 | 修复前 | 修复后 |
|------|------|--------|--------|
| **协程异常拦截** | 所有 7 个 CoroutineScope 均添加 CoroutineExceptionHandler | 任何后台协程异常 → 进程崩溃（"应用已停止"） | 异常被捕获并记录日志，服务继续运行 |
| **空指针防护** | 移除所有 `!!` 强制解包，替换为安全调用或显式异常 | NPE 直接崩溃 | 优雅降级 + 日志记录 |
| **Go 层 panic 保护** | 所有 export 函数用 `defer recover()` 包裹 | Go panic 导致进程崩溃 | 仅记录错误日志，不影响主进程 |
| **配置加载容错** | 无 Profile 时不抛异常，改为等待重试 | 启动时无 Profile → NPE 崩溃 | 3 秒等待 + 自动重试 |
| **服务绑定重试** | RemoteService 断连后最多重试 3 次（每次间隔 2 秒） | 服务断连 → 立即触发 AppCrashedActivity | 多数情况下自动恢复，无需用户干预 |

#### 自动关闭防护（Keep-Alive）

| 机制 | 说明 |
|------|------|
| **WakeLock 持续持有** | PARTIAL_WAKE_LOCK 防止 CPU 休眠，每 30 秒检查并重新获取 |
| **AlarmManager Watchdog** | 每 60 秒精准闹钟，服务被系统杀死后自动拉活 |
| **Watchdog KeepAlive** | 每 50 秒续期闹钟，防止服务存活时闹钟误触发 |
| **onTaskRemoved 安全重启** | Android 12+ 通过 watchdog 重启，避免后台启动限制 |
| **AtomicBoolean 防并发** | 服务启动状态用原子操作，防止多实例争抢 |

#### 内存优化

| 机制 | 说明 |
|------|------|
| **Go GC 调优** | GCPercent 从 100% 降至 50%，每 5 分钟且堆 > 32MB 时强制 FreeOSMemory |
| **TCP 连接池缩减** | 最大连接数从 200 降至 100，降低内存峰值 |
| **连接跟踪清理** | 每 60 秒清理过期连接，防止连接泄漏 |

#### 编译兼容性

| 修复项 | 说明 |
|--------|------|
| **Kotlin 2.1 兼容** | `javaClass` → `::class.java`，`CancellableContinuation.cancel()` → `resumeWithException()` |
| **Go 常量兼容** | 自定义 `TCP_FASTOPEN_CONNECT`、`IPV6_MTU_DISCOVER` 等 Linux 内核常量 |
| **kapt 兼容** | 启用 `kapt.use.kotlin2=true` |

### 功能权限对比

| 功能 | VPN 模式（非 root） | 透明代理模式（root） | 说明 |
|------|---------------------|----------------------|------|
| TCP 连接池 | ✅ | ✅ | 协议层连接复用 |
| TCP 并发拨号 | ✅ | ✅ | 多 IP 并发连接 |
| DNS 超时优化 | ✅ | ✅ | 默认 3s 超时 |
| 对象池优化 | ✅ | ✅ | 减少内存分配开销 |
| 并发优化 | ✅ | ✅ | 提升初始化速度 |
| Hysteria2/QUIC | ✅ | ✅ | 协议层面支持 |
| UDP 缓冲区优化 | ❌ | ✅ | 需 `sysctl` 调整内核参数 |
| 连接跟踪优化 | ❌ | ✅ | 需调整 `nf_conntrack` 参数 |
| 队列优化 | ❌ | ✅ | 需调整 `netdev_max_backlog` |
| BBR 算法 | ❌ | ✅ | 需调整 TCP 拥塞控制 |
| DNS 劫持 | ❌ | ✅ | 需 iptables 规则 |
| 透明代理 | ❌ | ✅ | 需 TPROXY/REDIRECT 规则 |
| 锁定后台 | ❌ | ✅ | 需 iptables 标记 |
| WakeLock 保活 | ✅ | ✅ | PARTIAL_WAKE_LOCK 防止 CPU 休眠 |
| Watchdog 守护 | ✅ | ✅ | AlarmManager 精准闹钟拉活 |

---

## 项目结构

```
ClashMetaForAndroid/
├── app/                    # 主应用模块
│   └── src/main/
│       ├── java/           # Activity、Service、工具类
│       ├── res/            # 资源文件
│       └── assets/         # 内置资源（about.html、geo 数据）
├── core/                   # 核心代理模块
│   └── src/
│       ├── foss/golang/    # Go 原生代码（mihomo 内核，Git 子模块）
│       ├── main/golang/    # Go JNI 桥接层
│       │   └── native/
│       │       ├── optimize/        # 性能优化模块（TCP 连接池、FEC、QUIC 等）
│       │       └── optimize_bridge.go  # JNI 导出桥接
│       └── main/cpp/       # C JNI 桥接层
├── service/                # 后台服务模块
│   └── src/main/java/      # ClashService、TunService、ProfileManager
├── design/                 # UI 设计模块
│   └── src/main/
│       ├── java/           # Design 类（Activity UI 层）
│       └── res/            # 布局、样式、国际化资源
├── common/                 # 公共工具模块
├── v2board/                # V2Board 集成模块
│   └── src/main/java/      # 配置管理、会话、同步逻辑
└── hideapi/                # Android 隐藏 API 访问
```

---

## 环境要求

| 依赖 | 版本要求 |
|------|----------|
| Android SDK | compileSdk 35，minSdk 21 |
| JDK | OpenJDK 17+ |
| NDK | 29.0.14206865 |
| Kotlin | 2.1.0+ |
| Go | 1.20+ |
| CMake | 3.0+ |
| Gradle | 8.8.0+ |

---

## 快速开始

### 1. 克隆项目

```bash
git clone --recurse-submodules https://github.com/<your-org>/ClashMetaForAndroid.git
cd ClashMetaForAndroid
```

> 如已克隆但未拉取子模块：`git submodule update --init --recursive`

### 2. 配置环境

在项目根目录创建 `local.properties`：

```properties
# Android SDK 路径（必填）
sdk.dir=/path/to/android-sdk

# 自定义应用包名（可选）
custom.application.id=com.my.compile.clash

# 去掉包名后缀（可选，设为 true 后包名不再带 .alpha/.meta 后缀）
remove.suffix=true
```

### 3. 配置 V2Board

编辑项目根目录的 [`v2board.properties`](v2board.properties) 文件（与 `build.gradle.kts` 同级）：

```properties
# 主服务端 URL（必填，用于 API 调用）
v2board.server.url=https://your-domain.com

# 备用域名列表（可选，逗号分隔，主域名不可用时自动逐个探测）
v2board.server.domains=https://domain1.com,https://domain2.com,https://domain3.com

# 订阅同步间隔（可选，单位：分钟，默认 1440 = 24 小时）
v2board.sync.interval=1440

# 应用显示名称（可选，仅用于内部标识）
v2board.app.name=蓝星网络
```

#### 多域名配置说明

多域名通过 `v2board.server.domains` 字段配置，**多个 URL 用英文逗号 `,` 分隔**，不要有空格。

**配置示例**：

```properties
# 主域名 + 2 个备用域名
v2board.server.domains=https://jc.lxseek.com,https://go.lxkjzh.top,https://cdn.lxkjzh.top
```

**域名回退逻辑**（运行时自动执行）：

```
1. 优先使用用户登录后动态获取的后端地址（serverUrl）
2. 若为空，使用 v2board.server.url 作为主域名
3. 若主域名不通，依次尝试 v2board.server.domains 中的每个域名
4. 全部失败 → 提示用户检查网络
```

### 4. 配置签名（可选，发布时需要）

创建 `signing.properties`：

```properties
keystore.password=<password>
key.alias=<alias>
key.password=<password>
```

签名文件放置于项目根目录，命名为 `release.keystore`。

### 5. 编译

```bash
# Alpha 版本
./gradlew app:assembleAlphaRelease

# Meta 版本
./gradlew app:assembleMetaRelease
```

编译产物输出至 `app/release/` 目录。

---

## Root 功能配置

### 启用 Root 功能

1. 确保设备已获取 root 权限
2. 打开应用 → 设置 → Root 设置
3. 系统会自动弹出 superuser 授权对话框
4. 点击"允许"授权
5. 启用所需功能：
   - **透明代理** — 通过 iptables 重定向所有流量
   - **DNS 劫持** — 拦截 DNS 查询并路由到 Clash
   - **锁定后台** — 防止系统杀死 Clash 进程

### Root 功能说明

| 功能 | 说明 |
|------|------|
| 透明代理 | 使用 TPROXY 模式重定向所有 TCP/UDP 流量 |
| DNS 劫持 | 将所有 DNS 查询（端口 53）重定向到 Clash |
| 锁定后台 | 使用 iptables 标记防止系统杀死进程 |

---

## 架构说明

### V2Board 登录流程

```
首次安装
  → 启动 App → 检测 hasEverLoggedIn = false
  → 打开 V2Board 登录页（WebView）
  → 用户登录 → JS Bridge 捕获 auth_data
  → 保存到 SharedPreferences → hasEverLoggedIn = true
  → 自动同步订阅 → 进入主页

再次启动
  → 检测 hasEverLoggedIn = true → 直接进入主页
  → 不发起任何网络请求
  → 点击"我的账户"等按钮时才加载 WebView
```

### 域名回退机制

```
1. 优先使用 config.serverUrl（已探测成功的域名或登录后动态获取的后端地址）
2. 其次使用 ConfigManager 中的主域名（v2board.server.url，仅作为首次启动 fallback）
3. 最后使用 v2board.properties 中的域名列表（v2board.server.domains）逐个探测
4. 全部失败 → 提示用户检查网络或配置
```

### V2Board 模块说明

| 文件 | 职责 |
|------|------|
| `ConfigManager.kt` | 统一配置管理，从 `v2board.properties` 读取配置 |
| `V2BoardConfig.kt` | 多域名配置、同步间隔管理 |
| `V2BoardSession.kt` | 登录态持久化（SharedPreferences） |
| `V2BoardSync.kt` | 域名回退、API 调用、单例管理 |
| `V2BoardActivity.kt` | WebView 壳（登录/用户中心/套餐/关于/知识库） |
| `V2BoardAutoSync.kt` | 订阅自动同步到 Profile |
| `V2BoardDesign.kt` | WebView UI 层 |

---

## 产品变体

项目通过 Gradle `productFlavors` 提供两个变体：

| 变体 | 包名后缀 | 说明 |
|------|----------|------|
| `alpha` | `.alpha` | 默认变体，开发/测试使用 |
| `meta` | `.meta` | 发布变体 |

可通过 `local.properties` 中 `remove.suffix=true` 去掉包名后缀。

---

## 依赖说明

| 组件 | 用途 |
|------|------|
| [mihomo](https://github.com/MetaCubeX/mihomo) | 代理核心引擎（Git 子模块） |
| [libsu](https://github.com/topjohnwu/libsu) | Root 权限管理（6.0.0+） |
| OkHttp | 网络请求（V2Board API、订阅同步） |
| Room | 本地数据库（代理选择、导入记录） |
| Kotlin Serialization | JSON 序列化 |
| kaidl | AIDL 接口代码生成 |
| AndroidX | 基础 UI 组件 |
| Kotlin Coroutines | 异步任务处理 |
| reedsolomon | Go 端 FEC 纠删码 |

> **Go 模块注意**：每次修改 `core/src/main/golang/` 下的 Go 代码后，需在 CI 中执行 `go mod tidy` 同步依赖。GitHub Actions 工作流已包含此步骤。

---

## 许可证

本项目基于 [GPLv3](LICENSE) 许可证发布。

贡献代码即表示您同意将代码合并至项目的闭源分支，其余条款遵循 GPLv3 协议。详见 [CONTRIBUTING.md](CONTRIBUTING.md)。

---

## 免责声明

本软件仅供学习和研究使用，用户需自行承担使用风险。开发者不对因使用本软件而产生的任何直接或间接损失负责。

本软件严格禁止在中国大陆境内使用，用户需遵守所在地区的法律法规。
