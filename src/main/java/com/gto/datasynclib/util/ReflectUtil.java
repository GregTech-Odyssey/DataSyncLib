package com.gto.datasynclib.util;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.*;

/**
 * Reflection utility methods for field access and {@link MethodHandle} / {@link VarHandle} creation.
 *
 * <h3>Core responsibilities:</h3>
 * <ul>
 *   <li><strong>VarHandle creation</strong> — {@link #createVarHandle(MethodHandles.Lookup, Field)}
 *       wraps {@link MethodHandles.Lookup#unreflectVarHandle(Field)} with error context</li>
 *   <li><strong>MethodHandle adaptation</strong> — {@link #createAdaptedMethodHandle(MethodHandles.Lookup, Method)}
 *       and its overload bridge between reflective {@link Method} objects and the MethodHandle API,
 *       adapting parameter types from specific types to {@code Object} for use with
 *       {@link MethodHandle#invokeExact}</li>
 *   <li><strong>Generic type extraction</strong> — {@link #getFieldGenericTypeClasses(Type)} and
 *       {@link #getFieldGenericType(Type)} resolve type arguments from parameterized fields,
 *       used by the annotation processor to determine codec types and by
 *       {@link com.gto.datasynclib.annotations.Conversion @Conversion} resolution</li>
 *   <li><strong>Value parsing</strong> — {@link #parse(Class, String)} converts annotation
 *       string values to their typed equivalents (primitives, strings, enums)</li>
 *   <li><strong>Method resolution</strong> — {@link #getAccessibleMethod(Class, String, Class...)}
 *       finds methods declared on a class or inherited, setting them accessible</li>
 * </ul>
 *
 * @see com.gto.datasynclib.FieldDefinitionStorage
 * @see com.gto.datasynclib.DataFieldDefinition
 */
@UtilityClass
public final class ReflectUtil {

    /**
     * Creates an {@link IllegalArgumentException} with a standardized message format
     * for field-not-found errors.
     *
     * @param fieldName the name of the field that was not found
     * @return an exception ready to throw
     */
    public IllegalArgumentException createFieldNotFoundException(String fieldName) {
        return new IllegalArgumentException("Field[" + fieldName + "] not found");
    }

    /**
     * Extracts the raw {@link Class} objects for each type argument of a parameterized type.
     *
     * <p>For example, given {@code Map<String, Integer>}, returns {@code [String.class, Integer.class]}.
     * Throws if the type is not parameterized.</p>
     *
     * @param genericType the generic type to extract from (must be a {@link ParameterizedType})
     * @return an array of raw classes, one per type argument
     * @throws IllegalArgumentException if the type is not parameterized
     */
    public Class<?>[] getFieldGenericTypeClasses(Type genericType) {
        if (!(genericType instanceof ParameterizedType pType))
            throw new IllegalArgumentException("Field is not a parameterized type");
        Type[] actualTypeArguments = pType.getActualTypeArguments();
        Class<?>[] result = new Class<?>[actualTypeArguments.length];
        for (int i = 0; i < actualTypeArguments.length; i++) {
            result[i] = getRawType(actualTypeArguments[i]);
        }
        return result;
    }

    /**
     * Extracts the raw {@link Type} objects for each type argument of a parameterized type.
     *
     * <p>Unlike {@link #getFieldGenericTypeClasses}, this preserves full type information
     * (including nested parameterized types and wildcards). Used when resolving generic
     * type information for codec and factory lookup.</p>
     *
     * @param genericType the generic type to extract from (must be a {@link ParameterizedType})
     * @return an array of types, one per type argument
     * @throws IllegalArgumentException if the type is not parameterized
     */
    public Type[] getFieldGenericType(Type genericType) {
        if (!(genericType instanceof ParameterizedType pType))
            throw new IllegalArgumentException("Field is not a parameterized type");
        return pType.getActualTypeArguments();
    }

    /**
     * Resolves a {@link Type} to its raw {@link Class}, handling parameterized types,
     * generic arrays, and wildcards.
     *
     * <p>This is used as a helper within generic type extraction to strip away
     * type parameters and get the base class.</p>
     *
     * @param type the type to resolve (may be {@code Class}, {@link ParameterizedType},
     *             {@link GenericArrayType}, or {@code null})
     * @return the raw class, or {@code null} for unresolvable types
     */
    public Class<?> getRawType(Type type) {
        return switch (type) {
            case Class<?> aClass -> aClass;
            case GenericArrayType genericArrayType -> getRawType(genericArrayType.getGenericComponentType());
            case ParameterizedType parameterizedType -> getRawType(parameterizedType.getRawType());
            case null, default -> null;
        };
    }

    /**
     * Builds a fully-qualified field name for error messages: {@code "pkg.ClassName.fieldName"}.
     *
     * @param field the field to describe
     * @return a human-readable string identifying the field's location
     */
    public String getFieldDetailedName(Field field) {
        return field.getDeclaringClass().getName() + "." + field.getName();
    }

