package com.gto.datasynclib.plugin

import com.gto.datasynclib.plugin.inspection.AnnotationContractInspection
import com.gto.datasynclib.plugin.inspection.AnnotationGlobalUsageHelper
import com.gto.datasynclib.plugin.registry.FieldContextResolver

import com.intellij.codeInsight.daemon.ImplicitUsageProvider
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class PlatformCompatibilityTest : LightJavaCodeInsightFixtureTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addClass(
            """
            package com.gto.datasynclib.annotations;
            public @interface SyncToClient { String listener() default ""; }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.gto.datasynclib.annotations;
            public @interface Codec { String saveCodec() default ""; }
            """.trimIndent(),
        )
    }

    fun testImplicitUsageExtensionIsRegistered() {
        assertTrue(ImplicitUsageProvider.EP_NAME.extensionList.any { it is AnnotationGlobalUsageHelper })
    }

    fun testAnnotationReferencedMethodIsUsed() {
        val clazz =
            configureClass(
                """
            class Holder {
                @com.gto.datasynclib.annotations.SyncToClient(listener = "onChange")
                private int value;
                private void onChange(int newValue, int oldValue) {}
                private void unrelated() {}
            }
        """,
            )
        val provider = AnnotationGlobalUsageHelper()
        assertTrue(provider.isImplicitUsage(clazz.findMethodsByName("onChange", false).single()))
        assertFalse(provider.isImplicitUsage(clazz.findMethodsByName("unrelated", false).single()))
        assertFalse(provider.isImplicitUsage(clazz))
    }

    fun testStringReferenceNavigatesToMethod() {
        val clazz =
            configureClass(
                """
            class Holder {
                @com.gto.datasynclib.annotations.SyncToClient(listener = "onChange")
                private int value;
                private void onChange(int newValue, int oldValue) {}
            }
        """,
            )
        val literal = PsiTreeUtil.findChildOfType(clazz, PsiLiteralExpression::class.java)!!
        val method = clazz.findMethodsByName("onChange", false).single()
        assertTrue(literal.references.any { it.resolve() == method })
    }

    fun testInspectionReportsUnresolvedMember() {
        myFixture.enableInspections(AnnotationContractInspection())
        configureClass(
            """
            class Holder {
                @com.gto.datasynclib.annotations.SyncToClient(listener = "missing")
                private int value;
            }
        """,
        )
        assertTrue(myFixture.doHighlighting().any { it.description == "Cannot resolve method 'missing'" })
    }

    fun testAnnotationReferencedFieldIsReadButNotWritten() {
        val clazz =
            configureClass(
                """
            class Holder {
                private static final Object CODEC = new Object();
                @com.gto.datasynclib.annotations.Codec(saveCodec = "CODEC")
                private int value;
            }
        """,
            )
        val codec = clazz.findFieldByName("CODEC", false)!!
        val provider = AnnotationGlobalUsageHelper()
        assertTrue(provider.isImplicitUsage(codec))
        assertTrue(provider.isImplicitRead(codec))
        assertFalse(provider.isImplicitWrite(codec))
        assertFalse(provider.isImplicitRead(clazz.findFieldByName("value", false)!!))
    }

    fun testUnrelatedAnnotationDoesNotSuppressUnused() {
        val clazz =
            configureClass(
                """
            @interface Other { String listener(); }
            class Holder {
                @Other(listener = "onChange")
                private int value;
                private void onChange(int newValue, int oldValue) {}
            }
        """,
            )
        assertFalse(AnnotationGlobalUsageHelper().isImplicitUsage(clazz.findMethodsByName("onChange", false).single()))
    }

    fun testGenericArgumentsUsePsiParameterTypes() {
        val clazz =
            configureClass(
                """
            class Holder<T> {
                private Holder<java.lang.String> generic;
                private int primitive;
            }
        """,
            )
        val arguments = FieldContextResolver.genericArguments(clazz.findFieldByName("generic", false)!!)
        assertEquals(listOf("java.lang.String"), arguments.map { it.canonicalText })
        assertEmpty(FieldContextResolver.genericArguments(clazz.findFieldByName("primitive", false)!!))
    }

    private fun configureClass(source: String) = (myFixture.configureByText("Holder.java", source.trimIndent()) as PsiJavaFile).classes.last()
}
