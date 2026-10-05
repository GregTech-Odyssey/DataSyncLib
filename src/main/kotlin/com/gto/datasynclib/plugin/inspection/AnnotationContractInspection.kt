package com.gto.datasynclib.plugin.inspection

import com.gto.datasynclib.plugin.registry.AnnotationContract
import com.gto.datasynclib.plugin.registry.AnnotationContractRegistry
import com.gto.datasynclib.plugin.registry.FieldContextResolver
import com.gto.datasynclib.plugin.registry.RefKind

import com.intellij.codeInspection.*
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

/**
 * DataSyncLib 注解契约校验 + 语义检查。
 *
 * 校验项（均有源码依据，镜像 FieldDefinitionStorage / FieldAnnotationMetadata / ReflectUtil）：
 *  - 字符串引用成员不存在 / 签名不匹配 / 静态性不匹配 / required 缺失
 *  - @SaveToDisk.key 重复（含继承链 + 嵌套展平）
 *  - @SaveToDisk.defaultValue 字面量格式
 *  - @Generic 无泛型实参
 *  - @Codec.saveCodec 存在时 writeToData/readFromData/writeToBuffer/readFromBuffer 无效
 */
class AnnotationContractInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun getDisplayName(): String = "DataSyncLib annotation checks"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : JavaElementVisitor() {

        override fun visitAnnotation(annotation: PsiAnnotation) {
            val qName = annotation.qualifiedName ?: return
            // 1) required 属性缺失
            AnnotationContractRegistry.findByAnnotation(qName)
                .filter { it.required }
                .forEach { contract ->
                    if (annotation.findAttributeValue(contract.attributeName) == null) {
                        holder.registerProblem(
                            annotation,
                            "Missing required attribute '${contract.attributeName}'",
                            ProblemHighlightType.ERROR,
                        )
                    }
                }
            // 2) defaultValue 字面量格式
            if (qName == AnnotationContract.SAVE_TO_DISK) {
                checkDefaultValue(holder, annotation)
            }
            // 3) @Codec 无效果警告
            if (qName == AnnotationContract.CODEC) {
                checkCodecRedundancy(holder, annotation)
            }
            // 4) @Codec 成对解析（writeToData 需配 readFromData，writeToBuffer 需配 readFromBuffer）
            if (qName == AnnotationContract.CODEC) {
                checkCodecPairs(holder, annotation)
            }
        }

        override fun visitNameValuePair(pair: PsiNameValuePair) {
            val value = pair.value as? PsiLiteralExpression ?: return
            if (value.value !is String) return
            val annotation = PsiTreeUtil.getParentOfType(pair, PsiAnnotation::class.java, false) ?: return
            val contract = AnnotationContractRegistry.find(
                annotation.qualifiedName ?: return,
                pair.name ?: "value",
            ) ?: return
            val field = PsiTreeUtil.getParentOfType(annotation, PsiField::class.java, false) ?: return
            checkContract(holder, value, field, contract)
        }

        override fun visitField(field: PsiField) {
            // @Generic 无泛型检查
            checkGeneric(holder, field)
            // @Conversion 泛型方向检查（Function<A,B> 的 A 必须匹配字段类型）
            checkConversionDirection(holder, field)
            // @Strategy 泛型类型匹配检查（Hash.Strategy<T> 的 T 必须匹配字段类型）
            checkStrategyGeneric(holder, field)
        }

