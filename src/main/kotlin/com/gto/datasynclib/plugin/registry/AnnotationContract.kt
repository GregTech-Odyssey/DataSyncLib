package com.gto.datasynclib.plugin.registry

/**
 * 描述一个「注解字符串属性 → 目标成员」的解析契约。
 *
 * 这是插件的单一事实来源，精确镜像 `FieldAnnotationMetadata` / `FieldDefinitionStorage`
 * / `ReflectUtil` 中的解析规则。含扩展方向 1（跨类型契约校验）所需的签名信息。
 */
enum class RefKind {
    /** 实例方法（非静态），在字段声明类及其继承链上查找 */
    INSTANCE_METHOD,

    /** 静态字段，仅在字段声明类上查找（getDeclaredField） */
    STATIC_FIELD,
}

/**
 * 一个注解属性的契约描述。
 *
 * @param annotationQualifiedName 注解全限定名，如 "com.gto.datasynclib.annotations.SyncToClient"
 * @param attributeName           注解里的属性名，如 "listener"
 * @param kind                    引用类别：实例方法 还是 静态字段
 * @param paramTypes              期望的方法参数类型（方法引用时）；用 null 表示「字段类型 T」，用特殊哨兵表示固定类型
 * @param returnType              期望的返回类型（方法引用时）；null 表示不关心/void 语义另有定义
 * @param required                是否必填（缺失应报错），如 Conversion.toManaged
 * @param returnIsVoid            方法引用时返回类型是否应为 void
 * @param staticFieldType         静态字段引用的期望字段类型（可 null 表示不强制）
 * @param fixedParamTypes         方法引用时用固定类型（如 Codec.readFromData 的 Data.class, int.class），null 表示全用字段类型
 */
data class AnnotationContract(
    val annotationQualifiedName: String,
    val attributeName: String,
    val kind: RefKind,
    val required: Boolean = false,
    val returnIsBoolean: Boolean = false,
    val returnIsVoid: Boolean = false,
    val staticFieldType: String? = null,
    val fixedParamTypes: List<String?>? = null,
    /** 参数个数；-1 表示由 fixedParamTypes 决定，否则表示「字段类型的重复次数」 */
    val paramCountFromFieldType: Int = 0,
    /**
     * 返回类型必须等于**有效类型 T**。
     *
     * 运行期把这些方法的返回值直接赋给/转型为 T，例如
     * `DataFieldDefinition.decode` 用 `readFromData` / `readFromBuffer` 的结果当 T 用，
     * 返回别的类型会在运行期 ClassCastException。见 `AbstractFieldAccess` 的 read 路径。
     */
    val returnIsFieldType: Boolean = false,
    /**
     * 引用缺失是否只是"静默无效"而不是"立即崩溃"。
     *
     * `true` 表示运行期用 try/catch 吞掉找不到的情况（如 `@Conversion.toField`：
     * `catch (NoSuchFieldException ignored)`），此时插件应给 WARNING 而非 ERROR。
     */
    val optionalRef: Boolean = false,
) {
    /** 方法引用时的期望参数类型列表（含哨兵 FIELD_TYPE） */
    val expectedParamTypes: List<String?>?
        get() =
            fixedParamTypes ?: if (paramCountFromFieldType > 0) {
                List(paramCountFromFieldType) { FIELD_TYPE }
            } else {
                emptyList()
            }

    companion object {
        /** 哨兵：表示「字段的类型 T」 */
        const val FIELD_TYPE: String = "\u0000FIELD_TYPE\u0000"

        const val SYNC_TO_CLIENT = "com.gto.datasynclib.annotations.SyncToClient"
        const val SYNC_TO_SERVER = "com.gto.datasynclib.annotations.SyncToServer"
        const val SAVE_TO_DISK = "com.gto.datasynclib.annotations.SaveToDisk"
        const val CONVERSION = "com.gto.datasynclib.annotations.Conversion"
        const val STRATEGY = "com.gto.datasynclib.annotations.Strategy"
        const val CODEC = "com.gto.datasynclib.annotations.Codec"
    }
}

/**
 * 全部注解属性契约的注册表，与 `docs/annotation-contract.md` 一一对应。
 */
object AnnotationContractRegistry {
    /** buffer 类型哨兵：1.20.1 用 FriendlyByteBuf，1.21 用 RegistryFriendlyByteBuf */
    const val BUFFER_FIELD_TYPE: String = "\u0000BUFFER\u0000"

