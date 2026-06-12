# 蓝星网络 (LanXing Network)

基于 [ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid) 定制的 V2Board 订阅客户端，集成了 V2Board 账号登录、订阅自动同步、多域名回退等功能，同时完整保留原版 Clash Meta 的代理能力。

## 功能特性

### V2Board 集成

- **账号登录** — 首次安装自动跳转 V2Board 登录页，登录状态持久化
- **订阅自动同步** — 支持自定义同步间隔，后台静默完成
- **多域名自动回退** — 内置域名不可用时自动探测备用域名，保障可用性
- **账户信息展示** — 主页显示流量使用情况与到期时间
- **帮助/知识库** — 内置 V2Board 知识库与关于页面

### 代理核心能力

- 支持 Shadowsocks、VMess、VLESS、Trojan、Hysteria2、TUIC、WireGuard 等主流协议
- 基于 mihomo 内核，兼容 Clash Meta 配置格式
- TUN 模式全局代理（需 VPN 权限）
- 规则路由、DNS 覆盖、Fake-IP 等高级功能
- 支持 GeoIP / GeoSite 数据库进行地域分流

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
│       ├── foss/golang/    # Go 原生代码（mihomo 内核）
│       ├── main/golang/    # Go JNI 桥接层
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

## 环境要求

| 依赖 | 版本要求 |
|------|----------|
| Android SDK | compileSdk 35，minSdk 21 |
| JDK | OpenJDK 11+ |
| NDK | 29.0.14206865 |
| Go | 1.20+ |
| CMake | 3.0+ |
| Gradle | 8.8.0+ |

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

**配置示例**（实际项目中的写法）：

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

> **注意**：修改此文件后需重新编译 APK 才能生效。配置在构建时注入 `BuildConfig`，运行时由 `ConfigManager` 从 assets 中读取，两者保持一致。

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

### 更新地址接口格式

域名更新地址应返回如下格式的 JSON：

```json
{
  "domains": ["https://new-domain1.com", "https://new-domain2.com"],
  "update_url": "https://new-update-server.com"
}
```

## 产品变体

项目通过 Gradle `productFlavors` 提供两个变体：

| 变体 | 包名后缀 | 说明 |
|------|----------|------|
| `alpha` | `.alpha` | 默认变体，开发/测试使用 |
| `meta` | `.meta` | 发布变体 |

可通过 `local.properties` 中 `remove.suffix=true` 去掉包名后缀。

## 依赖说明

| 组件 | 用途 |
|------|------|
| [mihomo](https://github.com/MetaCubeX/mihomo) | 代理核心引擎（Git 子模块） |
| OkHttp | 网络请求（V2Board API、订阅同步） |
| Room | 本地数据库（代理选择、导入记录） |
| Kotlin Serialization | JSON 序列化 |
| kaidl | AIDL 接口代码生成 |
| AndroidX | 基础 UI 组件 |

## 许可证

本项目基于 [GPLv3](LICENSE) 许可证发布。

贡献代码即表示您同意将代码合并至项目的闭源分支，其余条款遵循 GPLv3 协议。详见 [CONTRIBUTING.md](CONTRIBUTING.md)。
