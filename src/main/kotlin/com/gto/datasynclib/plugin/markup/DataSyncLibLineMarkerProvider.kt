package com.gto.datasynclib.plugin.markup

import com.gto.datasynclib.plugin.registry.FieldContextResolver

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.util.PsiTreeUtil

import javax.swing.Icon

/**
 * 扩展方向 2（数据流可视化）：gutter 行标记 + 字段管线 tooltip。
 *
 * 在被 DataSyncLib 注解的字段左侧显示图标：
 *   - Upload/Download：@SyncToClient / @SyncToServer
 *   - MenuSaveall：@SaveToDisk
 *   - Refresh：@Conversion
 *   - Record：@Codec
 *   - Function：@Strategy
 *   - Class：@AdditionalHolder
 *   - Type：@Generic
 *
 * 每个图标的 tooltip 显示该注解的摘要；@Conversion 字段额外显示托管类型。
 */
class DataSyncLibLineMarkerProvider : RelatedItemLineMarkerProvider() {
    override fun collectNavigationMarkers(element: com.intellij.psi.PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
        if (element !is PsiField) return
        val annotations = element.annotations
        if (annotations.isEmpty()) return

        val target = element.nameIdentifier
        for (annotation in annotations) {
            val icon = iconFor(annotation) ?: continue
            val tooltip = tooltipFor(element, annotation)
            val navigationTarget = navigationTarget(element, annotation)
            val builder =
                NavigationGutterIconBuilder
                    .create(icon)
                    .setTarget(navigationTarget)
                    .setTooltipText(tooltip)
            result.add(builder.createLineMarkerInfo(target))
        }
    }

    /** @AdditionalHolder(flat) 的图标导航到嵌套类型；其它注解导航到注解本身 */
    private fun navigationTarget(field: PsiField, annotation: PsiAnnotation): PsiElement {
        val short = annotation.qualifiedName?.substringAfterLast('.') ?: ""
        if (short == "AdditionalHolder") {
            val childManager =
                annotation.findAttributeValue("childManager")?.let {
                    (it as? com.intellij.psi.PsiLiteralExpression)?.value as? Boolean
                } ?: false
            if (!childManager) {
                (field.type as? PsiClassType)?.resolve()?.let { return it }
            }
        }
        return PsiTreeUtil.getChildOfType(annotation, com.intellij.psi.PsiNameValuePair::class.java) ?: annotation
    }

    /** 摘要 tooltip：注解简名 + @Conversion 托管类型 */
    private fun tooltipFor(field: PsiField, annotation: PsiAnnotation): String {
        val short = annotation.qualifiedName?.substringAfterLast('.') ?: "annotation"
        return buildString {
            append(short)
            if (short == "Conversion") {
                val effective = FieldContextResolver.effectiveType(field)
                if (effective != field.type) {
                    append(" → 托管类型: ${effective.presentableText}")
                }
            }
        }
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
            "AdditionalHolder" -> AllIcons.Nodes.Class
            "Generic" -> AllIcons.Nodes.Type
            else -> null
        }
    }
}
