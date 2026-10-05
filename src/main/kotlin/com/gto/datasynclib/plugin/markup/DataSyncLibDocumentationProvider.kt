package com.gto.datasynclib.plugin.markup

import com.gto.datasynclib.plugin.registry.FieldContextResolver

import com.intellij.lang.LanguageDocumentation
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIdentifier
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
    /**
     * 只在光标位于**类名标识符本身**时接管，返回该 [PsiClass]。
     *
     * 平台拿到非 null 的返回值后会整体替换光标处的文档目标，所以这里必须严格限定范围：
     * 早先版本用 `getParentOfType(element, PsiClass, false)` 向上找类，导致类体内**任何**
     * 元素（方法名、字段名、注解…）都会被改写成"类的文档"。
     */
    override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? {
        val identifier = contextElement as? PsiIdentifier ?: return null
        val clazz = PsiTreeUtil.getParentOfType(identifier, PsiClass::class.java, true) ?: return null
        if (clazz.nameIdentifier != identifier) return null
        if (collectManagedFields(clazz).isEmpty()) return null
        return clazz
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val target = element ?: originalElement ?: return null
        val clazz = target as? PsiClass ?: PsiTreeUtil.getParentOfType(target, PsiClass::class.java, false) ?: return null
        val managed = collectManagedFields(clazz)
        if (managed.isEmpty()) return null
        return documentationOf(clazz, target) + render(clazz, managed)
    }

    /**
     * 拼接其他 provider（内置 JavaDoc 等）对同一元素的输出。
     *
     * 本 provider 注册为 `order="first"`，而语言的文档 provider 是
     * `CompositeDocumentationProvider` —— 它按顺序取第一个非 null 结果，
     * 因此这里必须主动把后续 provider 的内容取回来，否则会把原有 JavaDoc 短路掉。
     */
    private fun documentationOf(clazz: PsiClass, at: PsiElement): String {
        if (MERGE_GUARD.get()) return ""
        MERGE_GUARD.set(true)
        try {
            return LanguageDocumentation.INSTANCE
                .allForLanguage(clazz.language)
                .asSequence()
                .filterNot { it is DataSyncLibDocumentationProvider }
                .mapNotNull { runCatching { it.generateDoc(clazz, at) }.getOrNull() }
                .filter { it.isNotBlank() }
                .joinToString("<br/>")
        } finally {
            MERGE_GUARD.set(false)
        }
    }

    /** 按继承层级收集被注解字段：返回 (层级名, 字段列表) 的有序列表，父类在前 */
    private fun collectManagedFields(clazz: PsiClass): List<Pair<PsiClass, List<PsiField>>> {
        // 从根（最顶层父类）到当前类，逐层收集，镜像 get() 的递归合并（父类字段先注册）
        val hierarchy = ArrayList<PsiClass>()
        var c: PsiClass? = clazz
        while (c != null && c.name != null && c.name != "Object") {
            hierarchy.add(0, c) // 头部插入，保证父类在前
            c = c.superClass
        }
        val result = ArrayList<Pair<PsiClass, List<PsiField>>>()
        for (level in hierarchy) {
            val fields = level.fields.filter { isManagedField(it) }
            if (fields.isNotEmpty()) {
                result.add(level to fields)
            }
        }
        return result
    }

    private fun isManagedField(field: PsiField): Boolean {
        if (field.hasModifierProperty("static")) return false
        return field.annotations.any { isDataSyncLibAnnotation(it.qualifiedName) }
    }

    private fun isDataSyncLibAnnotation(qName: String?): Boolean = qName?.startsWith("com.gto.datasynclib.annotations.") == true

    private fun render(clazz: PsiClass, managed: List<Pair<PsiClass, List<PsiField>>>): String {
        val sb = StringBuilder()
        sb.append("<b>DataSyncLib 字段</b>")
        for ((level, fields) in managed) {
            val kind = if (level == clazz) "本类" else "父类"
            sb
                .append("<br/>")
                .append("<b>─── ")
                .append(kind)
                .append(" (")
                .append(link(level.qualifiedName, level.name ?: ""))
                .append(") ───</b>")
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
        val fieldLink = field.containingClass?.qualifiedName?.let { "$it#${field.name}" }
        return "<code>${link(fieldLink, field.name)}</code> : ${escapeHtml(typeText)}$annText"
    }

    private fun link(target: String?, label: String): String {
        val text = escapeHtml(label)
        return if (target == null) text else "<a href=\"psi_element://${escapeHtml(target)}\">$text</a>"
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    companion object {
        /** 防止 [documentationOf] 在收集其他 provider 输出时递归回自己。 */
        private val MERGE_GUARD = ThreadLocal.withInitial { false }

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
