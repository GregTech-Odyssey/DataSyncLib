package com.gto.datasynclib.plugin.inspection

import com.gto.datasynclib.plugin.registry.AnnotationContractRegistry
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil

/**
 * 扩展方向（消除「未使用」误报）：实现 IntelliJ 的 `GlobalUsageHelper` 扩展点。
 *
 * 当 UnusedDeclarationInspection 判断一个方法/字段是否被使用时，会调用
 * `globalUsageHelper.isDeclaredInOpenapiExtensions` / `shouldTreatAsUsed`（不同版本签名略有差异）。
 *
 * 这里判定：若一个成员的**名字**被同文件内的 DataSyncLib 注解字符串字面量引用，
 * 则视为「已使用」，不再报 unused。
 */
class AnnotationGlobalUsageHelper : com.intellij.codeInsight.daemon.impl.analysis.GlobalUsageHelper() {

    private val log = Logger.getInstance(javaClass)

    override fun shouldTreatAsUsed(element: PsiMember): Boolean {
        if (super.shouldTreatAsUsed(element)) return true
        return try {
            isReferencedByAnnotation(element)
        } catch (e: Exception) {
            log.warn("DataSyncLib usage check failed", e)
            false
        }
    }

    override fun isCurrentFileUpdated(element: PsiElement): Boolean {
        return super.isCurrentFileUpdated(element)
    }

    override fun isTrackedBySettings(element: PsiMember): Boolean {
        // 让被引用成员进入 tracked 集合，从而触发 shouldTreatAsUsed
        return super.isTrackedBySettings(element) || isReferencedByAnnotation(element)
    }

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
