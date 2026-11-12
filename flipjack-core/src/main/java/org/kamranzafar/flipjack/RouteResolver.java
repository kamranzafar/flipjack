/*
 * Copyright 2026 Kamran Zafar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kamranzafar.flipjack;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Turns builder settings plus {@link Toggleable} annotations into a route per interface
 * method: an ordered list of {@link FeatureCheck}s (class check first, then method check).
 * Everything is resolved and validated once, at build time.
 */
final class RouteResolver {

    private final ToggledFeature.Builder<?> spec;
    private final Class<?> annotatedType;
    private final Object fallbackMethodTarget;
    private final Set<Class<?>> fallbackTypes = new LinkedHashSet<>();

    RouteResolver(ToggledFeature.Builder<?> spec) {
        this.spec = spec;
        this.annotatedType = spec.annotatedType != null ? spec.annotatedType : spec.enabled.getClass();
        this.fallbackMethodTarget = spec.fallbackMethodTarget != null ? spec.fallbackMethodTarget : spec.enabled;
    }

    Map<Method, List<FeatureCheck>> resolve() {
        rejectAnnotatedMethodsOutsideInterfaces();
        FeatureCheck classCheck = classCheck();

        Map<Method, List<FeatureCheck>> routes = new HashMap<>();
        for (Class<?> iface : spec.interfaces) {
            for (Method method : iface.getMethods()) {
                if (Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                List<FeatureCheck> checks = new ArrayList<>(2);
                if (classCheck != null) {
                    checks.add(classCheck);
                }
                Toggleable annotation = ToggleAnnotations.onMethod(annotatedType, method);
                if (annotation != null) {
                    checks.add(methodCheck(method, annotation));
                }
                if (!checks.isEmpty()) {
                    routes.put(method, List.copyOf(checks));
                }
            }
        }
        if (routes.isEmpty()) {
            throw new IllegalStateException("Nothing to toggle on " + annotatedType.getName()
                    + ": set feature(...) or annotate the class or its methods with @Toggleable");
        }
        return Map.copyOf(routes);
    }

    private FeatureCheck classCheck() {
        Toggleable annotation = ToggleAnnotations.onType(annotatedType);
        String feature = spec.feature != null ? spec.feature : annotation != null ? annotation.value() : null;

        if (feature == null) {
            if (spec.fallbackInstance != null || spec.whenDisabled != null) {
                throw new IllegalStateException(
                        "fallback(...) and whenDisabled(...) apply to a class-level feature, but none is set");
            }
            return null;
        }
        requireName(feature, annotatedType.getName());
        if (annotation != null && !annotation.fallbackMethod().isEmpty()) {
            throw new IllegalArgumentException("fallbackMethod is only supported on methods, not on class "
                    + annotatedType.getName());
        }

        boolean annotationFallback = annotation != null && annotation.fallback() != Void.class;
        if (annotationFallback && annotation.whenDisabled() == WhenDisabled.NO_OP) {
            throw new IllegalArgumentException("@Toggleable on " + annotatedType.getName()
                    + " sets both fallback and whenDisabled = NO_OP; the fallback would always win, so choose one");
        }
        if (spec.whenDisabled != null && (spec.fallbackInstance != null || annotationFallback)) {
            throw new IllegalStateException("whenDisabled(" + spec.whenDisabled + ") never applies to "
                    + annotatedType.getName() + ": a class-level fallback is set, and it handles every disabled call");
        }

        DisabledAction action;
        if (spec.fallbackInstance != null) {
            requireImplementsAll(spec.fallbackInstance.getClass(), spec.interfaces);
            Object instance = spec.fallbackInstance;
            action = DisabledActions.delegateTo(() -> instance);
        } else if (annotation != null && annotation.fallback() != Void.class) {
            action = fallbackObject(annotation.fallback(), spec.interfaces);
        } else {
            WhenDisabled whenDisabled = spec.whenDisabled != null ? spec.whenDisabled
                    : annotation != null ? annotation.whenDisabled() : WhenDisabled.THROW;
            action = DisabledActions.of(feature, whenDisabled);
        }
        return new FeatureCheck(feature, action);
    }

    private FeatureCheck methodCheck(Method method, Toggleable annotation) {
        String feature = annotation.value();
        requireName(feature, describe(method));
        boolean hasMethod = !annotation.fallbackMethod().isEmpty();
        boolean hasObject = annotation.fallback() != Void.class;
        if (hasMethod && hasObject) {
            throw new IllegalArgumentException("@Toggleable on " + describe(method)
                    + " sets both fallback and fallbackMethod; choose one");
        }
        if ((hasMethod || hasObject) && annotation.whenDisabled() == WhenDisabled.NO_OP) {
            throw new IllegalArgumentException("@Toggleable on " + describe(method) + " sets both "
                    + (hasMethod ? "fallbackMethod" : "fallback")
                    + " and whenDisabled = NO_OP; the fallback would always win, so choose one");
        }

        DisabledAction action;
        if (hasMethod) {
            action = DisabledActions.callMethod(fallbackMethodTarget,
                    findFallbackMethod(method, annotation.fallbackMethod()));
        } else if (hasObject) {
            action = fallbackObject(annotation.fallback(), Set.of(method.getDeclaringClass()));
        } else {
            action = DisabledActions.of(feature, annotation.whenDisabled());
        }
        return new FeatureCheck(feature, action);
    }

    /**
     * Fallback classes named in annotations, collected by {@link #resolve()}.
     */
    Set<Class<?>> fallbackTypes() {
        return Set.copyOf(fallbackTypes);
    }

    private DisabledAction fallbackObject(Class<?> type, Collection<Class<?>> required) {
        requireImplementsAll(type, required);
        fallbackTypes.add(type);
        if (spec.fallbackInstance != null && type.isInstance(spec.fallbackInstance)) {
            Object instance = spec.fallbackInstance;
            return DisabledActions.delegateTo(() -> instance);
        }
        FallbackResolver resolver = spec.fallbackResolver;
        if (resolver == null) {
            throw new IllegalStateException("No instance available for fallback " + type.getName()
                    + "; provide one with fallbackResolver(FallbackResolver.of(...))");
        }
        if (spec.lazyFallbacks) {
            return DisabledActions.delegateTo(new LazySupplier<>(() -> type.cast(resolver.resolve(type))));
        }
        Object instance = type.cast(Objects.requireNonNull(resolver.resolve(type),
                "fallback resolver returned null for " + type.getName()));
        return DisabledActions.delegateTo(() -> instance);
    }

    private Method findFallbackMethod(Method method, String name) {
        Class<?>[] params = method.getParameterTypes();
        for (Class<?> c = annotatedType; c != null && c != Object.class; c = c.getSuperclass()) {
            Method candidate;
            try {
                candidate = c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException e) {
                continue;
            }
            if (!returnCompatible(method.getReturnType(), candidate.getReturnType())) {
                throw new IllegalArgumentException("fallbackMethod " + c.getSimpleName() + "." + name
                        + " returns " + candidate.getReturnType().getName() + ", but " + describe(method)
                        + " returns " + method.getReturnType().getName());
            }
            if (!Modifier.isStatic(candidate.getModifiers())
                    && !candidate.getDeclaringClass().isInstance(fallbackMethodTarget)) {
                throw new IllegalArgumentException("fallbackMethod target is not an instance of "
                        + candidate.getDeclaringClass().getName());
            }
            if (!candidate.trySetAccessible()) {
                throw new IllegalArgumentException("fallbackMethod " + c.getName() + "." + name
                        + " is not accessible");
            }
            return candidate;
        }
        throw new IllegalArgumentException("fallbackMethod '" + name + "' with parameters "
                + Arrays.toString(params) + " not found on " + annotatedType.getName()
                + " (for " + describe(method) + ")");
    }

    private void rejectAnnotatedMethodsOutsideInterfaces() {
        for (Method annotated : ToggleAnnotations.annotatedClassMethods(annotatedType)) {
            boolean onInterface = spec.interfaces.stream().anyMatch(iface -> {
                try {
                    iface.getMethod(annotated.getName(), annotated.getParameterTypes());
                    return true;
                } catch (NoSuchMethodException e) {
                    return false;
                }
            });
            if (!onInterface) {
                throw new IllegalArgumentException("@Toggleable on " + annotated.getDeclaringClass().getName()
                        + "." + annotated.getName() + "() has no effect: the method is not part of a proxied interface");
            }
        }
    }

    private static boolean returnCompatible(Class<?> expected, Class<?> actual) {
        if (expected == void.class) {
            return true;
        }
        if (expected.isPrimitive() || actual.isPrimitive()) {
            return expected == actual;
        }
        return expected.isAssignableFrom(actual);
    }

    private static void requireImplementsAll(Class<?> type, Collection<Class<?>> interfaces) {
        for (Class<?> iface : interfaces) {
            if (!iface.isAssignableFrom(type)) {
                throw new IllegalArgumentException("Fallback " + type.getName() + " must implement " + iface.getName());
            }
        }
    }

    private static void requireName(String feature, String where) {
        if (feature.isBlank()) {
            throw new IllegalArgumentException("Blank feature name on " + where);
        }
    }

    private static String describe(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName() + "()";
    }
}
