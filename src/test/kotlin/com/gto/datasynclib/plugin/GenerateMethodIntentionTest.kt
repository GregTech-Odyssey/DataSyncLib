package com.gto.datasynclib.plugin

import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/**
 * Quick-fix 生成的骨架必须能通过编译。
 *
 * 回归背景：`generateMethodSource` 里基本类型的 return 语句按 `returnType.startsWith(...)`
 * 逐个判断，遗漏了 `char`（以及 `boolean` 只认小写前缀），会生成 `return null;` —— 编译不过。
 */
class GenerateMethodIntentionTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addClass(
            """
            package com.gto.datasynclib.annotations;
            public @interface SaveToDisk { String listener() default ""; String defaultValueGetter() default ""; }
            """.trimIndent(),
        )
    }

    /** 触发 quick-fix 并把生成的方法文本取出来。 */
    private fun generateFor(fieldType: String, attribute: String, returnHint: String): String {
        val psi =
            myFixture.configureByText(
                "Holder.java",
                """
                class Holder {
                    @com.gto.datasynclib.annotations.SaveToDisk($attribute = "on<caret>X")
                    private $fieldType value;
                }
                """.trimIndent(),
            ) as PsiJavaFile
        java.io.File(System.getProperty("java.io.tmpdir"), "dsh-intention.txt").appendText(
            "=== $fieldType / $attribute ===\n" +
                "caret element = ${psi.findElementAt(myFixture.editor.caretModel.offset)?.javaClass?.simpleName}\n" +
                "intention found = ${runCatching { myFixture.findSingleIntention("Generate referenced method").text }.getOrNull()}\n",
        )
        val intention = myFixture.findSingleIntention("Generate referenced method")
        val failure =
            runCatching { myFixture.launchAction(intention) }.exceptionOrNull()
        java.io.File(System.getProperty("java.io.tmpdir"), "dsh-intention.txt").appendText(
            "=== $fieldType / $attribute ===\n" +
                "caret element = ${psi.findElementAt(myFixture.editor.caretModel.offset)?.javaClass?.simpleName}\n" +
                "launch failure = ${failure?.let { it.javaClass.simpleName + ": " + it.message }}\n",
        )
        val clazz = (myFixture.file as PsiJavaFile).classes.first()
        val generated = clazz.findMethodsByName("onX", false).firstOrNull()
        assertNotNull("应生成方法 onX（$returnHint）", generated)
        return generated!!.text
    }

    fun testCharFieldGeneratesCompilableReturn() {
        val text = generateFor("char", "defaultValueGetter", "char 返回")
        assertFalse("char 字段不能生成 return null;（编译不过），实际=$text", text.contains("return null"))
        assertTrue("char 字段应生成字符字面量，实际=$text", text.contains("return '"))
    }

    fun testIntFieldGeneratesZero() {
        val text = generateFor("int", "defaultValueGetter", "int 返回")
        assertTrue("int 字段应生成 return 0;，实际=$text", text.contains("return 0;"))
    }

    fun testBooleanFieldGeneratesFalse() {
        val text = generateFor("boolean", "defaultValueGetter", "boolean 返回")
        assertTrue("boolean 字段应生成 return false;，实际=$text", text.contains("return false;"))
    }

    fun testStringFieldGeneratesNull() {
        val text = generateFor("java.lang.String", "defaultValueGetter", "引用类型返回")
        assertTrue("引用类型应生成 return null;，实际=$text", text.contains("return null;"))
    }
}
