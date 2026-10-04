package com.gto.datasynclib.plugin.registry

import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiType

/**
 * 解析一个被注解字段的「有效类型上下文」，精确镜像
 * `FieldDefinitionStorage.scanFields` 的类型流转规则。
 *
 * 关键规则（源码事实）：
 * - 字段若无 `@Conversion`：有效类型 = 字段声明类型 `field.type`。
 * - 字段若有 `@Conversion(toManaged = "...")`：有效类型 = 该静态 Function 字段的
 *   **第二泛型参数**（托管类型）。`scanFields` 里 `type =
 *   getResolvedGenericArguments(conversionField.getGenericType(), Function.class)[1]`。
 * - 后续所有 listener / skipWhen / @Codec 方法的签名匹配，用的都是「有效类型」，
 *   而不是字段声明类型。
 */
object FieldContextResolver {

    const val CONVERSION_ANNOTATION = "com.gto.datasynclib.annotations.Conversion"

    /**
     * @return 字段的有效类型（托管类型或声明类型）。无法确定时返回声明类型。
     */
    fun effectiveType(field: PsiField): PsiType {
        val conversion = field.getAnnotation(CONVERSION_ANNOTATION) ?: return field.type
        val toManaged = conversion.findAttributeValue("toManaged")?.let {
            (it as? com.intellij.psi.PsiLiteralExpression)?.value as? String
        } ?: return field.type
        val clazz = field.containingClass ?: return field.type
        val fnField = clazz.findFieldByName(toManaged, true) ?: return field.type
        val fnType = fnField.type as? PsiClassType ?: return field.type
        // Function<A, B>：第二泛型参数是 B（托管类型）
        val typeParams = fnType.parameters
        if (typeParams.size >= 2) return typeParams[1]
        return field.type
    }

    /**
     * 是否有 @Conversion（用于 @Generic 检查的分支镜像）。
     */
    fun hasConversion(field: PsiField): Boolean =
        field.getAnnotation(CONVERSION_ANNOTATION) != null

    /**
     * 解析字段的泛型实参（用于 @Generic 检查），镜像 `createFieldDefinition`：
     * - 有 @Conversion：取 conversionField 的 Function 第二泛型实参
     * - 无 @Conversion：取 field 自身的泛型实参
     *
     * @return 泛型实参类型数组；若无法解析（非参数化类型）返回空数组
     */
    fun genericArguments(field: PsiField): List<PsiType> {
        val conversion = field.getAnnotation(CONVERSION_ANNOTATION)
        return if (conversion != null) {
            val toManaged = conversion.findAttributeValue("toManaged")?.let {
                (it as? com.intellij.psi.PsiLiteralExpression)?.value as? String
            }
            val clazz = field.containingClass
            val fnField = clazz?.findFieldByName(toManaged ?: "", true)
            val fnType = fnField?.type as? PsiClassType
            fnType?.parameters?.drop(1) ?: emptyList() // Function<A, B> 的 B 的实参
        } else {
            (field.type as? PsiClassType)?.parameters ?: emptyList()
        }
    }
}
