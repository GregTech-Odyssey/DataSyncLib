package com.gto.datasynclib.plugin.registry

import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType

/**
 * `@SaveToDisk.defaultValue` 的解析规则，精确镜像运行期 `ReflectUtil.parse(Class, String)`：
 *
 * ```java
 * if (type == String.class) return value;
 * if (value.isEmpty()) return null;
 * if (type.isEnum()) return EnumUtil.getEnum((Class) type, value);
 * if (int/Integer)   return Integer.parseInt(value);
 * ... long / double / float / byte / short 同理
 * if (boolean/Boolean) return Boolean.parseBoolean(value);
 * if (char/Character)  return value.charAt(0);
 * else throw new IllegalArgumentException("Unsupported type: " + type.getName());
 * ```
 *
 * 填错的后果按类型分三档（决定报错级别）：
 * - 数字型 / 不支持的引用类型：`parse` 抛异常，发生在 `FieldAnnotationMetadata` 构造期，
 *   即 mod 加载阶段直接崩溃 → ERROR
 * - boolean：`parseBoolean` 对任何非 "true" 字符串静默返回 false → ERROR（静默错值）
 * - 枚举：`getEnum` 查不到返回 null，静默变成「无默认值」 → ERROR（静默错值）
 * - char：非空即取首字符，不失败；仅提示长度可疑 → 非 ERROR
 * - String：永不失败
 */
object DefaultValueTypeResolver {

    /** 校验结果；[compatible] 为 false 时 [message] 给出与运行期一致的原因。 */
    data class Result(val compatible: Boolean, val message: String? = null) {
        companion object {
            val OK = Result(true)

            fun fail(message: String) = Result(false, message)
        }
    }

    /**
     * @param type 字段的**有效类型**（含 @Conversion 托管类型），与运行期 `ReflectUtil.parse` 的入参一致
     * @param text 注解里 defaultValue 的原始字符串
     */
    fun validate(type: PsiType, text: String): Result {
        // 空串在运行期直接 return null，不会失败
        if (text.isEmpty()) return Result.OK

        // 数组字段：@SaveToDisk.saveEmpty 与 defaultValue 无关，运行期不 parse 数组
        if (type is PsiArrayType) return Result.OK

        return when (type.canonicalText) {
            "java.lang.String" -> Result.OK

            "boolean", "java.lang.Boolean" ->
                // Boolean.parseBoolean 对任意非 "true" 字符串静默返回 false —— 必须拦
                if (text == "true" || text == "false") {
                    Result.OK
                } else {
                    Result.fail("'$text' is not a boolean literal; Boolean.parseBoolean would silently yield false (use \"true\" or \"false\")")
                }

            "int", "java.lang.Integer" -> numeric(text, "int") { it.toInt() }
            "long", "java.lang.Long" -> numeric(text, "long") { it.toLong() }
            "double", "java.lang.Double" -> numeric(text, "double") { it.toDouble() }
            "float", "java.lang.Float" -> numeric(text, "float") { it.toFloat() }
            "byte", "java.lang.Byte" -> numeric(text, "byte") { it.toByte() }
            "short", "java.lang.Short" -> numeric(text, "short") { it.toShort() }

            "char", "java.lang.Character" ->
                // charAt(0)：非空即成立，不失败（仅当长度 > 1 时提示只取首字符）
                if (text.length == 1) {
                    Result.OK
                } else {
                    Result(true, "Only the first character '${text[0]}' is used (Character parsing takes charAt(0))")
                }

            else ->
                if (type is PsiPrimitiveType) {
                    Result.OK
                } else {
                    // 枚举走名字查找，其它引用类型在运行期直接抛 IllegalArgumentException
                    enumConstants(type)?.let { constants ->
                        if (text in constants) {
                            Result.OK
                        } else {
                            Result.fail("Unknown enum constant '$text' for ${type.presentableText}; EnumUtil.getEnum returns null (silently no default). Candidates: ${constants.joinToString(", ")}")
                        }
                    } ?: Result.fail("Unsupported type ${type.presentableText}: ReflectUtil.parse throws IllegalArgumentException for this type")
                }
        }
    }

    /** 枚举常量名列表；非枚举返回 null（含无法解析的情况）。 */
    fun enumConstants(type: PsiType): List<String>? {
        val clazz = (type as? PsiClassType)?.resolve() ?: return null
        if (!clazz.isEnum) return null
        return clazz.fields.filter { it.hasModifierProperty("static") && it.hasModifierProperty("final") }.map { it.name }
    }

    private inline fun numeric(text: String, label: String, parse: (String) -> Any): Result = try {
        parse(text)
        Result.OK
    } catch (e: NumberFormatException) {
        Result.fail("Cannot parse '$text' as $label (ReflectUtil.parse would throw at mod load time)")
    }
}
