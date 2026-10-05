package com.gto.datasynclib.plugin

import com.gto.datasynclib.plugin.inspection.AnnotationContractInspection

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/**
 * `@SaveToDisk.key` 重复检测必须**继承感知**。
 *
 * 运行期 `FieldDefinitionStorage.get(clazz)` 会 `of(clazz, parentStorage)`，
 * 把父类的 definitionMap 一并放入再检测，其 javadoc 明说
 * "child classes may not reuse a parent's storage key"。
 * 插件原先只遍历本类字段，会漏掉"子类沿用父类 key"这一运行期崩溃场景。
 */
class DuplicateKeyInheritanceTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addClass(
            """
            package com.gto.datasynclib.annotations;
            public @interface SaveToDisk { String key() default ""; }
            """.trimIndent(),
        )
    }

    private fun highlights(source: String) = run {
        myFixture.enableInspections(AnnotationContractInspection())
        myFixture.configureByText("Holder.java", source.trimIndent())
        myFixture.doHighlighting()
    }

    private fun duplicateProblems(source: String) = highlights(source).filter { it.description?.contains("Duplicate field key") == true }

    /** 子类显式 key 与父类相同 —— 运行期会抛 Duplicate sync field key。 */
    fun testChildReusingParentExplicitKeyIsReported() {
        val hits =
            duplicateProblems(
                """
                class Base {
                    @com.gto.datasynclib.annotations.SaveToDisk(key = "health")
                    protected int baseHealth;
                }
                class Child extends Base {
                    @com.gto.datasynclib.annotations.SaveToDisk(key = "health")
                    private int childHealth;
                }
                """,
            )
        assertTrue("子类复用父类 key 应报重复，实际=${hits.map { it.description }}", hits.isNotEmpty())
        assertEquals("运行时必崩，应为 ERROR", HighlightSeverity.ERROR, hits.first().severity)
    }

    /** 子类字段名与父类字段名相同（都没写 key）→ 也用字段名做 key，冲突。 */
    fun testShadowedFieldNameIsReported() {
        val hits =
            duplicateProblems(
                """
                class Base {
                    @com.gto.datasynclib.annotations.SaveToDisk
                    protected int value;
                }
                class Child extends Base {
                    @com.gto.datasynclib.annotations.SaveToDisk
                    private int value;
                }
                """,
            )
        assertTrue("同名遮蔽字段的 key 相同，应报重复，实际=${hits.map { it.description }}", hits.isNotEmpty())
    }

    /** 反向确认：key 不同就不该报（避免"修过头"）。 */
    fun testDistinctKeysAcrossInheritanceNotReported() {
        val hits =
            duplicateProblems(
                """
                class Base {
                    @com.gto.datasynclib.annotations.SaveToDisk(key = "baseHealth")
                    protected int baseHealth;
                }
                class Child extends Base {
                    @com.gto.datasynclib.annotations.SaveToDisk(key = "childHealth")
                    private int childHealth;
                }
                """,
            )
        assertEmpty("key 不同不应报重复，实际=${hits.map { it.description }}", hits)
    }

    /** 三层继承：祖父与孙类冲突也要能查到。 */
    fun testGrandparentConflictReported() {
        val hits =
            duplicateProblems(
                """
                class A {
                    @com.gto.datasynclib.annotations.SaveToDisk(key = "k")
                    protected int a;
                }
                class B extends A {
                    @com.gto.datasynclib.annotations.SaveToDisk(key = "other")
                    protected int b;
                }
                class C extends B {
                    @com.gto.datasynclib.annotations.SaveToDisk(key = "k")
                    private int c;
                }
                """,
            )
        assertTrue("跨两层的 key 冲突也应报，实际=${hits.map { it.description }}", hits.isNotEmpty())
    }

    /**
     * 守护：不写 key 的普通（且不冲突）托管字段不能让 inspection 崩溃。
     *
     * `@SaveToDisk` 未写 key 时，findAttributeValue("key") 返回的是合成的默认值字面量
     * （DummyHolder 里的非物理元素）；若拿它当 registerProblem 的锚点会抛
     * "Non-physical PsiElement"。这条路径只有在真的出现重复 key 时才会走到，
     * 所以必须构造一个含未写 key 字段的正常类来覆盖。
     */
    fun testFieldsWithoutExplicitKeyDoNotCrash() {
        val highlights =
            highlights(
                """
                class Holder {
                    @com.gto.datasynclib.annotations.SaveToDisk
                    private int alpha;
                    @com.gto.datasynclib.annotations.SaveToDisk
                    private int beta;
                }
                """,
            )
        assertEmpty(
            "未写 key 且不冲突时不应有任何问题，实际=${highlights.map { it.description }}",
            highlights.filter { it.description?.contains("Duplicate") == true },
        )
    }

    /** 父类与子类都未写 key、但字段名不同 —— 也不应报重复。 */
    fun testInheritanceWithoutExplicitKeysNotReported() {
        val hits =
            duplicateProblems(
                """
                class Base {
                    @com.gto.datasynclib.annotations.SaveToDisk
                    protected int baseValue;
                }
                class Child extends Base {
                    @com.gto.datasynclib.annotations.SaveToDisk
                    private int childValue;
                }
                """,
            )
        assertEmpty("字段名不同则 key 不同，不应报重复，实际=${hits.map { it.description }}", hits)
    }
}
