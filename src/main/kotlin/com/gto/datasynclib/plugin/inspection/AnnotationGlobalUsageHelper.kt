package com.gto.datasynclib.plugin.inspection

import com.gto.datasynclib.plugin.registry.AnnotationContractRegistry

import com.intellij.codeInsight.daemon.ImplicitUsageProvider
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

/**
 * 消除「未使用」误报：通过 IntelliJ 的 `ImplicitUsageProvider` 扩展点
 * 标记被注解字符串引用的方法，以及被反射读取的静态字段。
 *
 * 这里判定：若一个成员的**名字**被同文件内的 DataSyncLib 注解字符串字面量引用，
 * 则视为「已使用」，不再报 unused。
 */
class AnnotationGlobalUsageHelper : ImplicitUsageProvider {

    override fun isImplicitUsage(element: PsiElement): Boolean = when (element) {
        is PsiMethod -> isReferencedByAnnotation(element)
        is PsiField -> isReferencedByAnnotation(element)
        else -> false
    }

    override fun isImplicitRead(element: PsiElement): Boolean = element is PsiField && isReferencedByAnnotation(element)

    override fun isImplicitWrite(element: PsiElement): Boolean = false

    private fun isReferencedByAnnotation(element: PsiMember): Boolean {
        val name = element.name ?: return false
        val file = element.containingFile ?: return false
        return fileHasAnnotationStringReference(file, name)
    }

    private fun fileHasAnnotationStringReference(file: PsiFile, name: String): Boolean {
        if (file !is PsiJavaFile) return false
        var found = false
        file.accept(object : JavaRecursiveElementVisitor() {
            override fun visitNameValuePair(pair: PsiNameValuePair) {
                if (found) return
                val v = pair.value as? PsiLiteralExpression ?: return
                val s = v.value as? String ?: return
                if (s != name) return
                val ann = PsiTreeUtil.getParentOfType(pair, PsiAnnotation::class.java, false) ?: return
                val qName = ann.qualifiedName ?: return
                val attr = pair.name ?: "value"
                if (AnnotationContractRegistry.find(qName, attr) != null) {
                    found = true
                }
            }
        })
        return found
    }
}
