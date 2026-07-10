package com.gto.datasynclib.util;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.*;

@UtilityClass
public final class ReflectUtil {

    public IllegalArgumentException fieldNotFoundException(String fieldName) {
        return new IllegalArgumentException("Field[" + fieldName + "] not found");
    }

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

    public Class<?> getRawType(Type type) {
        return switch (type) {
            case Class<?> aClass -> aClass;
            case GenericArrayType genericArrayType -> getRawType(genericArrayType.getGenericComponentType());
            case ParameterizedType parameterizedType -> getRawType(parameterizedType.getRawType());
            case null, default -> null;
        };
    }

    public String getFieldDetailedName(Field field) {
        return field.getDeclaringClass().getName() + "." + field.getName();
    }

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
     * (Object) -> fieldType
     */
    public MethodHandle createAdaptedGetter(Field field) {
        var rawGetter = createDirectGetter(field, getLookup(field));
        var originalType = rawGetter.type();
        var returnType = field.getType();
        if (originalType.parameterCount() == 1) {
            MethodHandle adaptedGetter;
            if (returnType.isPrimitive()) {
                adaptedGetter = rawGetter.asType(MethodType.methodType(returnType, Object.class));
            } else {
                adaptedGetter = rawGetter.asType(MethodType.methodType(Object.class, Object.class));
            }
            return adaptedGetter;
        }
        throw new IllegalArgumentException("Unexpected getter handle type: " + originalType);
    }

    /**
     * (Object, Primitive or Object) -> void
     */
    public MethodHandle createAdaptedSetter(Field field) {
        var rawSetter = createDirectSetter(field, getLookup(field));
        var originalType = rawSetter.type();
        var valueType = field.getType();
        var isPrimitive = valueType.isPrimitive();
        MethodHandle adaptedSetter;
        if (originalType.parameterCount() == 2) {
            if (isPrimitive) {
                adaptedSetter = rawSetter.asType(MethodType.methodType(void.class, Object.class, valueType));
            } else {
                adaptedSetter = rawSetter.asType(MethodType.methodType(void.class, Object.class, Object.class));
            }
            return adaptedSetter;
        }
        throw new IllegalArgumentException("Unexpected setter handle type: " + originalType);
    }

    /**
     * (Object, Primitive or Object...) -> Primitive or Object
     */
    public MethodHandle createAdaptedMethodHandle(@Nullable Method method) {
        return createAdaptedMethodHandle(method, null);
    }

    /**
     * (Object, Primitive or Object...) -> returnType
     */
    public MethodHandle createAdaptedMethodHandle(@Nullable Method method, @Nullable Class<?> returnType) {
        if (method == null) return null;
        var handle = createDirectMethodHandle(method);
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

    public MethodHandle createDirectGetter(Field field, MethodHandles.Lookup lookup) {
        try {
            try {
                return lookup.unreflectGetter(field);
            } catch (IllegalAccessException e) {
                try {
                    return lookup.findGetter(field.getDeclaringClass(), field.getName(), field.getType());
                } catch (NoSuchFieldException | IllegalAccessException ex) {
                    return lookup.findVarHandle(field.getDeclaringClass(), field.getName(), field.getType()).toMethodHandle(VarHandle.AccessMode.GET);
                }
            }
        } catch (Throwable e) {
            throw new RuntimeException("Failed to create getter for field: " + getFieldDetailedName(field), e);
        }
    }

    public MethodHandle createDirectSetter(Field field, MethodHandles.Lookup lookup) {
        try {
            try {
                return lookup.unreflectSetter(field);
            } catch (IllegalAccessException e) {
                try {
                    return lookup.findSetter(field.getDeclaringClass(), field.getName(), field.getType());
                } catch (NoSuchFieldException | IllegalAccessException ex) {
                    return lookup.findVarHandle(field.getDeclaringClass(), field.getName(), field.getType()).toMethodHandle(VarHandle.AccessMode.SET);
                }
            }
        } catch (Throwable e) {
            throw new RuntimeException("Failed to create setter for field: " + getFieldDetailedName(field), e);
        }
    }

    public MethodHandle createDirectMethodHandle(Method method) {
        if (method == null) return null;
        try {
            method.setAccessible(true);
            MethodHandles.Lookup lookup = getLookup(method);
            return lookup.unreflect(method);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to create MethodHandle for method: " + method.getName(), e);
        }
    }

    public MethodHandles.Lookup getLookup(Field field) {
        try {
            return MethodHandles.privateLookupIn(field.getDeclaringClass(), MethodHandles.lookup());
        } catch (IllegalAccessException e) {
            return MethodHandles.lookup();
        }
    }

    public MethodHandles.Lookup getLookup(Method method) {
        try {
            return MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup());
        } catch (IllegalAccessException e) {
            return MethodHandles.lookup();
        }
    }
}
