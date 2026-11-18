# Acid-WeChat 架构与开发规范

## 1. 分层架构与依赖原则

项目采用严格的单向分层设计，防止出现"上帝类"与循环依赖：

```text
HookEntry (模块入口与生命周期编排)
  ├── ui / debug       (纯配置驱动的 WebView 设置页、本地环回诊断接口)
  ├── features         (独立业务功能模块，禁止互调、禁止直接写 UI 或裸 SQL)
  ├── wechat           (微信表结构/领域数据仓储与解析适配)
  └── core             (通用基础设施：Context/Config/DexKit/WCDB/Protobuf/Reflect)
```

### 各包职责边界

| 包路径 | 职责范围 | 禁止事项 |
| --- | --- | --- |
| `com.acid.wechat` | `HookEntry`：初始化核心桥接、注册 Feature、触发 DexKit 解析 | 不写具体业务逻辑或具体 Hook 细节 |
| `com.acid.wechat.core` | `HostContext`、`ConfigStore`、`AcidFeature`、`FeatureRegistry`、`DexKitLocator`、`DexKitCache`、`ReflectHelper`、`WcdbBridge`、`ProtoLite`、`MsgNoticeHelper`、`AcidLogger` | 不包含特定微信业务（朋友圈/聊天）的表字段或规则判断 |
| `com.acid.wechat.wechat` | `ChatRepository`、`EmojiRepository`、`SnsRepository`、`SnsProto`、`SnsContextMenuHook`、`SnsFakeLikeStore`、`SnsFakeCommentStore`：封装 `EnMicroMsg.db` / `SnsMicroMsg.db` / `SnsInfoStorage` / 朋友圈长按菜单与 Protobuf 解析写入 | 不直接修改 UI 或持有开关策略 |
| `com.acid.wechat.features` | `AntiRecallFeature`、`CustomGameEmojiFeature`、`AntiMomentsPostFeature`、`FakeSnsLikeFeature`、`FakeSnsCommentFeature`、`SnsTailFeature`、`SavePathFeature`：每个 Feature 单文件负责一条功能链路、声明自己的 `DexRule` / `FeatureOption` / `AcidModalSpec` | 不写裸 SQL（走 `wechat/` 仓储）；不手写设置页 HTML 或独立弹窗 HTML；Feature 间不互相依赖 |
| `com.acid.wechat.ui` | `WebViewHost`、`SettingsHook`、`SettingsDialog`、`ui/overlay/`（`AcidModal`、`AcidModalSpec`、`FloatingSheetDialog`）：数据驱动设置页与标准居中悬浮弹窗体系 | 不硬编码功能列表，完全由 `FeatureRegistry` / `AcidModalSpec` / `ConfigStore` 驱动 |
| `com.acid.wechat.debug` | `DebugServer`：`127.0.0.1:19898` 只读状态、日志与调试开关（可关闭） | 不绕过系统或宿主安全边界 |

---

## 2. 防上帝类与代码质量约束

1. **单一职责与文件规模**：单个 Kotlin 文件聚焦单一职责，目标控制在 **250 行以内**；单个函数保持简短浅嵌套，避免把 Hook、SQL 查询、文案拼接、缓存全堆在一个类里。
2. **禁止重复逻辑**：反射查找统一复用 `ReflectHelper`；protobuf wire 修改统一复用 `ProtoLite`；微信联系人/消息查询统一走 `wechat/ChatRepository`。
3. **异常隔离**：所有 Hook 回调与反射操作必须包在 `runCatching` 中，并通过 `FeatureRegistry.recordError` / `AcidLogger` 上报，绝不允许拖崩微信主进程。
4. **无死代码**：废弃实验功能（如曾经移除的印章、评论防删）及配套临时字段必须连同配置 key 清理（`ConfigStore.retiredKeys`）。

---

## 3. UI 与配置规范

- **一级目录**：主页（`home`）、通讯录（`contacts`）、发现（`discover`）、我的（`me`）、朋友圈（`moments`）、聊天（`chat`）。
- **二级折叠目录**：同一业务通过 `FeatureCategory` + `group` 归入可折叠卡片（默认展开，展开态记录在前端 `localStorage`）。
- **极简白色界面**：卡片与底栏保持纯白极简风格；配置项只显示名称与控件（开关或单行输入框），**不写冗长功能介绍文案**。
- **父子联动**：子开关及自定义输入框通过 `dependsOn` 关联父 `configKey`，父项关闭时前端置灰锁定，运行时通过 `ConfigStore.isEffective()` 联判。
- **文本转义约定**：单行配置框支持 `\n` 表示换行（如默认朋友圈删除前缀 `❌\n`），运行时统一经 `ConfigStore.decodeNewlines()` 解析；撤回提示支持 `{name}` 占位符。

---

## 4. 新增 Feature 标准模板

1. 在 `ConfigStore` 定义配置 key 及默认值。
2. 新建 `com.acid.wechat.features.XxxFeature : AcidFeature`，声明 `category`、`group`、`configKey`、`extraOptions()`。
3. 如需访问微信新表结构，先在 `wechat/` 下沉淀仓储方法；如需要无版本依赖的 DEX 符号，在 `registerDexRules()` 注册 `DexKitLocator` 规则。
4. 如需在微信页面内弹出交互窗口，**一律复用 `AcidModal.show(activity, AcidModalSpec(...))`** 拼装标准积木块（`Preview` / `RandomCountBar` / `Checklist` / `TextInput` / `NumberInput` / `Toggle`），禁止为新功能重复造独立弹窗 HTML。
5. 在 `HookEntry.bootstrap()` 注册一次即可自动接入 DexKit、配置持久化、WebView 设置页与 Debug 接口。

---

## 5. 构建与调试

```bash
./build.sh release
# 产物输出（增量更新，不再附带 -mvp 后缀）:
#   app/build/outputs/apk/release/app-release.apk
#   /storage/emulated/0/Download/Acid-WeChat.apk
```

本地调试端点（默认 `http://127.0.0.1:19898`）：`/health`、`/dexkit`、`/features`、`/config`、`/logs`。
