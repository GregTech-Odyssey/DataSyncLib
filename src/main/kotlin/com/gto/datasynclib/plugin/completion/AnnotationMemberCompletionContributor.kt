package com.gto.datasynclib.plugin.completion

import com.gto.datasynclib.plugin.registry.AnnotationContract
import com.gto.datasynclib.plugin.registry.AnnotationContractRegistry
import com.gto.datasynclib.plugin.registry.RefKind
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiTreeUtil

/**
 * P3：注解字符串属性的补全。
 *
 * 输入引号时，列出当前类/父类里符合该契约签名的方法名 / 字段名。
 */
class AnnotationMemberCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(PsiLiteralExpression::class.java),
            MemberCompletionProvider(),
        )
    }
}

class MemberCompletionProvider : CompletionProvider<CompletionParameters>() {

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet,
    ) {
        val literal = parameters.position.parent as? PsiLiteralExpression ?: return
        val pair = PsiTreeUtil.getParentOfType(literal, com.intellij.psi.PsiNameValuePair::class.java, false) ?: return
        val annotation = PsiTreeUtil.getParentOfType(pair, com.intellij.psi.PsiAnnotation::class.java, false) ?: return
        val contract = AnnotationContractRegistry.find(
            annotation.qualifiedName ?: return,
            pair.name ?: "value",
        ) ?: return
        val field = PsiTreeUtil.getParentOfType(annotation, PsiField::class.java, false) ?: return
        val clazz = field.containingClass ?: return

        when (contract.kind) {
            RefKind.STATIC_FIELD -> {
                clazz.allFields
                    .filter { it.hasModifierProperty("static") }
                    .forEach { result.addElement(LookupElementBuilder.create(it.name)) }
            }
            RefKind.INSTANCE_METHOD -> {
                val methods = clazz.allMethods
                val matcher = com.gto.datasynclib.plugin.inspection.MethodSignatureMatcher(contract, field.type)
                methods
                    .filter { matcher.matches(it) }
                    .distinctBy { it.name }
                    .forEach { result.addElement(LookupElementBuilder.create(it.name)) }
            }
        }
    }
}
