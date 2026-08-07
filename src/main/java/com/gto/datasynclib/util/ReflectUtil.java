package com.gto.datasynclib.util;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
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
     * Resolves a {@code Type}, substituting type variables from the given binding map, while
     * preserving full type information (parameterized types, arrays, wildcards). Unbound type
     * variables are returned as-is.
     *
     * @param type     the type to resolve
     * @param bindings a map of type variable → concrete type (may be {@code null}/empty)
     * @return the resolved type, or the original if nothing needed substituting
     */
    public Type resolveType(@Nullable Type type, @Nullable java.util.Map<TypeVariable<?>, Type> bindings) {
        if (type == null || bindings == null || bindings.isEmpty()) return type;
        if (type instanceof TypeVariable<?> variable) {
            var bound = bindings.get(variable);
            return bound == null ? type : resolveType(bound, bindings);
        }
        if (type instanceof GenericArrayType array) {
            // Preserve array shape; the component is resolved best-effort to a raw class.
            return resolveType(array.getGenericComponentType(), bindings) instanceof Class<?> component
                    ? java.lang.reflect.Array.newInstance(component, 0).getClass()
                    : type;
        }
        if (type instanceof ParameterizedType parameterized) {
            Type[] args = parameterized.getActualTypeArguments();
            boolean changed = false;
            Type[] resolved = new Type[args.length];
            for (int i = 0; i < args.length; i++) {
                resolved[i] = resolveType(args[i], bindings);
                changed |= resolved[i] != args[i];
            }
            if (!changed) return type;
            return ParameterizedType.make(parameterized.getRawType(), resolved, parameterized.getOwnerType());
        }
        return type;
    }

    /**
     * Recursively collects the concrete type-variable bindings along a class's generic
     * superclass/interfaces chain, from the given immediate {@code slot} upward toward
     * {@code Object}. The {@code out} map accumulates type variable → resolved type.
     *
     * <p>Example: for {@code class A<T> extends HashMap<String, T>}, given the field type
     * {@code A<Integer>}, this records {@code T → Integer}, then walks {@code A}'s superclass
     * {@code HashMap<String, T>} (which fixes {@code K=String}, {@code V=T}).</p>
     *
     * @param slot     the parameterized type whose variables are currently bound
     * @param raw      the raw class corresponding to {@code slot}
     * @param out      accumulating map of type variable → concrete type
     * @return whether a change was made to {@code out}; used to detect already-registered entries
     */
    public boolean resolveSuperTypeBindings(@Nullable Type slot, @Nullable Class<?> raw, @NotNull java.util.Map<TypeVariable<?>, Type> out) {
        if (raw == null || raw == Object.class) return false;
        boolean changed = false;
        Type[] actual = null;
        if (slot instanceof ParameterizedType parameterized) {
            actual = parameterized.getActualTypeArguments();
        }
        TypeVariable<?>[] variables = raw.getTypeParameters();
        for (int i = 0; i < variables.length; i++) {
            Type value = actual != null && i < actual.length ? actual[i] : variables[i];
            value = resolveType(value, out);
            Type previous = out.put(variables[i], value);
            changed |= previous != value;
        }
        // Recurse into superclass (and interfaces for completeness).
        Type genericSuperclass = raw.getGenericSuperclass();
        changed |= resolveSuperTypeBindings(genericSuperclass, raw.getSuperclass(), out);
        for (Type genericInterface : raw.getGenericInterfaces()) {
            Class<?> iface = getRawType(genericInterface);
            if (iface != null) changed |= resolveSuperTypeBindings(genericInterface, iface, out);
        }
        return changed;
    }

    /**
     * Resolves {@code fieldType} to the {@link ParameterizedType} that {@code targetRaw}
     * occupies in its generic hierarchy, with all type variables substituted by their concrete
     * bindings drawn from {@code fieldType}. Returns the resolved parameterized supertype, or
     * {@code targetRaw} as a plain class if {@code targetRaw} is not generic / not found.
     *
     * <h3>Example</h3>
     * <pre>{@code class A<T> extends HashMap<String, T> { }}</pre>
     * Given a field declared as {@code A<Integer>}:
     * <pre>{@code getResolvedSuperType(field.getGenericType(), HashMap.class) }
     * // → ParameterizedType HashMap<String, Integer></pre>
     *
     * @param fieldType the declared generic type of a field (may be a {@code Class} or {@code ParameterizedType})
     * @param targetRaw the raw class of the ancestor to resolve to (may be {@code null}
     *                  to resolve the field type's own generic arguments)
     * @return the resolved parameterized type, or {@code null} if it cannot be resolved
     */
    @Nullable
    public ParameterizedType getResolvedSuperType(@Nullable Type fieldType, @Nullable Class<?> targetRaw) {
        if (fieldType == null) return null;
        // Build the full binding map from the field's own concrete arguments through the hierarchy
        // (classes, interfaces, arbitrary depth).
        var bindings = new java.util.LinkedHashMap<TypeVariable<?>, Type>();
        if (fieldType instanceof ParameterizedType parameterized) {
            resolveSuperTypeBindings(parameterized, getRawType(parameterized.getRawType()), bindings);
        } else if (fieldType instanceof Class<?> clazz) {
            resolveSuperTypeBindings(null, clazz, bindings);
        }
        Class<?> startRaw = getRawType(fieldType);
        if (targetRaw == null || targetRaw == startRaw) {
            // No ancestor requested: resolve the field type's own generic arguments.
            if (fieldType instanceof ParameterizedType parameterized) {
                return (ParameterizedType) resolveType(parameterized, bindings);
            }
            return null;
        }
        // Find the (possibly deep) parameterized slot for targetRaw along the whole
        // class/interface hierarchy, then substitute its type variables.
        ParameterizedType ancestor = findAncestorParameterized(fieldType, startRaw, targetRaw, new java.util.HashSet<>());
        if (ancestor == null) return null;
        return (ParameterizedType) resolveType(ancestor, bindings);
    }

    /**
     * Convenience: returns the resolved {@link Class} type arguments of {@code targetRaw} within
     * {@code fieldType}'s generic hierarchy. For {@code A<T> extends HashMap<String, T>} with a
     * field {@code A<Integer>}, this returns {@code [String.class, Integer.class]}.
     *
     * @param fieldType the declared generic type of the field
     * @param targetRaw the raw ancestor class whose arguments to produce
     * @return resolved concrete argument classes (may contain {@code null} for unresolvable entries)
     */
    public Class<?>[] getResolvedGenericArguments(@Nullable Type fieldType, @Nullable Class<?> targetRaw) {
        var resolved = getResolvedSuperType(fieldType, targetRaw);
        if (resolved == null) return new Class<?>[0];
        Type[] args = resolved.getActualTypeArguments();
        Class<?>[] result = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            result[i] = getRawType(args[i]);
        }
        return result;
    }

    /**
     * Finds the {@link ParameterizedType} for {@code targetRaw} along {@code type}'s generic
     * hierarchy — both superclass <em>and</em> implemented interfaces, at arbitrary depth.
     * Returns {@code null} if {@code targetRaw} is not a generic ancestor of {@code startRaw}.
     *
     * <p>For example {@code class A<T> implements List<T>} resolves {@code List<T>} given
     * {@code targetRaw = List.class}.</p>
     *
     * @param type       the type whose generic hierarchy to search (a {@code Class} or {@code ParameterizedType})
     * @param startRaw   the raw class of {@code type}
     * @param targetRaw  the raw ancestor (class or interface) to locate
     * @param seen       set of already-visited raw classes to guard against cycles
     * @return the parameterized slot for {@code targetRaw}, or {@code null}
     */
    @Nullable
    private ParameterizedType findAncestorParameterized(@Nullable Type type, @Nullable Class<?> startRaw, Class<?> targetRaw, java.util.Set<Class<?>> seen) {
        if (startRaw == null || startRaw == Object.class || !seen.add(startRaw)) return null;
        // Direct match on this node.
        if (startRaw == targetRaw) {
            return type instanceof ParameterizedType parameterized ? parameterized : null;
        }
        // Search superclass.
        Type genericSuperclass = startRaw.getGenericSuperclass();
        if (genericSuperclass != null) {
            Class<?> supRaw = getRawType(genericSuperclass);
            var res = findAncestorParameterized(genericSuperclass, supRaw, targetRaw, seen);
            if (res != null) return res;
        }
        // Search implemented interfaces (generic or not).
        for (Type genericInterface : startRaw.getGenericInterfaces()) {
            Class<?> ifaceRaw = getRawType(genericInterface);
            var res = findAncestorParameterized(genericInterface, ifaceRaw, targetRaw, seen);
            if (res != null) return res;
        }
        return null;
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
