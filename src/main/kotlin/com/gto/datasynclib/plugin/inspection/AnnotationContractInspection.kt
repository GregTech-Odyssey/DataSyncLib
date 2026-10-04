package com.gto.datasynclib.plugin.inspection

import com.gto.datasynclib.plugin.registry.AnnotationContract
import com.gto.datasynclib.plugin.registry.AnnotationContractRegistry
import com.gto.datasynclib.plugin.registry.RefKind
import com.intellij.codeInspection.*
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

/**
 * 扩展方向 1 + P2：对注解字符串引用做契约校验。
 *
 * 校验项：
 *  - 引用成员不存在（unresolved）
 *  - 签名不匹配（返回类型 / 参数类型 / 参数个数）
 *  - 静态字段被写成实例方法、反之亦然
 *  - required 属性缺失（Conversion.toManaged）
 *  - 静态性不匹配（getDeclaredField + f.get(null) 要求 static）
 */
class AnnotationContractInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun getDisplayName(): String = "DataSyncLib annotation reference checks"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitNameValuePair(pair: PsiNameValuePair) {
                val value = pair.value ?: return
                if (value !is PsiLiteralExpression) return
                if (value.value !is String) return

                val annotation = PsiTreeUtil.getParentOfType(pair, PsiAnnotation::class.java, false) ?: return
                val contract = AnnotationContractRegistry.find(
                    annotation.qualifiedName ?: return,
                    pair.name ?: "value",
                ) ?: return
                val field = PsiTreeUtil.getParentOfType(annotation, PsiField::class.java, false) ?: return

                checkContract(holder, value, field, contract)
            }

            override fun visitAnnotation(annotation: PsiAnnotation) {
                // required 属性缺失检查
                val qName = annotation.qualifiedName ?: return
                val requiredContracts = AnnotationContractRegistry.findByAnnotation(qName)
                    .filter { it.required }
                for (contract in requiredContracts) {
                    val pair = annotation.findAttributeValue(contract.attributeName)
                    if (pair == null) {
                        holder.registerProblem(
                            annotation,
                            "Missing required attribute '${contract.attributeName}'",
                            ProblemHighlightType.ERROR,
                        )
                    }
                }
            }
        }

    private fun checkContract(
        holder: ProblemsHolder,
        literal: PsiLiteralExpression,
        owner: PsiField,
        contract: AnnotationContract,
    ) {
        val name = literal.value as? String ?: return
        val clazz = owner.containingClass ?: return

        when (contract.kind) {
            RefKind.STATIC_FIELD -> checkField(holder, literal, clazz, name, contract)
            RefKind.INSTANCE_METHOD -> checkMethod(holder, literal, clazz, name, owner, contract)
        }
    }

    private fun checkField(
        holder: ProblemsHolder,
        literal: PsiLiteralExpression,
        clazz: PsiClass,
        name: String,
        contract: AnnotationContract,
    ) {
        val target = clazz.findFieldByName(name, false) // getDeclaredField：不跨父类
        if (target == null) {
            holder.registerProblem(
                literal,
                "Cannot resolve field '$name' in ${clazz.name}",
                ProblemHighlightType.ERROR,
            )
            return
        }
        if (!target.hasModifierProperty(PsiModifier.STATIC)) {
            holder.registerProblem(
                literal,
                "Referenced field '$name' must be static (resolved via f.get(null))",
                ProblemHighlightType.ERROR,
            )
        }
        contract.staticFieldType?.let { expected ->
            if (!isAssignableToType(target.type, expected)) {
                holder.registerProblem(
                    literal,
                    "Field '$name' should be of type $expected",
                    ProblemHighlightType.WARNING,
                )
            }
        }
    }

    private fun checkMethod(
        holder: ProblemsHolder,
        literal: PsiLiteralExpression,
        clazz: PsiClass,
        name: String,
        owner: PsiField,
        contract: AnnotationContract,
    ) {
        val methods = clazz.findMethodsByName(name, true)
        if (methods.isEmpty()) {
            holder.registerProblem(
                literal,
                "Cannot resolve method '$name'",
                ProblemHighlightType.ERROR,
            )
            return
        }
        val fieldType = owner.type
        val matcher = MethodSignatureMatcher(contract, fieldType)
        val matched = methods.firstOrNull { matcher.matches(it) }
        if (matched == null) {
            val hint = matcher.describeExpectation()
            holder.registerProblem(
                literal,
                "No method '$name' matches expected signature $hint",
                ProblemHighlightType.WARNING,
            )
        }
    }

    private fun isAssignableToType(actual: PsiType?, expectedQName: String): Boolean =
        actual?.canonicalText == expectedQName ||
            actual?.canonicalText?.substringAfterLast('.') == expectedQName.substringAfterLast('.')
}

/**
 * 方法签名匹配器。与 AnnotationMemberReference.matchesContract 保持一致。
 */
class MethodSignatureMatcher(
    private val contract: AnnotationContract,
    private val fieldType: PsiType,
) {
    fun matches(method: PsiMethod): Boolean {
        val params = contract.expectedParamTypes ?: emptyList()
        val actual = method.parameterList.parameters.map { it.type }
        if (actual.size != params.size) return false
        params.forEachIndexed { i, expected ->
            if (expected == null) return@forEachIndexed
            if (expected == AnnotationContract.FIELD_TYPE) {
                if (actual[i] != fieldType) return false
            } else if (expected == AnnotationContractRegistry.BUFFER_FIELD_TYPE) {
                val t = actual[i]?.canonicalText
                if (t != "net.minecraft.network.FriendlyByteBuf" && t != "net.minecraft.network.RegistryFriendlyByteBuf") return false
            } else {
                val a = actual[i]?.canonicalText
                if (a != expected && a?.substringAfterLast('.') != expected.substringAfterLast('.')) return false
            }
        }
        if (contract.returnIsBoolean && method.returnType != PsiTypes.booleanType()) return false
        if (contract.returnIsVoid && method.returnType != PsiType.VOID) return false
        return true
    }

    fun describeExpectation(): String {
        val params = contract.expectedParamTypes ?: emptyList()
        val p = params.joinToString(", ") { e ->
            when (e) {
                null -> "?"
                AnnotationContract.FIELD_TYPE -> fieldType.presentableText
                AnnotationContractRegistry.BUFFER_FIELD_TYPE -> "FriendlyByteBuf"
                else -> e.substringAfterLast('.')
            }
        }
        val r = when {
            contract.returnIsBoolean -> "boolean"
            contract.returnIsVoid -> "void"
            else -> "…"
        }
        return "($p) -> $r"
    }
}


