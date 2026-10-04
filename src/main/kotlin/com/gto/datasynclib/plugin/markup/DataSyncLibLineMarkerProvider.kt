package com.gto.datasynclib.plugin.markup

import com.gto.datasynclib.plugin.registry.AnnotationContractRegistry
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiField
import com.intellij.psi.PsiNameValuePair
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.Icon

/**
 * 扩展方向 2（数据流可视化）：gutter 行标记。
 *
 * 在被 @SyncToClient/@SyncToServer/@SaveToDisk 注解的字段左侧显示图标：
 *   - ⚡ 同步字段（按 sync 类型区分 C2S / S2C）
 *   - 💾 持久化字段
 * 图标可点击导航到相关注解。
 */
class DataSyncLibLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun collectNavigationMarkers(
        element: com.intellij.psi.PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        if (element !is PsiField) return
        val annotations = element.annotations
        if (annotations.isEmpty()) return

        for (annotation in annotations) {
            val icon = iconFor(annotation) ?: continue
            val tooltip = tooltipFor(element, annotation)
            val builder = RelatedItemLineMarkerInfo(
                element.nameIdentifier ?: element,
                element.textRange,
                icon,
                null,
                null,
                null,
            )
            result.add(builder)
        }
    }

    /** 托管类型提示：字段有 @Conversion 时，tooltip 显示有效（托管）类型 */
    private fun tooltipFor(field: PsiField, annotation: PsiAnnotation): String? {
        val qName = annotation.qualifiedName ?: return null
        if (qName != "com.gto.datasynclib.annotations.Conversion") return null
        val effective = com.gto.datasynclib.plugin.registry.FieldContextResolver.effectiveType(field)
        if (effective == field.type) return null
        return "托管类型（@Conversion 后）: ${effective.presentableText}"
    }

    private fun iconFor(annotation: PsiAnnotation): Icon? {
        val qName = annotation.qualifiedName ?: return null
        return when (qName.substringAfterLast('.')) {
            "SyncToClient" -> AllIcons.Actions.Upload
            "SyncToServer" -> AllIcons.Actions.Download
            "SaveToDisk" -> AllIcons.Actions.MenuSaveall
            "Conversion" -> AllIcons.Actions.Refresh
            "Codec" -> AllIcons.Nodes.Record
            "Strategy" -> AllIcons.Nodes.Function
            "AdditionalHolder" -> AllIcons.Nodes.Tree
            "Generic" -> AllIcons.Nodes.Type
            else -> null
        }
    }
}

/**
 * 扩展方向 2：inlay hint —— 在注解字符串字面量上方显示解析后的签名。
 * （需 IDE 2021.3+ 的 InlayHints API，这里用 LineMarker 作为兼容替代，实际签名提示
 * 已在 inspection 的 describeExpectation() 中实现。）
 */
