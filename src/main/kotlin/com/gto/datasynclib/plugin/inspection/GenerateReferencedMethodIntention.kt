package com.gto.datasynclib.plugin.inspection

import com.gto.datasynclib.plugin.registry.AnnotationContractRegistry
import com.gto.datasynclib.plugin.registry.FieldContextResolver
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException

/**
 * Quick-fix：为注解字符串引用的 listener / skipWhen / defaultValueGetter / Codec 方法
 * 生成符合签名的方法骨架。
 *
 * 触发：光标在某个注解的字符串字面量（如 `listener = "onX"`）上，Alt+Enter。
 * 当目标方法不存在时，生成一个签名正确的方法体。
 */
class GenerateReferencedMethodIntention : PsiElementBaseIntentionAction() {

    override fun getText(): String = "Generate referenced method"

    override fun getFamilyName(): String = "DataSyncLib"

    override fun isAvailable(project: Project, editor: Editor, element: PsiElement): Boolean {
        val ctx = resolveContext(element) ?: return false
        // 仅当目标方法不存在时才可用（避免覆盖已有方法）
        val clazz = ctx.field.containingClass ?: return false
        val methods = clazz.findMethodsByName(ctx.name, true)
        return methods.none { MethodSignatureMatcher(ctx.contract, ctx.effectiveType).matches(it) }
    }

    override fun invoke(project: Project, editor: Editor, element: PsiElement) {
        val ctx = resolveContext(element) ?: return
        val clazz = ctx.field.containingClass ?: return
        val matcher = MethodSignatureMatcher(ctx.contract, ctx.effectiveType)
        val body = generateMethodSource(ctx.contract, ctx.name, ctx.effectiveType, matcher)
        val factory = JavaPsiFacade.getElementFactory(project)
        val method = factory.createMethodFromText(body, clazz)
        clazz.add(method)
    }

    private data class Ctx(
        val field: PsiField,
        val contract: com.gto.datasynclib.plugin.registry.AnnotationContract,
        val name: String,
        val effectiveType: PsiType,
    )

    private fun resolveContext(element: PsiElement): Ctx? {
        if (element !is PsiLiteralExpression) return null
        if (element.value !is String) return null
        val pair = PsiTreeUtil.getParentOfType(element, PsiNameValuePair::class.java, false) ?: return null
        val annotation = PsiTreeUtil.getParentOfType(pair, PsiAnnotation::class.java, false) ?: return null
        val contract = AnnotationContractRegistry.find(
            annotation.qualifiedName ?: return null,
            pair.name ?: "value",
        ) ?: return null
        if (contract.kind != com.gto.datasynclib.plugin.registry.RefKind.INSTANCE_METHOD) return null
        val field = PsiTreeUtil.getParentOfType(annotation, PsiField::class.java, false) ?: return null
        val name = element.value as String
        val effectiveType = FieldContextResolver.effectiveType(field)
        return Ctx(field, contract, name, effectiveType)
    }

    private fun generateMethodSource(
        contract: com.gto.datasynclib.plugin.registry.AnnotationContract,
        name: String,
        fieldType: PsiType,
        matcher: MethodSignatureMatcher,
    ): String {
        val paramTypes = contract.expectedParamTypes ?: emptyList()
        val params = paramTypes.mapIndexed { i, e ->
            val t = when {
                e == com.gto.datasynclib.plugin.registry.AnnotationContract.FIELD_TYPE -> fieldType.canonicalText
                e == AnnotationContractRegistry.BUFFER_FIELD_TYPE -> "net.minecraft.network.FriendlyByteBuf"
                e != null -> e
                else -> "Object"
            }
            "$t arg$i"
        }
        val returnType = when {
            contract.returnIsBoolean -> "boolean"
            contract.returnIsVoid -> "void"
            else -> fieldType.canonicalText
        }
        val returnStmt = when {
            contract.returnIsBoolean -> "return false;"
            contract.returnIsVoid -> ""
            returnType == "void" -> ""
            returnType.startsWith("boolean") -> "return false;"
            returnType.startsWith("int") || returnType.startsWith("long") || returnType.startsWith("short") ||
                returnType.startsWith("byte") || returnType.startsWith("double") || returnType.startsWith("float") -> "return 0;"
            else -> "return null;"
        }
        return buildString {
            append("    public $returnType $name(")
            append(params.joinToString(", "))
            append(") {\n")
            append("        $returnStmt\n")
            append("    }")
        }
    }
}