    val ALL: List<AnnotationContract> =
        listOf(
            // ---- SyncToClient ----
            AnnotationContract(
                AnnotationContract.SYNC_TO_CLIENT,
                "skipWhen",
                RefKind.INSTANCE_METHOD,
                returnIsBoolean = true,
                paramCountFromFieldType = 1,
            ),
            AnnotationContract(
                AnnotationContract.SYNC_TO_CLIENT,
                "listener",
                RefKind.INSTANCE_METHOD,
                returnIsVoid = true,
                paramCountFromFieldType = 2,
            ),
            // ---- SyncToServer ----
            AnnotationContract(
                AnnotationContract.SYNC_TO_SERVER,
                "skipWhen",
                RefKind.INSTANCE_METHOD,
                returnIsBoolean = true,
                paramCountFromFieldType = 1,
            ),
            AnnotationContract(
                AnnotationContract.SYNC_TO_SERVER,
                "listener",
                RefKind.INSTANCE_METHOD,
                returnIsVoid = true,
                paramCountFromFieldType = 2,
            ),
            // ---- SaveToDisk ----
            AnnotationContract(
                AnnotationContract.SAVE_TO_DISK,
                "skipWhen",
                RefKind.INSTANCE_METHOD,
                returnIsBoolean = true,
                paramCountFromFieldType = 1,
            ),
            AnnotationContract(
                AnnotationContract.SAVE_TO_DISK,
                "listener",
                RefKind.INSTANCE_METHOD,
                returnIsVoid = true,
                paramCountFromFieldType = 1,
            ),
            AnnotationContract(
                AnnotationContract.SAVE_TO_DISK,
                "defaultValueGetter",
                RefKind.INSTANCE_METHOD,
                paramCountFromFieldType = 0,
            ),
            // ---- Conversion（静态 Function 字段） ----
            AnnotationContract(
                AnnotationContract.CONVERSION,
                "toManaged",
                RefKind.STATIC_FIELD,
                required = true,
                staticFieldType = "java.util.function.Function",
            ),
            AnnotationContract(
                AnnotationContract.CONVERSION,
                "toField",
                RefKind.STATIC_FIELD,
                staticFieldType = "java.util.function.Function",
                // 运行期 catch (NoSuchFieldException ignored) 静默跳过，不是崩溃
                optionalRef = true,
            ),
            // ---- Strategy（静态 Hash.Strategy 字段） ----
            AnnotationContract(
                AnnotationContract.STRATEGY,
                "value",
                RefKind.STATIC_FIELD,
                required = true,
                staticFieldType = "it.unimi.dsi.fastutil.Hash.Strategy",
            ),
            // ---- Codec（静态 codec 字段 或 实例方法，互斥分支） ----
            AnnotationContract(
                AnnotationContract.CODEC,
                "saveCodec",
                RefKind.STATIC_FIELD,
                staticFieldType = "com.gto.datasynclib.datastream.codec.DataCodec",
            ),
            AnnotationContract(
                AnnotationContract.CODEC,
                "syncCodec",
                RefKind.STATIC_FIELD,
                staticFieldType = "com.gto.datasynclib.datastream.codec.ByteStreamCodec",
            ),
            AnnotationContract(
                AnnotationContract.CODEC,
                "writeToData",
                RefKind.INSTANCE_METHOD,
                paramCountFromFieldType = 1,
            ),
            AnnotationContract(
                AnnotationContract.CODEC,
                "readFromData",
                RefKind.INSTANCE_METHOD,
                fixedParamTypes = listOf("com.gto.datasynclib.datastream.data.Data", "int"),
                // 运行期把返回值当 T 用
                returnIsFieldType = true,
            ),
            AnnotationContract(
                AnnotationContract.CODEC,
                "writeToBuffer",
                RefKind.INSTANCE_METHOD,
                returnIsVoid = true,
                fixedParamTypes = listOf(BUFFER_FIELD_TYPE, AnnotationContract.FIELD_TYPE),
            ),
            AnnotationContract(
                AnnotationContract.CODEC,
                "readFromBuffer",
                RefKind.INSTANCE_METHOD,
                fixedParamTypes = listOf(BUFFER_FIELD_TYPE),
                // 运行期把返回值当 T 用
                returnIsFieldType = true,
            ),
        )

    fun findByAnnotation(annotationQualifiedName: String): List<AnnotationContract> = ALL.filter { it.annotationQualifiedName == annotationQualifiedName }

    fun find(annotationQualifiedName: String, attributeName: String): AnnotationContract? = ALL.firstOrNull {
        it.annotationQualifiedName == annotationQualifiedName && it.attributeName == attributeName
    }
}