        override fun visitClass(clazz: PsiClass) {
            // @SaveToDisk.key 重复检测（含嵌套 @AdditionalHolder 展平字段）
            checkDuplicateKeys(holder, clazz)
        }
    }

    // ===== defaultValue 格式 =====

    private fun checkDefaultValue(holder: ProblemsHolder, annotation: PsiAnnotation) {
        val pair = annotation.findAttributeValue("defaultValue") ?: return
        val literal = pair as? PsiLiteralExpression ?: return
        val text = literal.value as? String ?: return
        if (text.isEmpty()) return
        val field = PsiTreeUtil.getParentOfType(annotation, PsiField::class.java, false) ?: return
        val type = FieldContextResolver.effectiveType(field)
        if (!isParsable(type, text)) {
            holder.registerProblem(
                literal,
                "Cannot parse '$text' as ${type.presentableText}",
                ProblemHighlightType.ERROR,
            )
        }
    }

    private fun isParsable(type: PsiType, text: String): Boolean {
        // 镜像 ReflectUtil.parse：支持 String/枚举/基本类型。枚举与 String 不校验格式。
        if (type is PsiPrimitiveType || isBoxedNumber(type) || type is PsiArrayType) {
            return tryParseNumber(type, text)
        }
        // String / 枚举 / 其它引用类型：不强制校验（parse 只对基本类型抛格式异常）
        return true
    }

    private fun isBoxedNumber(type: PsiType): Boolean {
        val name = type.canonicalText
        return name == "java.lang.Integer" || name == "java.lang.Long" ||
            name == "java.lang.Boolean" || name == "java.lang.Double" ||
            name == "java.lang.Float" || name == "java.lang.Byte" ||
            name == "java.lang.Short" || name == "java.lang.Character"
    }

    private fun tryParseNumber(type: PsiType, text: String): Boolean {
        val c = type.canonicalText
        return try {
            when (c) {
                "int", "java.lang.Integer" -> text.toInt()
                "long", "java.lang.Long" -> text.toLong()
                "boolean", "java.lang.Boolean" -> text.toBooleanStrictOrNull() != null || text == "true" || text == "false"
                "double", "java.lang.Double" -> text.toDouble()
                "float", "java.lang.Float" -> text.toFloat()
                "byte", "java.lang.Byte" -> text.toByte()
                "short", "java.lang.Short" -> text.toShort()
                "char", "java.lang.Character" -> text.length == 1
                else -> true
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ===== @Codec 无效果警告 =====

    private fun checkCodecRedundancy(holder: ProblemsHolder, annotation: PsiAnnotation) {
        val saveCodec = annotation.findAttributeValue("saveCodec")?.let {
            (it as? PsiLiteralExpression)?.value as? String
        } ?: return
        if (saveCodec.isEmpty()) return
        // saveCodec 非空 → 走静态 codec 分支，实例方法属性被忽略
        for (attr in listOf("writeToData", "readFromData", "writeToBuffer", "readFromBuffer")) {
            val pair = annotation.findAttributeValue(attr)?.let { it as? PsiLiteralExpression } ?: continue
            val v = pair.value as? String ?: continue
            if (v.isNotEmpty()) {
                holder.registerProblem(
                    pair,
                    "'$attr' has no effect when 'saveCodec' is set (static codec branch is used)",
                    ProblemHighlightType.WARNING,
                )
            }
        }
    }

    // ===== @Codec 成对解析 =====

    private fun checkCodecPairs(holder: ProblemsHolder, annotation: PsiAnnotation) {
        val saveCodec = annotation.findAttributeValue("saveCodec")?.let {
            (it as? PsiLiteralExpression)?.value as? String
        }
        // saveCodec 非空时走静态分支，实例方法属性被忽略，无需成对校验
        if (!saveCodec.isNullOrEmpty()) return

        checkPair(
            holder,
            annotation,
            "writeToData",
            "readFromData",
            "'writeToData' requires a matching 'readFromData' (runtime resolves readFromData unconditionally)",
        )
        checkPair(
            holder,
            annotation,
            "writeToBuffer",
            "readFromBuffer",
            "'writeToBuffer' requires a matching 'readFromBuffer' (runtime resolves readFromBuffer unconditionally)",
        )
    }

    private fun checkPair(holder: ProblemsHolder, annotation: PsiAnnotation, writeAttr: String, readAttr: String, message: String) {
        val write = attrString(annotation, writeAttr)
        val read = attrString(annotation, readAttr)
        if (write.isNotEmpty() && read.isEmpty()) {
            val pair = annotation.findAttributeValue(writeAttr) as? PsiLiteralExpression
            holder.registerProblem(
                pair ?: annotation,
                message,
                ProblemHighlightType.ERROR,
            )
        }
    }

    private fun attrString(annotation: PsiAnnotation, attr: String): String = annotation.findAttributeValue(attr)?.let { (it as? PsiLiteralExpression)?.value as? String } ?: ""

    // ===== @Generic 无泛型 =====

    private fun checkGeneric(holder: ProblemsHolder, field: PsiField) {
        val generic = field.getAnnotation("com.gto.datasynclib.annotations.Generic") ?: return
        val args = FieldContextResolver.genericArguments(field)
        if (args.isEmpty()) {
            holder.registerProblem(
                generic,
                "@Generic field must have resolvable generic type arguments",
                ProblemHighlightType.ERROR,
            )
        }
    }

    // ===== @Conversion 泛型方向 =====

    private fun checkConversionDirection(holder: ProblemsHolder, field: PsiField) {
        val conversion = field.getAnnotation(FieldContextResolver.CONVERSION_ANNOTATION) ?: return
        val types = FieldContextResolver.conversionFunctionTypes(field)
        if (types.size < 2) {
            // 无法解析 Function 泛型（可能在别的文件/未解析），跳过（不误报）
            return
        }
        // Function<A, B>：A（第一泛型）必须匹配字段声明类型
        val a = types[0]
        if (!typeCompatible(a, field.type)) {
            val pair = conversion.findAttributeValue("toManaged") as? PsiLiteralExpression
            holder.registerProblem(
                pair ?: conversion,
                "Conversion Function input type ${a.presentableText} does not match field type ${field.type.presentableText}",
                ProblemHighlightType.WARNING,
            )
        }
    }

    private fun typeCompatible(a: PsiType, b: PsiType): Boolean {
        if (a == b) return true
        // 装箱/拆箱等价
        val an = a.canonicalText
        val bn = b.canonicalText
        return box(an) == box(bn)
    }

    private fun box(t: String): String = when (t) {
        "int" -> "java.lang.Integer"
        "long" -> "java.lang.Long"
        "boolean" -> "java.lang.Boolean"
        "double" -> "java.lang.Double"
        "float" -> "java.lang.Float"
        "byte" -> "java.lang.Byte"
        "short" -> "java.lang.Short"
        "char" -> "java.lang.Character"
        else -> t
    }

    // ===== @Strategy 泛型类型匹配 =====

    private fun checkStrategyGeneric(holder: ProblemsHolder, field: PsiField) {
        val strategy = field.getAnnotation(FieldContextResolver.STRATEGY_ANNOTATION) ?: return
        val st = FieldContextResolver.strategyGenericType(field) ?: return
        // Hash.Strategy<T> 的 T 需匹配字段类型（数组字段 T = 数组类型本身）
        if (!typeCompatible(st, field.type)) {
            val pair = strategy.findAttributeValue("value") as? PsiLiteralExpression
            holder.registerProblem(
                pair ?: strategy,
                "Strategy generic type ${st.presentableText} does not match field type ${field.type.presentableText}",
                ProblemHighlightType.WARNING,
            )
        }
    }

    // ===== key 重复检测 =====

    private fun checkDuplicateKeys(holder: ProblemsHolder, clazz: PsiClass) {
        // 镜像 scanFields：遍历本类字段，对 @AdditionalHolder(flat) 字段递归展平其嵌套类型的字段，
        // 所有「托管字段」的 key 进入同一个 definitionMap，冲突即运行时抛异常。
        val seen = HashMap<String, PsiField>()
        collectKeys(clazz, seen, holder, 0)
    }

    private fun collectKeys(clazz: PsiClass, seen: MutableMap<String, PsiField>, holder: ProblemsHolder, depth: Int) {
        if (depth > 8) return // 防循环
        for (field in clazz.fields) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) continue
            // @AdditionalHolder（flat）字段：递归展平嵌套类型的字段（镜像 scanFields 递归）
            val additionalHolder = field.getAnnotation(FieldContextResolver.ADDITIONAL_HOLDER_ANNOTATION)
            if (additionalHolder != null) {
                val childManager = additionalHolder.findAttributeValue("childManager")?.let {
                    (it as? com.intellij.psi.PsiLiteralExpression)?.value as? Boolean
                } ?: false
                if (!childManager) {
                    // flat：递归展开嵌套类型
                    val nestedClass = (field.type as? PsiClassType)?.resolve()
                    if (nestedClass != null) {
                        collectKeys(nestedClass, seen, holder, depth + 1)
                    }
                    continue
                }
                // childManager=true：字段本身作为一个托管字段（若带 SaveToDisk/Sync 注解）
            }
            // 托管字段（进 definitionMap 的字段）
            if (!isManagedField(field)) continue
            val saveToDisk = field.getAnnotation("com.gto.datasynclib.annotations.SaveToDisk")
            val key = resolveKey(field, saveToDisk)
            val prev = seen[key]
            if (prev != null && prev !== field) {
                val target = (saveToDisk?.findAttributeValue("key") as? PsiLiteralExpression) ?: field.nameIdentifier
                holder.registerProblem(
                    target,
                    "Duplicate field key '$key' (already used by '${prev.name}') — runtime throws 'Duplicate sync field key'",
                    ProblemHighlightType.ERROR,
                )
            } else {
                seen[key] = field
            }
        }
    }

    private fun isManagedField(field: PsiField): Boolean = field.getAnnotation("com.gto.datasynclib.annotations.SaveToDisk") != null ||
        field.getAnnotation("com.gto.datasynclib.annotations.SyncToClient") != null ||
        field.getAnnotation("com.gto.datasynclib.annotations.SyncToServer") != null ||
        field.getAnnotation("com.gto.datasynclib.annotations.AddToManager") != null

    private fun resolveKey(field: PsiField, saveToDisk: PsiAnnotation?): String {
        val explicit = saveToDisk?.findAttributeValue("key")?.let {
            (it as? PsiLiteralExpression)?.value as? String
        }
        return explicit?.takeIf { it.isNotEmpty() } ?: field.name
    }

    // ===== 字符串引用契约校验 =====

    private fun checkContract(holder: ProblemsHolder, literal: PsiLiteralExpression, owner: PsiField, contract: AnnotationContract) {
        val name = literal.value as? String ?: return
        val clazz = owner.containingClass ?: return
        when (contract.kind) {
            RefKind.STATIC_FIELD -> checkField(holder, literal, clazz, name, contract)
            RefKind.INSTANCE_METHOD -> checkMethod(holder, literal, clazz, name, owner, contract)
        }
    }

    private fun checkField(holder: ProblemsHolder, literal: PsiLiteralExpression, clazz: PsiClass, name: String, contract: AnnotationContract) {
        val target = clazz.findFieldByName(name, false)
        if (target == null) {
            holder.registerProblem(literal, "Cannot resolve field '$name' in ${clazz.name}", ProblemHighlightType.ERROR)
            return
        }
        if (!target.hasModifierProperty(PsiModifier.STATIC)) {
            holder.registerProblem(literal, "Referenced field '$name' must be static", ProblemHighlightType.ERROR)
        }
        contract.staticFieldType?.let { expected ->
            if (!target.type.canonicalText.let { it == expected || it.substringAfterLast('.') == expected.substringAfterLast('.') }) {
                holder.registerProblem(literal, "Field '$name' should be of type $expected", ProblemHighlightType.WARNING)
            }
        }
    }

    private fun checkMethod(holder: ProblemsHolder, literal: PsiLiteralExpression, clazz: PsiClass, name: String, owner: PsiField, contract: AnnotationContract) {
        val methods = clazz.findMethodsByName(name, true)
        if (methods.isEmpty()) {
            holder.registerProblem(literal, "Cannot resolve method '$name'", ProblemHighlightType.ERROR)
            return
        }
        // 关键修复：用「有效类型」（含 @Conversion 托管类型）而非字段声明类型
        val effectiveType = FieldContextResolver.effectiveType(owner)
        val matcher = MethodSignatureMatcher(contract, effectiveType)
        val matched = methods.firstOrNull { matcher.matches(it) }
        if (matched == null) {
            val hint = matcher.describeExpectation()
            holder.registerProblem(
                literal,
                "No method '$name' matches expected signature $hint",
                ProblemHighlightType.WARNING,
            )
        }
    }
}

