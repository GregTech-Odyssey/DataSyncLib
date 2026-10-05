package com.gto.datasynclib.plugin

import com.gto.datasynclib.plugin.inspection.AnnotationContractInspection
import com.gto.datasynclib.plugin.registry.AnnotationContractRegistry

import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/**
 * 静态字段引用的类型校验（`@Conversion.toManaged` 的 `Function`）。
 *
 * 回归背景：原实现按 `canonicalText` 做字符串比较，而参数化类型的 canonicalText 带泛型实参
 * （`java.util.function.Function<A,B>`），永远不等于裸类型名 `java.util.function.Function`，
 * 导致**所有带泛型的静态字段都被误报** —— 只有写成裸类型 `Function` 才"恰好"通过。
 *
 * 注意：契约注册表里是真实 FQN，所以这里必须用真实注解名，否则契约查不到、
 * 校验整体不执行，用例会假通过。
 */
class FieldTypeCheckTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addClass(
            """
            package com.gto.datasynclib.annotations;
            public @interface Conversion { String toManaged() default ""; String toField() default ""; }
            """.trimIndent(),
        )
        myFixture.addClass(
            """
            package com.gto.datasynclib.testdata;
            public class RecipeLogic {}
            """.trimIndent(),
        )
        // 让 "Function" 这个简单名在该包里可解析（模拟用户 import java.util.function.Function）
        myFixture.addClass(
            """
            package com.gto.datasynclib.testdata;
            public interface Function<A, B> {}
            """.trimIndent(),
        )
    }

    /** 前提校验：契约必须存在，否则后面所有断言都是假通过。 */
    fun testContractExistsForToManaged() {
        assertNotNull(
            "com.gto.datasynclib.annotations.Conversion.toManaged 必须在契约注册表里",
            AnnotationContractRegistry.find("com.gto.datasynclib.annotations.Conversion", "toManaged"),
        )
        assertEquals(
            "该契约要求的静态字段类型",
            "java.util.function.Function",
            AnnotationContractRegistry.find("com.gto.datasynclib.annotations.Conversion", "toManaged")!!.staticFieldType,
        )
    }

    private fun problems(source: String): List<String> {
        myFixture.enableInspections(AnnotationContractInspection())
        myFixture.configureByText("Holder.java", source.trimIndent())
        return myFixture.doHighlighting().mapNotNull { it.description }
    }

    private fun typeWarnings(problems: List<String>) = problems.filter { it.contains("should be of type") }

    /** 前提校验：注解 FQN 能解析、契约能命中，校验链路才会执行。 */
    fun testAnnotationResolvesToContract() {
        val psi =
            myFixture.configureByText(
                "Holder.java",
                """
                class Holder {
                    @com.gto.datasynclib.annotations.Conversion(toManaged = "FN")
                    private int value;
                }
                """.trimIndent(),
            ) as PsiJavaFile
        val ann = psi.classes.first().fields.first().annotations.first()
        assertEquals("com.gto.datasynclib.annotations.Conversion", ann.qualifiedName)
    }

    /**
     * 核心回归：漏了 @Conversion 的 required 之外，这里主要验证"参数化类型不误报"。
     * fixture 无 JDK，`java.util.function.Function` 解析不到，会走"剥泛型后比全限定名"的降级路径。
     */
    fun testParameterizedFunctionNotReported() {
        val ps =
            problems(
                """
                import java.util.function.Function;

                class Holder {
                    private static final Function<Integer, Integer> FN = null;

                    @com.gto.datasynclib.annotations.Conversion(toManaged = "FN")
                    private int value;
                }
                """,
            )
        assertEmpty("参数化的 Function 不应被误报，实际=$ps", typeWarnings(ps))
    }

    /** 裸类型不能改坏。 */
    fun testRawFunctionNotReported() {
        val ps =
            problems(
                """
                import java.util.function.Function;

                class Holder {
                    private static final Function FN = null;

                    @com.gto.datasynclib.annotations.Conversion(toManaged = "FN")
                    private int value;
                }
                """,
            )
        assertEmpty("裸 Function 不应被误报，实际=$ps", typeWarnings(ps))
    }

    /** 类型真的不对时必须仍然报出来 —— 不能"修过头"。 */
    fun testGenuinelyWrongTypeStillReported() {
        val ps =
            problems(
                """
                import com.gto.datasynclib.testdata.RecipeLogic;

                class Holder {
                    private static final RecipeLogic NOT_A_FUNCTION = new RecipeLogic();

                    @com.gto.datasynclib.annotations.Conversion(toManaged = "NOT_A_FUNCTION")
                    private int value;
                }
                """,
            )
        assertTrue(
            "类型确实不对时必须报错，实际=$ps",
            typeWarnings(ps).any { it.contains("NOT_A_FUNCTION") },
        )
    }
}
