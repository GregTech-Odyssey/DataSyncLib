# DataSyncLib IntelliJ IDEA Plugin

为 DataSyncLib 的注解驱动 DSL 提供 IDE 级支持，解决「注解字符串引用无法跳转 / 被引用成员误报未使用」的核心痛点。

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

本插件精确镜像运行时的解析规则，把字符串字面量恢复成真正的符号引用。

## 功能

### 1. 导航（Ctrl/Cmd + Click）
从注解字符串跳转到被引用的 `PsiMethod` / `PsiField`。

### 2. 契约校验（Inspection）
- 引用成员不存在 → ERROR
- 签名不匹配（返回类型 / 参数个数 / 参数类型）→ WARNING
- 静态字段被写成实例方法、反之 → ERROR
- 引用字段非 static（运行时 `f.get(null)` 要求）→ ERROR
- required 属性缺失（如 `@Conversion.toManaged`）→ ERROR

### 3. 消除「未使用」误报
被注解字符串引用的成员不再被 `UnusedDeclarationInspection` 标为未使用。

### 4. 补全
在字符串字面量内输入时，列出符合该契约签名的方法名 / 字段名。

### 5. 数据流可视化
- `@SyncToClient` / `@SyncToServer` / `@SaveToDisk` 字段左侧显示 gutter 图标。

## 扩展方向

- **跨类型契约校验**：不仅校验「存在性」，还校验方法签名的参数/返回类型与字段类型
  `T` 的精确匹配（listener 的两个参数都必须等于字段类型等）。
- **数据流可视化**：gutter 图标 + （规划中的）字段管线视图，展示每个字段的同步/持久化流向。

## 架构

- `registry/AnnotationContract.kt` — 注解属性 → 解析契约的**单一事实来源**，与
  `docs/annotation-contract.md` 一一对应。运行时解析规则变更时，同步更新这里。
- `reference/` — `PsiReference` 实现（导航核心）。
- `inspection/` — 契约校验 + 未使用误报消除。
- `completion/` — 补全。
- `markup/` — gutter 可视化。

## 构建

```bash
./gradlew buildPlugin        # 产出可安装的 zip
./gradlew runIde             # 启动带插件的沙盒 IDE
```

> 构建需要 JDK 17 与可访问 JetBrains 插件的网络环境。
> iSH 环境不支持运行 JVM（`getcpu` 系统调用缺失），请在本地 IDE / CI 中构建。

## 事实来源

所有解析规则镜像自：
- `src/main/java/com/gto/datasynclib/FieldAnnotationMetadata.java`
- `src/main/java/com/gto/datasynclib/FieldDefinitionStorage.java`
- `src/main/java/com/gto/datasynclib/util/ReflectUtil.java`

完整契约见 [`docs/annotation-contract.md`](docs/annotation-contract.md)。