/**
 * 方法签名匹配器，与 AnnotationMemberReference.matchesContract 保持一致。
 */
class MethodSignatureMatcher(private val contract: AnnotationContract, private val fieldType: PsiType) {
    fun matches(method: PsiMethod): Boolean {
        val params = contract.expectedParamTypes ?: emptyList()
        val actual = method.parameterList.parameters.map { it.type }
        if (actual.size != params.size) return false
        params.forEachIndexed { i, expected ->
            if (expected == null) return@forEachIndexed
            if (expected == AnnotationContract.FIELD_TYPE) {
                if (!typesCompatible(actual[i], fieldType)) return false
            } else if (expected == AnnotationContractRegistry.BUFFER_FIELD_TYPE) {
                val t = actual[i].canonicalText
                if (t != "net.minecraft.network.FriendlyByteBuf" && t != "net.minecraft.network.RegistryFriendlyByteBuf") return false
            } else {
                val a = actual[i].canonicalText
                if (a != expected && a.substringAfterLast('.') != expected.substringAfterLast('.')) return false
            }
        }
        if (contract.returnIsBoolean) {
            val rt = method.returnType ?: return false
            if (rt != PsiTypes.booleanType() && rt.canonicalText != "boolean") return false
        }
        if (contract.returnIsVoid && method.returnType != PsiTypes.voidType()) return false
        return true
    }

