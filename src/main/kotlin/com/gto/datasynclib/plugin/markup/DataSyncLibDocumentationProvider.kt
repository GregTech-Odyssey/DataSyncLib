package com.gto.datasynclib.plugin.markup

import com.gto.datasynclib.plugin.registry.FieldContextResolver

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.util.PsiTreeUtil

/**
 * 类 tooltip（Documentation）增强：在 hover 类名时，展示该类及其父类中被
 * DataSyncLib 注解的字段清单与注解信息（镜像 `FieldDefinitionStorage.get()` 的
 * 继承链合并规则）。
 *
 * 输出格式：
 * ```
 * <b>DataSyncLib 字段</b>
 * ─── 本类 (MyHolder) ───
 *   ⚡ energy : long  [SyncToClient, SaveToDisk]
 *   💾 inventory : InventoryData  [SaveToDisk, AdditionalHolder]
 * ─── 父类 (BaseHolder) ───
 *   💾 id : int  [SaveToDisk]
 * ```
 */
class DataSyncLibDocumentationProvider : AbstractDocumentationProvider() {
    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val target = element ?: originalElement ?: return null
        val clazz = target as? PsiClass ?: PsiTreeUtil.getParentOfType(target, PsiClass::class.java, false) ?: return null
        val managed = collectManagedFields(clazz)
        if (managed.isEmpty()) return null
        return render(clazz, managed)
    }

    /** 按继承层级收集被注解字段：返回 (层级名, 字段列表) 的有序列表，父类在前 */
    private fun collectManagedFields(clazz: PsiClass): List<Pair<String, List<PsiField>>> {
        // 从根（最顶层父类）到当前类，逐层收集，镜像 get() 的递归合并（父类字段先注册）
        val hierarchy = ArrayList<PsiClass>()
        var c: PsiClass? = clazz
        while (c != null && c.name != null && c.name != "Object") {
            hierarchy.add(0, c) // 头部插入，保证父类在前
            c = c.superClass
        }
        val result = ArrayList<Pair<String, List<PsiField>>>()
        for (level in hierarchy) {
            val fields = level.fields.filter { isManagedField(it) }
            if (fields.isNotEmpty()) {
                val label = if (level == clazz) "本类 (${level.name})" else "父类 (${level.name})"
                result.add(label to fields)
            }
        }
        return result
    }

    private fun isManagedField(field: PsiField): Boolean {
        if (field.hasModifierProperty("static")) return false
        return field.annotations.any { isDataSyncLibAnnotation(it.qualifiedName) }
    }

    private fun isDataSyncLibAnnotation(qName: String?): Boolean = qName?.startsWith("com.gto.datasynclib.annotations.") == true

    private fun render(clazz: PsiClass, managed: List<Pair<String, List<PsiField>>>): String {
        val sb = StringBuilder()
        sb.append("<b>DataSyncLib 字段</b>")
        for ((label, fields) in managed) {
            sb
                .append("<br/>")
                .append("<b>─── ")
                .append(escapeHtml(label))
                .append(" ───</b>")
            for (field in fields) {
                sb.append("<br/>")
                sb.append("&nbsp;&nbsp;")
                sb.append(fieldShortDesc(field))
            }
        }
        return sb.toString()
    }

    private fun fieldShortDesc(field: PsiField): String {
        val anns =
            field.annotations
                .mapNotNull { it.qualifiedName?.substringAfterLast('.') }
                .filter { it in ANNOTATION_SHORT_NAMES }
        val effective = FieldContextResolver.effectiveType(field)
        val typeText =
            if (effective != field.type) {
                "${field.type.presentableText} → ${effective.presentableText}"
            } else {
                field.type.presentableText
            }
        val annText = if (anns.isNotEmpty()) "&nbsp;<code>[${anns.joinToString(", ")}]</code>" else ""
        return "<code>${escapeHtml(field.name)}</code> : ${escapeHtml(typeText)}$annText"
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    companion object {
        private val ANNOTATION_SHORT_NAMES =
            setOf(
                "SaveToDisk",
                "SyncToClient",
                "SyncToServer",
                "Conversion",
                "Codec",
                "Strategy",
                "AdditionalHolder",
                "Generic",
                "Access",
                "AddToManager",
            )
    }
}
