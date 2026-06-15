# 蓝星网络 (LanXing Network)

基于 [ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid) 定制的 V2Board 订阅客户端，集成了 V2Board 账号登录、订阅自动同步、多域名回退、多账号管理等功能，同时完整保留原版 Clash Meta 的代理能力。

**当前版本：v2.0.22**

## 功能特性

### V2Board 集成

- **账号登录** — 首次安装自动跳转 V2Board 登录页，登录状态持久化（基于 localStorage），无需重复登录
- **多账号管理** — 支持多个 V2Board 账号，通过邮箱自动识别：
  - 同一邮箱 → 自动更新对应订阅配置
  - 不同邮箱 → 自动创建新订阅（蓝星、蓝星1、蓝星2...）并激活
- **订阅自动同步** — 支持自定义同步间隔，后台静默完成
- **多域名自动回退** — 内置域名不可用时自动探测备用域名，保障可用性
- **账户信息展示** — 主页卡片实时显示：
  - 运行状态 + 上下行流量（简写格式：↑1.2M ↓3.4G）
  - 代理模式（Rule/Direct/Global）
  - 套餐名称、到期时间、余额
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

#### 本地开发

创建 `signing.properties`：

```properties
keystore.password=<password>
key.alias=<alias>
key.password=<password>
```

签名文件放置于项目根目录，命名为 `release.keystore`。

#### GitHub Actions 自动签名

**配置优先级**：`GitHub Secrets` > `.env 文件`

如果设置了 GitHub Secrets，会使用 Secrets 的值；否则从 `.env` 文件读取。

**方法一：使用 GitHub Secrets（推荐）**

在 GitHub 仓库的 `Settings → Secrets and variables → Actions` 中添加以下 Secrets：

| Secret 名称 | 值 |
|------------|-----|
| `SIGNING_STORE_PASSWORD` | 密钥库密码（如 `ojbkxc`） |
| `SIGNING_KEY_ALIAS` | 密钥别名（如 `ojbkxc`） |
| `SIGNING_KEY_PASSWORD` | 密钥密码（如 `ojbkxc`） |
| `V2BOARD_SERVER_URL` | 前端域名（如 `jc.lxseek.com`） |
| `V2BOARD_SERVER_DOMAINS` | 域名列表（如 `jc.lxseek.com`） |

**方法二：使用 .env 文件（适用于本地测试）**

创建 `.env` 文件：
```env
SIGNING_STORE_PASSWORD=ojbkxc
SIGNING_KEY_ALIAS=ojbkxc
SIGNING_KEY_PASSWORD=ojbkxc
V2BOARD_SERVER_URL=jc.lxseek.com
V2BOARD_SERVER_DOMAINS=jc.lxseek.com
```

> **注意**：
> - CI 会自动生成签名密钥库，无需手动上传 `release.keystore` 文件
> - 确保三个签名配置的值完全一致（建议都使用相同的值）

### 5. 配置前端域名（可选）

#### 本地开发

在 `app/src/main/assets/v2board.properties` 中配置：

```properties
v2board.server.url=jc.lxseek.com
v2board.server.domains=jc.lxseek.com,backup.lxseek.com
```

> **注意**：域名可以不带 `https://` 前缀，系统会自动添加。

#### GitHub Actions

在 GitHub Secrets 中添加：

| Secret 名称 | 值示例 |
|------------|--------|
| `V2BOARD_SERVER_URL` | `jc.lxseek.com` |
| `V2BOARD_SERVER_DOMAINS` | `jc.lxseek.com,backup.lxseek.com` |

> **注意**：域名可以不带 `https://` 前缀，系统会自动添加。

### 6. 编译

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
  → 点击 Logo 进入账户时才加载 WebView

WebView 加载时
  → 在 onPageStarted 中自动注入已保存的 auth_data 到 localStorage
  → 前端路由守卫检查到登录状态 → 自动跳转仪表盘
  → 无需用户重新登录
```

### 多账号订阅管理

```
用户 A (user@example.com) 首次登录
  → 创建订阅配置：蓝星
  → 自动激活

用户 B (other@test.com) 登录
  → 检测到邮箱不同 → 创建订阅配置：蓝星1
  → 自动激活为当前配置

用户 A 再次登录（切换回）
  → 检测到邮箱相同 → 更新订阅配置：蓝星
  → 自动激活
```

> **注意**：订阅命名基于 `v2board.properties` 中的 `v2board.app.name` 配置，默认为"蓝星"。

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
| `V2BoardSession.kt` | 登录态持久化（SharedPreferences、localStorage 注入）、邮箱→订阅 UUID 映射 |
| `V2BoardSync.kt` | 域名回退、API 调用、单例管理、fetchSubscribeUrl / fetchUserInfo |
| `V2BoardActivity.kt` | WebView 壳（登录/用户中心/套餐/关于/知识库）、JS Bridge 实现 |
| `V2BoardAutoSync.kt` | 订阅自动同步到 Profile、多账号检测与配置创建/更新 |
| `V2BoardDesign.kt` | WebView UI 层、配置 |

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

## 更新日志

### v2.0.22
- **多账号支持**：通过邮箱自动识别用户，同一邮箱更新订阅，不同邮箱创建新订阅配置
- **登录优化**：WebView 加载时自动注入 localStorage，消除重复登录需求
- **UI 优化**：主界面卡片布局优化，流量显示简写格式（↑1.2M ↓3.4G）
- **信息同步**：切换账号后自动重新获取用户信息（套餐、到期时间、余额）
- **编译修复**：移除废弃的 WebView API，适配最新 Android SDK
