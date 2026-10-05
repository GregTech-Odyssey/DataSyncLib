# DataSyncLib IntelliJ IDEA Plugin

为 DataSyncLib 的注解驱动 DSL 提供 IDE 级支持，解决「注解字符串引用无法跳转 / 被引用成员误报未使用」的核心痛点，并把大量运行期反射错误提前到编译期暴露。

## 背景

DataSyncLib 的注解用**字符串字面量**引用类成员，例如：

```java
@SyncToClient(listener = "onEnergyChange")   // "onEnergyChange" 是一个方法名字符串
private long energy;

public void onEnergyChange(long newValue, long oldValue) { ... }
```

这些字符串由运行时通过反射（`FieldAnnotationMetadata` / `FieldDefinitionStorage` /
`ReflectUtil`）按名字解析，因此 IntelliJ 默认：
- 无法从 `"onEnergyChange"` 跳转到方法定义；
- 把 `onEnergyChange` 方法误判为「未使用」（灰显）；
- 改名/重构时不联动，运行期静默失效。

本插件精确镜像运行时的解析规则，把字符串字面量恢复成真正的符号引用，并补全注解 DSL 的语义检查。

---

## 功能总览

### 一、导航

| 功能 | 说明 |
|---|---|
| 字符串引用跳转 | Ctrl/Cmd + Click 从注解字符串（`listener="x"`、`skipWhen="x"`、`saveCodec="x"`、`toManaged="x"` 等）跳转到被引用的方法/字段 |
| 嵌套类跳转 | `@AdditionalHolder`(flat) 字段的 gutter 图标点击跳转到嵌套类型 |

### 二、契约校验（Inspection）

**字符串引用类校验：**

| 检查 | 级别 |
|---|---|
| 引用成员不存在（unresolved） | ERROR |
| 引用字段非 static（运行时 `f.get(null)` 要求） | ERROR |
| required 属性缺失（如 `@Conversion.toManaged`） | ERROR |
| 方法签名不匹配（返回类型 / 参数个数 / 参数类型） | WARNING |

**语义检查类校验（镜像运行期逻辑）：**

| 检查 | 级别 | 源码依据 |
|---|---|---|
| `@SaveToDisk.key` 重复（含嵌套 `@AdditionalHolder` 展平字段） | ERROR | `definitionMap.put != null → throw` |
| `@SaveToDisk.defaultValue` 字面量格式 | ERROR | `ReflectUtil.parse(type, value)` |
| `@Generic` 字段无泛型实参 | ERROR | `genericType.length == 0 → throw` |
| `@Codec` 成对：`writeToData` 缺 `readFromData`、`writeToBuffer` 缺 `readFromBuffer` | ERROR | 无条件解析 readXxx |
| `@Codec` 无效果：`saveCodec` 非空时 `writeToData` 等实例方法被忽略 | WARNING | 互斥分支 |
| `@Codec` 无效果：`saveCodec` 空时单独填 `syncCodec` | WARNING | 分支作用域 |
| `@Conversion` 方向：`Function<A,B>` 的 A 不匹配字段类型 | WARNING | Javadoc 约定 |
| `@Strategy` 泛型：`Hash.Strategy<T>` 的 T 不匹配字段类型 | WARNING | Javadoc 约定 |

**关键修复**：`listener`/`skipWhen`/`@Codec` 方法的签名匹配用「有效类型」（含 `@Conversion` 的托管类型），而非字段声明类型，避免有 `@Conversion` 的字段被误报。

### 三、体验增强

| 功能 | 说明 |
|---|---|
| 成员补全 | 字符串字面量内输入时，按契约签名过滤列出方法名/字段名 |
| 生成方法 quick-fix | Alt+Enter 为引用的 `listener`/`skipWhen`/`defaultValueGetter` 等方法生成签名正确的骨架 |
| 消除「未使用」误报 | 被注解字符串引用的成员不再被 `UnusedDeclarationInspection` 标灰 |

### 四、数据流可视化

