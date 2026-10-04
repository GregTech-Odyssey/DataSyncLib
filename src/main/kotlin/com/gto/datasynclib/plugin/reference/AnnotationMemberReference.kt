package com.gto.datasynclib.plugin.reference

import com.gto.datasynclib.plugin.registry.AnnotationContract
import com.gto.datasynclib.plugin.registry.AnnotationContractRegistry
import com.gto.datasynclib.plugin.registry.RefKind
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException

/**
 * 把注解里的字符串字面量解析到被引用的 PsiMethod / PsiField。
 *
 * 精确镜像 `ReflectUtil.getAccessibleMethod`（先 getDeclaredMethod 再 getMethod）和
 * `FieldDefinitionStorage.scanFields`（getDeclaredField，不跨父类）的查找规则。
 */
class AnnotationMemberReference(
    element: PsiLiteralExpression,
    private val owner: PsiField,
    private val contract: AnnotationContract,
) : PsiReferenceBase<PsiLiteralExpression>(element, TextRange(1, element.textLength - 1)) {

    override fun resolve(): PsiElement? = when (contract.kind) {
        RefKind.STATIC_FIELD -> resolveField()
        RefKind.INSTANCE_METHOD -> resolveMethod()
    }

    private val name: String
        get() = element.value as? String ?: return ""

    private val clazz: PsiClass?
        get() = owner.containingClass

    /** 字段类型 T */
    private val fieldType: PsiType
        get() = owner.type

    private fun resolveField(): PsiField? {
        val clazz = clazz ?: return null
        // getDeclaredField：仅当前类（含 private），不跨父类
        var target = clazz.findFieldByName(name, false)
        if (target == null) {
            // 兜底：也向上查（插件比运行时宽松，便于导航；校验逻辑会严格区隔）
            target = clazz.findFieldByName(name, true)
        }
        // 校验静态性：运行时 f.get(null) 要求 static
        return target
    }

    private fun resolveMethod(): PsiMethod? {
        val clazz = clazz ?: return null
        val name = this.name
        if (name.isEmpty()) return null
        // 先精确匹配签名（getDeclaredMethod），再回退名字匹配（getMethod 找继承 public）
        return findMethodWithSignature(clazz, name, onlyDeclared = false)
            ?: clazz.findMethodsByName(name, true).firstOrNull()
    }

    private fun findMethodWithSignature(
        clazz: PsiClass,
        name: String,
        onlyDeclared: Boolean,
    ): PsiMethod? {
        val candidates = clazz.findMethodsByName(name, !onlyDeclared)
        if (candidates.isEmpty()) return null
        // 按契约的期望签名过滤
        return candidates.firstOrNull { matchesContract(it) }
            ?: candidates.firstOrNull { nameMatchOnly(it) }
    }

    /** 宽松：只看名字，用于导航时优先跳转（宁可跳错也不跳空） */
    private fun nameMatchOnly(method: PsiMethod): Boolean = true

    /** 严格：按契约校验签名（供 inspection 使用） */
    fun matchesContract(method: PsiMethod): Boolean {
        val params = contract.expectedParamTypes ?: emptyList()
        val actual = method.parameterList.parameters.map { it.type }
        if (actual.size != params.size) return false
        params.forEachIndexed { i, expected ->
            if (expected == null) return@forEachIndexed
            val a = actual[i]
            if (expected == AnnotationContract.FIELD_TYPE) {
                if (a != fieldType) return false
            } else if (expected == AnnotationContractRegistry.BUFFER_FIELD_TYPE) {
                if (!isBufferType(a)) return false
            } else {
                // 固定类型：按类名宽松匹配
                if (!isAssignableToFixed(a, expected)) return false
            }
        }
        if (contract.returnIsBoolean) {
            if (method.returnType != PsiTypes.booleanType()) return false
        }
        if (contract.returnIsVoid) {
            if (method.returnType != PsiType.VOID) return false
        }
        return true
    }

    private fun isBufferType(type: PsiType?): Boolean = when (type?.canonicalText) {
        "net.minecraft.network.FriendlyByteBuf",
        "net.minecraft.network.RegistryFriendlyByteBuf" -> true
        else -> false
    }

    private fun isAssignableToFixed(actual: PsiType?, fixedName: String): Boolean =
        actual?.canonicalText == fixedName || actual?.canonicalText?.substringAfterLast('.') == fixedName.substringAfterLast('.')

    override fun handleElementRename(newElementName: String): PsiElement {
        val literal = element.text
        val newLiteral = literal.substring(0, 1) + newElementName + literal.substring(literal.length - 1)
        val newElement = JavaPsiFacade.getElementFactory(element.project)
            .createExpressionFromText(newLiteral, element) as PsiLiteralExpression
        return element.replace(newElement)
    }

    override fun bindToElement(elementToBindTo: PsiElement): PsiElement =
        throw IncorrectOperationException("Not supported")
}

/**
 * 给注解里的字符串字面量提供引用。
 */
class AnnotationReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(PsiLiteralExpression::class.java),
            MemberReferenceProvider(),
        )
    }
}

class MemberReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        if (element !is PsiLiteralExpression) return PsiReference.EMPTY_ARRAY
        if (element.value !is String) return PsiReference.EMPTY_ARRAY

        // 找到字面量所在的注解属性赋值
        val valuePair = PsiTreeUtil.getParentOfType(element, PsiNameValuePair::class.java, false) ?: return PsiReference.EMPTY_ARRAY
        val annotation = PsiTreeUtil.getParentOfType(valuePair, PsiAnnotation::class.java, false) ?: return PsiReference.EMPTY_ARRAY
        val attributeName = valuePair.name ?: "value"

        val annotationQName = annotation.qualifiedName ?: return PsiReference.EMPTY_ARRAY
        val contract = AnnotationContractRegistry.find(annotationQName, attributeName) ?: return PsiReference.EMPTY_ARRAY

        // 找到被注解的字段
        val field = PsiTreeUtil.getParentOfType(annotation, PsiField::class.java, false) ?: return PsiReference.EMPTY_ARRAY

        return arrayOf(AnnotationMemberReference(element, field, contract))
    }
}