    // 类型兼容：允许基本类型与装箱类型等价（与 MethodHandle 的 primitive 保留语义近似）
    private fun typesCompatible(actual: PsiType?, expected: PsiType): Boolean {
        if (actual == expected) return true
        if (actual is PsiPrimitiveType && expected is PsiPrimitiveType) return actual == expected
        // 装箱/拆箱等价
        val a = actual?.canonicalText
        val e = expected.canonicalText
        return primitiveBox(a) == primitiveBox(e)
    }

    private fun primitiveBox(t: String?): String? = when (t) {
        "int" -> "java.lang.Integer"
        "long" -> "java.lang.Long"
        "boolean" -> "java.lang.Boolean"
        "double" -> "java.lang.Double"
        "float" -> "java.lang.Float"
        "byte" -> "java.lang.Byte"
        "short" -> "java.lang.Short"
        "char" -> "java.lang.Character"
        else -> t
    }

    fun describeExpectation(): String {
        val params = contract.expectedParamTypes ?: emptyList()
        val p = params.joinToString(", ") { e ->
            when (e) {
                null -> "?"
                AnnotationContract.FIELD_TYPE -> fieldType.presentableText
                AnnotationContractRegistry.BUFFER_FIELD_TYPE -> "FriendlyByteBuf"
                else -> e.substringAfterLast('.')
            }
        }
        val r = when {
            contract.returnIsBoolean -> "boolean"
            contract.returnIsVoid -> "void"
            else -> "…"
        }
        return "($p) -> $r"
    }
}
