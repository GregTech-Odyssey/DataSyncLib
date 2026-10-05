package com.gto.datasynclib.plugin

import com.gto.datasynclib.plugin.inspection.AnnotationContractInspection

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/**
 * 按运行期 `FieldAnnotationMetadata` 的真实语义校验注解契约。
 *
 * 覆盖本轮从运行期源码核对出的四处偏差：
 * 1. `@Conversion.toField` 缺失字段是"静默跳过"，不该报 ERROR
 * 2. `@Codec.readFromData`/`readFromBuffer` 的返回类型必须等于字段类型 T
 * 3. `@Codec` 的成对关系是**单向**的：有写无读会崩，有读无写只是静默无效
 * 4. `@Codec` 在 final / `@Access(instanceAsValue=false)` 字段上整体失效
 */
class CodecAndConversionContractTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        for (decl in ANNOTATIONS) {
            myFixture.addClass(decl)
        }
    }

    private fun messages(source: String): List<String> {
        myFixture.enableInspections(AnnotationContractInspection())
        myFixture.configureByText("Holder.java", source.trimIndent())
        return myFixture.doHighlighting().mapNotNull { it.description }
    }

    private fun highlighting(source: String) = run {
        myFixture.enableInspections(AnnotationContractInspection())
        myFixture.configureByText("Holder.java", source.trimIndent())
        myFixture.doHighlighting()
    }

    // ===== 1. @Conversion.toField 可选引用 =====

    fun testMissingToFieldIsWarningNotError() {
        val highlights =
            highlighting(
                """
                import java.util.function.Function;

                class Holder {
                    private static final Function<Integer, Integer> FWD = i -> i;

                    @com.gto.datasynclib.annotations.Conversion(toManaged = "FWD", toField = "MISSING_REVERSE")
                    private int value;
                }
                """,
            )
        val hit = highlights.firstOrNull { it.description?.contains("MISSING_REVERSE") == true }
        assertNotNull("应提示 toField 指向的字段不存在，实际=${highlights.map { it.description }}", hit)
        assertEquals(
            "运行期对 toField 是静默跳过（catch NoSuchFieldException ignored），应为 WARNING 而非 ERROR",
            HighlightSeverity.WARNING,
            hit!!.severity,
        )
    }

    // ===== 2. readFromData 返回类型必须是 T =====

    fun testReadFromDataWithWrongReturnTypeRejected() {
        myFixture.addClass("package com.gto.datasynclib.datastream.data; public class Data {}")
        val msgs =
            messages(
                """
                import com.gto.datasynclib.datastream.data.Data;

                class Holder {
                    @com.gto.datasynclib.annotations.Codec(readFromData = "read")
                    private int value;

                    private String read(Data data, int version) { return null; }
                }
                """,
            )
        assertTrue(
            "readFromData 返回 String 而字段是 int，运行期会把返回值当 int 用，应报签名不匹配，实际=$msgs",
            msgs.any { it.contains("signature") },
        )
    }

    fun testReadFromDataWithCorrectSignatureAccepted() {
        myFixture.addClass("package com.gto.datasynclib.datastream.data; public class Data {}")
        val msgs =
            messages(
                """
                import com.gto.datasynclib.datastream.data.Data;

                class Holder {
                    @com.gto.datasynclib.annotations.Codec(readFromData = "read")
                    private int value;

                    private int read(Data data, int version) { return 0; }
                }
                """,
            )
        assertEmpty(
            "返回类型与参数都正确时不应报签名问题，实际=$msgs",
            msgs.filter { it.contains("signature") },
        )
    }

    // ===== 3. @Codec 成对关系是单向的 =====

    fun testLoneWriteToDataIsError() {
        val highlights =
            highlighting(
                """
                class Holder {
                    @com.gto.datasynclib.annotations.Codec(writeToData = "write")
                    private int value;

                    private Object write(int v) { return null; }
                }
                """,
            )
        val hit = highlights.firstOrNull { it.description?.contains("readFromData") == true }
        assertNotNull("有写无读应报错，实际=${highlights.map { it.description }}", hit)
        assertEquals(
            "有写无读运行期会崩，应为 ERROR",
            HighlightSeverity.ERROR,
            hit!!.severity,
        )
    }

    fun testLoneReadFromDataIsWarningOnly() {
        val highlights =
            highlighting(
                """
                class Holder {
                    @com.gto.datasynclib.annotations.Codec(readFromData = "read")
                    private int value;

                    private int read(Object data, int version) { return 0; }
                }
                """,
            )
        val hit = highlights.firstOrNull { it.description?.contains("writeToData") == true }
        assertNotNull("有读无写应提示无效，实际=${highlights.map { it.description }}", hit)
        assertEquals(
            "有读无写只是静默无效（运行期不解析），应为 WARNING",
            HighlightSeverity.WARNING,
            hit!!.severity,
        )
    }

    // ===== 4. @Codec 在 final / @Access(instanceAsValue=false) 上失效 =====

    fun testCodecOnFinalFieldWarns() {
        val msgs =
            messages(
                """
                class Holder {
                    @com.gto.datasynclib.annotations.Codec(writeToData = "write")
                    private final int value = 0;

                    private Object write(int v) { return null; }
                }
                """,
            )
        assertTrue(
            "@Codec 在 final 字段上整体失效，应提示，实际=$msgs",
            msgs.any { it.contains("final field") },
        )
    }

    fun testCodecWithAccessInstanceAsValueFalseWarns() {
        val msgs =
            messages(
                """
                class Holder {
                    @com.gto.datasynclib.annotations.Codec(writeToData = "write")
                    @com.gto.datasynclib.annotations.Access(instanceAsValue = false)
                    private int value;

                    private Object write(int v) { return null; }
                }
                """,
            )
        assertTrue(
            "@Access(instanceAsValue=false) 下 @Codec 失效，应提示，实际=$msgs",
            msgs.any { it.contains("instanceAsValue") },
        )
    }

    companion object {
        private val ANNOTATIONS =
            listOf(
                """
                package com.gto.datasynclib.annotations;
                public @interface Conversion { String toManaged() default ""; String toField() default ""; }
                """.trimIndent(),
                """
                package com.gto.datasynclib.annotations;
                public @interface Codec {
                    String saveCodec() default "";
                    String syncCodec() default "";
                    String writeToData() default "";
                    String readFromData() default "";
                    String writeToBuffer() default "";
                    String readFromBuffer() default "";
                }
                """.trimIndent(),
                """
                package com.gto.datasynclib.annotations;
                public @interface Access { boolean instanceAsValue() default false; }
                """.trimIndent(),
            )
    }
}
