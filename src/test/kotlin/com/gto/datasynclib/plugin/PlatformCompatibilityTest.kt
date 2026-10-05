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

    /**
     * 回归：defaultValue 校验曾因 `tryParseNumber` 里的 `return try { ...; true }`
     * 恒为 true 而完全失效，任何错值都不报错。
     */
    fun testInspectionReportsInvalidDefaultValueForBoolean() {
        myFixture.enableInspections(AnnotationContractInspection())
        myFixture.addClass(
            """
            package com.gto.datasynclib.annotations;
            public @interface SaveToDisk { String defaultValue() default ""; }
            """.trimIndent(),
        )
        configureClass(
            """
            class Holder {
                @com.gto.datasynclib.annotations.SaveToDisk(defaultValue = "flase")
                private boolean flag;
            }
        """,
        )
        val problems = myFixture.doHighlighting()
        assertTrue(
            "拼错的 boolean 默认值必须被报出来，实际 problems=$problems",
            problems.any { it.description?.contains("flase") == true },
        )
    }

    fun testInspectionReportsInvalidDefaultValueForInt() {
        myFixture.enableInspections(AnnotationContractInspection())
        myFixture.addClass(
            """
            package com.gto.datasynclib.annotations;
            public @interface SaveToDisk { String defaultValue() default ""; }
            """.trimIndent(),
        )
        configureClass(
            """
            class Holder {
                @com.gto.datasynclib.annotations.SaveToDisk(defaultValue = "4a2")
                private int count;
            }
        """,
        )
        assertTrue(
            "非法 int 默认值必须被报出来",
            myFixture.doHighlighting().any { it.description?.contains("4a2") == true },
        )
    }

    fun testInspectionAcceptsValidDefaultValue() {
        myFixture.enableInspections(AnnotationContractInspection())
        myFixture.addClass(
            """
            package com.gto.datasynclib.annotations;
            public @interface SaveToDisk { String defaultValue() default ""; }
            """.trimIndent(),
        )
        configureClass(
            """
            class Holder {
                @com.gto.datasynclib.annotations.SaveToDisk(defaultValue = "true")
                private boolean flag;
            }
        """,
        )
        val problems = myFixture.doHighlighting().filter { it.description?.contains("defaultValue") == true }
        assertEmpty("合法默认值不应报错，实际=$problems", problems)
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

    /**
     * 实参契约要求非静态。运行期 `lookup.unreflect(staticMethod)` 得到的 handle 没有接收者，
     * 而调用处是 `invokeExact(source, value)`，参数个数不匹配 → WrongMethodTypeException。
     */
    fun testInspectionReportsStaticListenerAsSignatureMismatch() {
        myFixture.enableInspections(AnnotationContractInspection())
        myFixture.addClass(
            """
            package com.gto.datasynclib.annotations;
            public @interface SaveToDisk { String listener() default ""; }
            """.trimIndent(),
        )
        configureClass(
            """
            class Holder {
                @com.gto.datasynclib.annotations.SaveToDisk(listener = "onLoaded")
                private int value;
                private static void onLoaded(int v) {}
            }
        """,
        )
        val problems = myFixture.doHighlighting()
        assertTrue(
            "静态方法不能用作 listener，应报签名不匹配，实际 problems=$problems",
            problems.any { it.description?.contains("onLoaded") == true },
        )
    }

    private fun configureClass(source: String) = (myFixture.configureByText("Holder.java", source.trimIndent()) as PsiJavaFile).classes.last()
}