| 功能 | 说明 |
|---|---|
| gutter 图标 | 为 `@SyncToClient`/`@SyncToServer`/`@SaveToDisk`/`@Conversion`/`@Codec`/`@Strategy`/`@AdditionalHolder`/`@Generic` 字段显示对应图标 |
| 托管类型提示 | `@Conversion` 字段的图标 tooltip 显示「声明类型 → 托管类型」 |
| 类 tooltip 字段清单 | hover 类名时，展示本类**及各级父类**中被注解字段的清单（字段名、类型、注解标签） |

类 tooltip 示例：

```
DataSyncLib 字段
─── 本类 (MyHolder) ───
  energy : long  [SyncToClient, SaveToDisk]
  inventory : InventoryData → 托管类型  [SaveToDisk, AdditionalHolder]
─── 父类 (BaseHolder) ───
  id : int  [SaveToDisk]
```

---

## 覆盖的注解

`@SaveToDisk`、`@SyncToClient`、`@SyncToServer`、`@Conversion`、`@Codec`、
`@Strategy`、`@AdditionalHolder`、`@Generic`、`@Access`、`@AddToManager`。

完整契约（属性 → 解析规则 → 签名）见
[`docs/annotation-contract.md`](docs/annotation-contract.md)，它是插件内
`registry/AnnotationContract.kt` 的镜像，也是运行时解析逻辑的单一事实来源。

## 架构

```
src/main/kotlin/com/gto/datasynclib/plugin/
├── registry/
│   ├── AnnotationContract.kt        # 注解属性 → 解析契约的单一事实来源
│   └── FieldContextResolver.kt      # 有效类型 / 泛型解析（镜像 scanFields）
├── reference/                       # PsiReference（导航核心）
├── inspection/
│   ├── AnnotationContractInspection.kt   # 契约 + 语义校验
│   ├── AnnotationGlobalUsageHelper.kt    # 消除未使用误报
│   └── GenerateReferencedMethodIntention.kt  # 生成方法 quick-fix
├── completion/                      # 成员补全
└── markup/
    ├── DataSyncLibLineMarkerProvider.kt   # gutter 图标 + tooltip + 嵌套导航
    └── DataSyncLibDocumentationProvider.kt # 类 tooltip 字段清单
```

## 构建

```bash
./gradlew buildPlugin        # 产出可安装的 zip
./gradlew runIde             # 启动带插件的沙盒 IDE
```

> 构建需要 JDK 21 与可访问 Maven Central / JetBrains 仓库的网络环境。
> **Gradle JVM 必须是 JDK 21**：Gradle 8.14.5 内嵌的 Kotlin 解析不了 JDK 25 的版本号
> （报 `IllegalArgumentException: 25.0.4.1`）。命令行构建请设置 `JAVA_HOME`，
> 或在 IDEA 中把 *Settings → Build Tools → Gradle → Gradle JVM* 设为 JDK 21。
> 使用 IntelliJ Platform Gradle Plugin 2.19.0、Kotlin 2.3，目标平台为 IntelliJ IDEA 2026.2（build 262）；
> 插件最低支持 IDEA 2026.2。注意 2.16.0 之前的插件无法解析 262 的新模块描述格式
> （`$legacy_jps_module` namespace），会导致 `test` 任务依赖解析失败。
> **推荐直接复用本机已安装的 IDEA，彻底避免下载约 1.5G 的平台包**（在 `gradle.properties` 中配置，该文件未被 git 跟踪）：
> ```properties
> localPlatformPath=D:\\Program Files\\JetBrains\\IntelliJ IDEA
> localRuntimePath=D:\\Program Files\\JetBrains\\IntelliJ IDEA\\jbr
> ```
> 也可临时用 `-PlocalPlatformPath=<IDEA 安装目录>` 覆盖；测试或启动沙盒 IDE 时用
> `-PlocalRuntimePath=<IDEA 安装目录>/jbr` 复用 JetBrains Runtime。
> iSH 环境不支持运行 JVM（`getcpu` 系统调用缺失），请在本地 IDE / CI 中构建。

## 事实来源

所有解析规则镜像自：
- `FieldAnnotationMetadata.java` — 注解字符串 → Method/Field 的解析
- `FieldDefinitionStorage.java` — 字段扫描、类型流转（`@Conversion` 托管类型）、继承链合并
- `ReflectUtil.java` — 方法/字段查找、泛型解析、`parse` 字面量解析
