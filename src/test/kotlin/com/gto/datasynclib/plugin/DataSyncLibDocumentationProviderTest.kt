package com.gto.datasynclib.plugin

import com.gto.datasynclib.plugin.markup.DataSyncLibDocumentationProvider

import com.intellij.lang.LanguageDocumentation
import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/**
 * 钉住 tooltip 的目标解析：
 * 类名标识符必须能解析到 PsiClass 并渲染；
 * 无注解字段的类必须返回 null（不劫持内置 JavaDoc）。
 */
class DataSyncLibDocumentationProviderTest : LightJavaCodeInsightFixtureTestCase() {
    private val provider = DataSyncLibDocumentationProvider()

    private fun file(source: String): PsiJavaFile {
        myFixture.addClass(
            """
            package com.gto.datasynclib.annotations;
            public @interface SyncToClient { String listener() default ""; }
            """.trimIndent(),
        )
        return myFixture.configureByText("Holder.java", source.trimIndent()) as PsiJavaFile
    }

    fun testCustomElementResolvesClassFromClassNameIdentifier() {
        val psi = file(
            """
            class Holder {
                @com.gto.datasynclib.annotations.SyncToClient(listener = "onChange")
                private int value;
            }
            """,
        )
        val offset = psi.text.indexOf("Holder {")
        val contextElement = psi.findElementAt(offset)!!
        val resolved = provider.getCustomDocumentationElement(myFixture.editor, psi, contextElement, offset)
        assertTrue("类名标识符应解析为 PsiClass，实际: $resolved", resolved is PsiClass)
        assertEquals("Holder", (resolved as PsiClass).name)
    }

    fun testCustomElementReturnsNullWhenNoManagedFields() {
        val psi =
            file(
                """
                class Plain {
                    private int value;
                }
                """,
            )
        val offset = psi.text.indexOf("Plain {")
        val contextElement = psi.findElementAt(offset)!!
        assertNull(provider.getCustomDocumentationElement(myFixture.editor, psi, contextElement, offset))
    }

    /**
     * 回归：平台拿到非 null 的自定义文档元素后会整体替换光标处的文档目标，
     * 因此类体内的元素（方法名/字段名/注解名）绝不能被改写成"类的文档"。
     */
    fun testCustomElementDoesNotHijackMembersInsideClass() {
        val psi =
            file(
                """
                class Holder {
                    @com.gto.datasynclib.annotations.SyncToClient(listener = "onChange")
                    private int value;
                    private void onChange(int a, int b) {}
                }
                """,
            )
        val positions =
            mapOf(
                "方法名" to "onChange(int",
                "字段名" to "value;",
                "注解名" to "SyncToClient(",
            )
        for ((label, needle) in positions) {
            val offset = psi.text.indexOf(needle)
            val contextElement = psi.findElementAt(offset)!!
            val resolved = provider.getCustomDocumentationElement(myFixture.editor, psi, contextElement, offset)
            assertNull(
                "$label 位置不应被接管（否则会顶掉该方法/字段自己的文档），实际解析为: $resolved",
                resolved,
            )
        }
    }

    /** 反向确认：类名本身仍必须被接管，不能"修过头"。 */
    fun testCustomElementStillHijacksClassName() {
        val psi =
            file(
                """
                class Holder {
                    @com.gto.datasynclib.annotations.SyncToClient(listener = "onChange")
                    private int value;
                    private void onChange(int a, int b) {}
                }
                """,
            )
        val offset = psi.text.indexOf("Holder {")
        val contextElement = psi.findElementAt(offset)!!
        val resolved = provider.getCustomDocumentationElement(myFixture.editor, psi, contextElement, offset)
        assertTrue("类名位置必须仍被接管", resolved is PsiClass)
    }

    /** 嵌套类：内层类名应解析到内层类，而不是外层类。 */
    fun testCustomElementResolvesInnermostClass() {
        val psi =
            file(
                """
                class Outer {
                    @com.gto.datasynclib.annotations.SyncToClient(listener = "onChange")
                    private int outerValue;

                    static class Inner {
                        @com.gto.datasynclib.annotations.SyncToClient(listener = "onChange")
                        private int innerValue;
                    }
                }
                """,
            )
        val offset = psi.text.indexOf("Inner {")
        val contextElement = psi.findElementAt(offset)!!
        val resolved = provider.getCustomDocumentationElement(myFixture.editor, psi, contextElement, offset)
        assertTrue("内层类名应解析为 PsiClass，实际: $resolved", resolved is PsiClass)
        assertEquals("Inner", (resolved as PsiClass).name)
    }

    fun testGenerateDocRendersManagedFieldsFromClassName() {
        val psi =
            file(
                """
                class Holder {
                    @com.gto.datasynclib.annotations.SyncToClient(listener = "onChange")
                    private int value;
                }
                """,
            )
        val offset = psi.text.indexOf("Holder {")
        val contextElement = psi.findElementAt(offset)!!
        val resolved = provider.getCustomDocumentationElement(myFixture.editor, psi, contextElement, offset)
        val doc = provider.generateDoc(resolved, contextElement)
        assertNotNull("类名位置应渲染出字段清单", doc)
        assertTrue(doc!!.contains("DataSyncLib"))
        assertTrue("应含字段链接", doc.contains("psi_element://Holder#value"))
    }

    /**
     * 回归：注册为 order="first" 后本 provider 会先于内置 provider 命中，
     * 必须自己把其他 provider 的输出拼回来，否则原有 JavaDoc 会被短路掉。
     * 这里断言的是「拼接」这一行为本身：其他 provider 有多少内容，
     * 我们的结果里就必须包含多少。
     */
    fun testMergesOtherProvidersInsteadOfShortCircuiting() {
        val psi =
            file(
                """
                class Holder {
                    @com.gto.datasynclib.annotations.SyncToClient(listener = "onChange")
                    private int value;
                }
                """,
            )
        val offset = psi.text.indexOf("Holder {")
        val contextElement = psi.findElementAt(offset)!!
        val resolved = provider.getCustomDocumentationElement(myFixture.editor, psi, contextElement, offset)

        val others =
            LanguageDocumentation.INSTANCE
                .allForLanguage(JavaLanguage.INSTANCE)
                .filterNot { it is DataSyncLibDocumentationProvider }
                .mapNotNull { runCatching { it.generateDoc(resolved, contextElement) }.getOrNull() }
                .filter { it.isNotBlank() }
        val doc = provider.generateDoc(resolved, contextElement)!!

        assertTrue("本 provider 的内容必须存在", doc.contains("DataSyncLib"))
        assertTrue("测试环境应至少有一个其他 provider 产出内容，否则本用例是空转", others.isNotEmpty())
        for (other in others) {
            assertTrue("其他 provider 的输出被短路了，缺失片段: ${other.take(60)}", doc.contains(other))
        }
    }
}
