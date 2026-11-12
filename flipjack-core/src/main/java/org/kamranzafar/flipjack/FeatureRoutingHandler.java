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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The proxy's single entry point. Looks up the pre-resolved route for the called
 * method, runs its feature checks in order, and either lets the first failing check's
 * strategy handle the call or invokes the feature itself.
 */
final class FeatureRoutingHandler implements InvocationHandler {

    private final Object enabled;
    private final Object target;
    private final Map<Method, List<FeatureCheck>> routes;
    private final Supplier<? extends FeatureToggles> toggles;
    private final Supplier<? extends FeatureContext> context;
    private final Set<String> features;
    private final Set<Class<?>> fallbackTypes;

    FeatureRoutingHandler(Object enabled, Object target, Map<Method, List<FeatureCheck>> routes,
                          Supplier<? extends FeatureToggles> toggles,
                          Supplier<? extends FeatureContext> context, Set<String> features,
                          Set<Class<?>> fallbackTypes) {
        this.enabled = enabled;
        this.target = target;
        this.routes = routes;
        this.toggles = toggles;
        this.context = context;
        this.features = features;
        this.fallbackTypes = fallbackTypes;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Class<?> declaring = method.getDeclaringClass();
        if (declaring == Object.class) {
            return objectMethod(proxy, method, args);
        }
        if (declaring == Toggled.class) {
            return method.getName().equals("fallbackTypes") ? fallbackTypes : features;
        }

        List<FeatureCheck> checks = routes.get(method);
        if (checks != null) {
            FeatureToggles current = toggles.get();
            // read once so the class and method checks decide for the same context
            FeatureContext callContext = Objects.requireNonNullElse(context.get(), FeatureContext.EMPTY);
            for (FeatureCheck check : checks) {
                if (!current.isEnabled(check.feature(), callContext)) {
                    return check.action().handle(method, args);
                }
            }
        }
        return Invocations.invoke(enabled, method, args);
    }

    /**
     * The raw implementation behind any integration proxy, for inspecting its fields.
     */
    Object target() {
        return target;
    }

    private Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "ToggledFeature" + features + " -> " + enabled.getClass().getName();
            default -> throw new UnsupportedOperationException(method.toString());
        };
    }
}
