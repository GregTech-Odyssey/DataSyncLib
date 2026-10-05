package com.gto.datasynclib.plugin

import com.gto.datasynclib.plugin.registry.DefaultValueTypeResolver

import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/**
 * 钉住 defaultValue 的解析规则，必须与运行期 `ReflectUtil.parse` 一致。
 *
 * 三档后果（见 DefaultValueTypeResolver 注释）：
 * - 数字型 / 不支持的引用类型：构造期抛异常 → 必须判为不兼容
 * - boolean：parseBoolean 静默返回 false → 非 true/false 必须判为不兼容
 * - 枚举：getEnum 查不到返回 null → 未知常量必须判为不兼容
 */
class DefaultValueTypeResolverTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addClass(
            """
            package com.gto.datasynclib.annotations;
            public @interface SaveToDisk { String defaultValue() default ""; }
            """.trimIndent(),
        )
    }

    /** 每次调用都新建一个文件，避免重名冲突。 */
    private fun fieldOf(type: String, index: Int = 0): PsiField {
        val psi =
            myFixture.configureByText(
                "Holder$index.java",
                """
                class Holder$index {
                    private $type value;
                }
                """.trimIndent(),
            ) as PsiJavaFile
        return psi.classes.first().fields.first()
    }

    private fun validate(type: String, text: String, index: Int = 0) = DefaultValueTypeResolver.validate(fieldOf(type, index).type, text)

    // ===== 数字型：填错会在 mod 加载期抛 NumberFormatException =====

    fun testValidIntAccepted() {
        assertTrue(validate("int", "42").compatible)
        assertTrue(validate("int", "-1", index = 1).compatible)
    }

    fun testInvalidIntRejected() {
        val r = validate("int", "4a2")
        assertFalse("'4a2' 不是合法 int，运行期会抛异常", r.compatible)
        assertTrue(r.message!!.contains("int"))
    }

    fun testInvalidDoubleRejected() {
        assertFalse(validate("double", "1.2.3").compatible)
    }

    fun testBoxedTypesValidatedToo() {
        assertTrue(validate("java.lang.Long", "99").compatible)
        assertFalse(validate("java.lang.Long", "x", index = 1).compatible)
    }

    // ===== boolean：最危险，parseBoolean 静默变 false =====

    fun testBooleanLiteralsAccepted() {
        assertTrue(validate("boolean", "true").compatible)
        assertTrue(validate("boolean", "false", index = 1).compatible)
    }

    fun testBooleanTypoRejected() {
        // Boolean.parseBoolean("flase") == false，不抛异常 => 必须拦下来
        val r = validate("boolean", "flase")
        assertFalse("拼错的 'flase' 会静默变成 false，必须报错", r.compatible)
        assertTrue(r.message!!.contains("false"))
    }

    fun testBooleanCaseSensitiveRejected() {
        // parseBoolean 只认小写 "true"，"TRUE" 会静默变 false
        assertFalse(validate("boolean", "TRUE").compatible)
        assertFalse(validate("boolean", "True", index = 1).compatible)
    }

    // ===== String：永不失败 =====

    fun testStringAlwaysAccepted() {
        assertTrue(validate("java.lang.String", "anything at all").compatible)
    }

    // ===== char：charAt(0)，非空即成立，仅提示长度 =====

    fun testCharSingleAccepted() {
        val r = validate("char", "x")
        assertTrue(r.compatible)
        assertNull(r.message)
    }

    fun testCharLongerWarnsButCompatible() {
        val r = validate("char", "xy")
        assertTrue("charAt(0) 不会失败，所以仍算兼容", r.compatible)
        assertNotNull("但应提示只取首字符", r.message)
    }

    // ===== 空串：运行期直接 return null，不算错误 =====

    fun testEmptyAlwaysAccepted() {
        val types = listOf("int", "boolean", "double", "java.lang.String", "char")
        for ((i, t) in types.withIndex()) {
            assertTrue("空串在运行期 return null，不应报错（type=$t）", validate(t, "", index = i).compatible)
        }
    }
}