    /**
     * Parses a string value from an annotation into the appropriate Java type.
     *
     * <p>Supports: {@code String}, enums (via {@link EnumUtil#getEnum}), all primitives
     * and their boxed equivalents ({@code int}, {@code long}, {@code boolean}, {@code double},
     * {@code float}, {@code byte}, {@code short}, {@code char}).</p>
     *
     * @param type  the target type to parse into
     * @param value the string value from the annotation attribute
     * @return the parsed value, or {@code null} for empty strings on non-String types
     * @throws IllegalArgumentException if the type is not supported
     */
    public Object parse(Class<?> type, String value) {
        if (type == String.class) return value;
        if (value.isEmpty()) return null;
        if (type.isEnum()) return EnumUtil.getEnum((Class) type, value);
        if (type == int.class || type == Integer.class) {
            return Integer.parseInt(value);
        } else if (type == long.class || type == Long.class) {
            return Long.parseLong(value);
        } else if (type == boolean.class || type == Boolean.class) {
            return Boolean.parseBoolean(value);
        } else if (type == double.class || type == Double.class) {
            return Double.parseDouble(value);
        } else if (type == float.class || type == Float.class) {
            return Float.parseFloat(value);
        } else if (type == byte.class || type == Byte.class) {
            return Byte.parseByte(value);
        } else if (type == short.class || type == Short.class) {
            return Short.parseShort(value);
        } else if (type == char.class || type == Character.class) {
            return value.charAt(0);
        } else {
            throw new IllegalArgumentException("Unsupported type: " + type.getName());
        }
    }

    /**
     * Finds a method by name and parameter types, searching the declaring class first
     * (including private methods) then inherited public methods.
     *
     * <p>The returned method has {@code setAccessible(true)} already called.</p>
     *
     * @param clazz          the class to search
     * @param name           the method name
     * @param parameterTypes the parameter types for overload resolution
     * @return the accessible {@link Method}, never null
     * @throws RuntimeException wrapping {@link NoSuchMethodException} if not found
     */
    public Method getAccessibleMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        try {
            return clazz.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            try {
                return clazz.getMethod(name, parameterTypes);
            } catch (NoSuchMethodException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    /**
     * Creates a {@link VarHandle} for the given field using the provided lookup.
     *
     * <p>VarHandles provide the fastest possible field access in Java, avoiding reflection
     * overhead. They are used by {@link com.gto.datasynclib.DataFieldDefinition} for all
     * getter/setter operations on managed fields.</p>
     *
     * @param lookup the lookup with access to the field's declaring class
     * @param field  the field to create a VarHandle for
     * @return a VarHandle for reading (and, if non-final, writing) the field
     * @throws RuntimeException if the lookup doesn't have access to the field
     */
    public VarHandle createVarHandle(MethodHandles.Lookup lookup, Field field) {
        try {
            return lookup.unreflectVarHandle(field);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to acquire variable handle for field " + getFieldDetailedName(field), e);
        }
    }

    /**
     * Creates an adapted {@link MethodHandle} from a {@link Method}, converting all
     * non-primitive parameter types to {@code Object} for uniform invocation.
     *
     * <p>The resulting handle has the signature {@code (Object, Object...) -> returnType}
     * (or {@code (Object, Object...) -> Object} if the return type is a reference type),
     * allowing it to be called via {@link MethodHandle#invokeExact} with uniform
     * {@code Object} arguments regardless of the method's declared parameter types.</p>
     *
     * <p>Primitive parameters and return types are preserved as-is for performance.</p>
     *
     * @param lookup the lookup with access to the method's declaring class
     * @param method the method to create a handle for (may be null)
     * @return the adapted MethodHandle, or {@code null} if {@code method} is null
     */
    public MethodHandle createAdaptedMethodHandle(MethodHandles.Lookup lookup, @Nullable Method method) {
        return createAdaptedMethodHandle(lookup, method, null);
    }

    /**
     * Creates an adapted {@link MethodHandle} with a specified return type.
     *
     * <p>Like {@link #createAdaptedMethodHandle(MethodHandles.Lookup, Method)}, but allows
     * overriding the return type. When {@code returnType} is non-null, the handle's return
     * type is set to that class instead of the method's declared return type (for reference
     * returns) or {@code Object.class} (when null).</p>
     *
     * <p>This is used by {@link com.gto.datasynclib.FieldAnnotationMetadata} when creating
     * handles for conditions ({@code -> boolean}) and listeners ({@code -> void}).</p>
     *
     * @param lookup     the lookup with access to the method's declaring class
     * @param method     the method to create a handle for (may be null)
     * @param returnType the desired return type, or {@code null} to use method's own type
     * @return the adapted MethodHandle, or {@code null} if {@code method} is null
     */
    public MethodHandle createAdaptedMethodHandle(MethodHandles.Lookup lookup, @Nullable Method method, @Nullable Class<?> returnType) {
        if (method == null) return null;
        var handle = createDirectMethodHandle(lookup, method);
        var originalType = handle.type();
        var rt = method.getReturnType();
        var newParamTypes = originalType.parameterArray();
        for (var i = 0; i < newParamTypes.length; i++) {
            var type = newParamTypes[i];
            if (type.isPrimitive()) continue;
            newParamTypes[i] = Object.class;
        }
        MethodHandle adaptedHandle;
        if (rt.isPrimitive() || rt == void.class) {
            adaptedHandle = handle.asType(MethodType.methodType(rt, newParamTypes));
        } else {
            adaptedHandle = handle.asType(MethodType.methodType(returnType == null ? Object.class : returnType, newParamTypes));
        }
        return adaptedHandle;
    }

    /**
     * Creates a direct (unadapted) {@link MethodHandle} from a reflective {@link Method}.
     *
     * <p>This preserves the method's exact signature. The resulting handle is typically
     * further adapted by {@link #createAdaptedMethodHandle} to widen parameter types to
     * {@code Object} for uniform invocation.</p>
     *
     * @param lookup the lookup with access to the method's declaring class
     * @param method the method to unreflect (may be null)
     * @return the direct MethodHandle, or {@code null} if {@code method} is null
     */
    public MethodHandle createDirectMethodHandle(MethodHandles.Lookup lookup, Method method) {
        if (method == null) return null;
        try {
            method.setAccessible(true);
            return lookup.unreflect(method);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to create MethodHandle for method: " + method.getName(), e);
        }
    }

}
