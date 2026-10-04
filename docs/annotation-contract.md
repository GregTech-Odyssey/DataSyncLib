# DataSyncLib 注解字符串引用契约（插件事实来源）

> 来源：`FieldAnnotationMetadata.java` + `FieldDefinitionStorage.java` + `ReflectUtil.java`
> 本文档是 IntelliJ 插件解析/校验逻辑的**唯一事实来源**，必须与源码保持一致。

## 通用解析规则

所有「字符串 → 成员」解析都基于注解**所在字段的声明类** `clazz`（即 `scanFields(clazz, ...)` 里的 clazz）。

### 方法查找（`ReflectUtil.getAccessibleMethod`）
```
1. clazz.getDeclaredMethod(name, paramTypes)  —— 优先声明类（含 private）
2. clazz.getMethod(name, paramTypes)           —— 回退到继承的 public 方法
```
- 查找范围：**仅当前声明类 + 其继承的 public 方法**（不含父类的 private/package 方法）。
- 参数类型**精确匹配**（`getMethod`/`getDeclaredMethod` 按签名精确匹配，非赋值兼容）。
- 找不到 → 抛 `RuntimeException`（运行期崩溃，插件应在编译期标红）。

### 字段查找（静态字段引用）
```
clazz.getDeclaredField(name)  —— 仅当前声明类，含 private
```
- 不查父类字段。找不到 → 抛异常。
- 通过 `f.get(null)` 读静态值，所以要求字段是 **static**。

---

## 契约表

| 注解.属性 | 引用类型 | 精确签名 / 类型 | 静态度 | 必填 |
|---|---|---|---|---|
| `SyncToClient.skipWhen` | 实例方法 | `(T) -> boolean`，T=字段类型 | 非静态 | 否 |
| `SyncToServer.skipWhen` | 实例方法 | `(T) -> boolean` | 非静态 | 否 |
| `SyncToClient.listener` | 实例方法 | `(T newValue, T oldValue) -> void` | 非静态 | 否 |
| `SyncToServer.listener` | 实例方法 | `(T newValue, T oldValue) -> void` | 非静态 | 否 |
| `SaveToDisk.skipWhen` | 实例方法 | `(T) -> boolean` | 非静态 | 否 |
| `SaveToDisk.listener` | 实例方法 | `(T) -> void`（磁盘加载后回调） | 非静态 | 否 |
| `SaveToDisk.defaultValueGetter` | 实例方法 | `() -> T`（无参） | 非静态 | 否 |
| `Conversion.toManaged` | 静态字段 | `static Function<FieldType, ManagedType>` | **静态** | **是**（required） |
| `Conversion.toField` | 静态字段 | `static Function<ManagedType, FieldType>` | **静态** | 否（缺失静默跳过） |
| `Strategy.value` | 静态字段 | `static Hash.Strategy<T>` | **静态** | 是 |
| `Codec.saveCodec` | 静态字段 | `static DataCodec<?>` | **静态** | 否 |
| `Codec.syncCodec` | 静态字段 | `static ByteStreamCodec<?>` | **静态** | 否 |
| `Codec.writeToData` | 实例方法 | `(T) -> Data` | 非静态 | 否 |
| `Codec.readFromData` | 实例方法 | `(Data, int) -> T` | 非静态 | 否 |
| `Codec.writeToBuffer` | 实例方法 | `(FriendlyByteBuf, T) -> void` | 非静态 | 否 |
| `Codec.readFromBuffer` | 实例方法 | `(FriendlyByteBuf) -> T` | 非静态 | 否 |
| `SaveToDisk.key` | —（字符串键） | 非符号引用，仅可校验 key 唯一性（可选） | — | 否 |
| `SaveToDisk.defaultValue` | —（字面量） | 经 `ReflectUtil.parse(type, value)` 解析，可校验格式 | — | 否 |

## 关键细节

1. **`listener`（sync）参数类型 = 字段类型 `T`，且 oldValue/newValue 都是同一个 `T`**。契约校验时两个参数都必须是字段的精确类型。

2. **`Conversion.toManaged` 的返回值决定了「托管类型」**：`getResolvedGenericArguments(conversionField.getGenericType(), Function.class)[1]`（第 2 个泛型参数）。插件做契约校验时可据此推断 `ManagedType`。

3. **`Codec` 有互斥分支**：
   - 若 `saveCodec` 非空 → 走「静态 codec 字段」分支，`writeToData`/`readFromData`/`writeToBuffer`/`readFromBuffer` **被忽略**。
   - 若 `saveCodec` 为空 → 走「实例方法」分支（writeToData 与 readFromData 成对、writeToBuffer 与 readFromBuffer 成对）。
   - 插件应检查：`writeToData` 未配对 `readFromData` 时（仅填一个）的语义。

4. **`readFromBuffer` 用 `FriendlyByteBuf`（1.20.1）/ `RegistryFriendlyByteBuf`（1.21）**——签名随版本，插件校验 buffer 类型时应宽松匹配（两者兼容）。

5. **`Conversion` 的 `toField` 缺失时「静默跳过」**，不报错；`toManaged` 缺失则必然空指针/崩溃。插件对 `toManaged` 应标「必填缺失」错误。

## 关键类型流转（@Conversion 托管类型）

- `scanFields` 里 `@Conversion(toManaged="X")` 存在时，字段的「有效类型」被替换为 `X` 所指向的静态 `Function<A, B>` 字段的**第二泛型参数 B**（托管类型）。
- 后续 `listener`/`skipWhen`/`@Codec` 方法的签名匹配，用的都是**托管类型**，而非字段声明类型。
- 插件必须用 `FieldContextResolver.effectiveType()` 镜像此规则，否则有 `@Conversion` 的字段会误报签名不匹配。
- `@Generic` 检查同理：有 `@Conversion` 时泛型实参来自 `Function` 字段的第二泛型，而非字段自身。

## 插件检查项清单（与代码一一对应）

| 检查 | 触发条件 | 源码依据 |
|---|---|---|
| key 重复 | 同 holder 内两字段解析出相同 key | `FieldDefinitionStorage` 构造器 `definitionMap.put != null → throw Duplicate` |
| defaultValue 格式 | `@SaveToDisk.defaultValue` 无法按有效类型解析 | `ReflectUtil.parse(type, value)` |
| @Generic 无泛型 | `@Generic` 字段无泛型实参 | `createFieldDefinition` `genericType.length == 0 → throw` |
| @Codec 无效果 | `saveCodec` 非空且实例方法属性也填了 | `FieldAnnotationMetadata` 互斥分支 |
| 托管类型提示 | `@Conversion` 字段 | `scanFields` type 替换 |


## 插件需要规避的解析难点

- **重载**：`skipWhen="foo"` 若有多个 `foo`，`getDeclaredMethod(name, type)` 靠参数精确匹配消歧；插件应按「签名精确匹配」过滤，多个候选时提示歧义。
- **继承**：方法可能落父类（`getMethod` 只找 public），字段（`getDeclaredField`）不跨父类。插件解析范围必须区隔这两种情况。
