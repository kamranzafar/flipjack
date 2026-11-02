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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Supplier;

/**
 * Creates toggle-routing proxies for class-level and method-level features.
 *
 * <pre>{@code
 * // Class level: decorator on, plain implementation as fallback
 * Feature feature = ToggledFeature.builder(Feature.class)
 *         .feature("premium")
 *         .enabled(new PremiumFeatureDecorator(basic))
 *         .fallback(basic)
 *         .toggles(toggles)
 *         .build();
 *
 * // Method level: driven by @Toggleable on methods
 * PricingService pricing = ToggledFeature.builder(PricingService.class)
 *         .enabled(new SmartPricing())
 *         .fallbackResolver(FallbackResolver.of(legacyPricing))
 *         .toggles(toggles)
 *         .build();
 * }</pre>
 * <p>
 * Builder settings override the class-level annotation. All annotations are read and
 * validated when {@link Builder#build()} runs, so misconfiguration fails immediately.
 * Proxies are JDK dynamic proxies: toggled types must be interfaces.
 */
public final class ToggledFeature {

    private ToggledFeature() {
    }

    public static <T> Builder<T> builder(Class<T> type) {
        return new Builder<>(type);
    }

    /**
     * Shorthand for an annotated implementation with an optional class-level fallback.
     *
     * @param fallback may be {@code null}
     */
    public static <T> T of(Class<T> type, T enabled, T fallback, FeatureToggles toggles) {
        Builder<T> builder = builder(type).enabled(enabled).toggles(toggles);
        if (fallback != null) {
            builder.fallback(fallback);
        }
        return builder.build();
    }

    /**
     * Toggles one step inside a component: {@code enabled} while {@code feature} is on,
     * {@code fallback} while it is off. Typically used with method references, so both
     * variants can stay private methods next to the code that calls them:
     * <pre>{@code
     * this.tax = ToggledFeature.either(TaxRule.class, Features.REGIONAL_TAX,
     *         this::regionalTax, this::flatTax, toggles);
     * }</pre>
     * Calls go through the returned proxy, so this also works from inside the component.
     * {@link #featuresUsedBy(Object)} finds the feature through the field holding the proxy.
     */
    public static <T> T either(Class<T> type, String feature, T enabled, T fallback, FeatureToggles toggles) {
        return builder(type).feature(feature).enabled(enabled).fallback(fallback).toggles(toggles).build();
    }

    /**
     * Every feature {@code component} checks: its own, if it is a toggled proxy, plus those of
     * toggled proxies held in its instance fields (one level deep), such as the ones
     * {@link #either} creates. Used by consistency checks and admin endpoints.
     */
    public static Set<String> featuresUsedBy(Object component) {
        Set<String> features = new TreeSet<>();
        Object target = component;
        if (component instanceof Toggled toggled) {
            features.addAll(toggled.toggledFeatures());
            if (Proxy.isProxyClass(component.getClass())
                    && Proxy.getInvocationHandler(component) instanceof FeatureRoutingHandler handler) {
                target = handler.target();
            }
        }
        for (Class<?> c = target.getClass(); c != null && !isPlatformClass(c); c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()
                        || !field.trySetAccessible()) {
                    continue;
                }
                try {
                    if (field.get(target) instanceof Toggled held) {
                        features.addAll(held.toggledFeatures());
                    }
                } catch (IllegalAccessException ignored) {
                    // not readable; it can't contribute features
                }
            }
        }
        return features;
    }

    private static boolean isPlatformClass(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.");
    }

    /**
     * True if the class or any of its (or its interfaces') methods is annotated with {@link Toggleable}.
     */
    public static boolean isToggleable(Class<?> type) {
        return ToggleAnnotations.isToggleable(type);
    }

    public static final class Builder<T> {

        // read by RouteResolver
        final Class<T> type;
        final Set<Class<?>> interfaces = new LinkedHashSet<>();
        String feature;
        Object enabled;
        Object fallbackInstance;
        WhenDisabled whenDisabled;
        Supplier<? extends FeatureToggles> toggles;
        Supplier<? extends FeatureContext> context = () -> FeatureContext.EMPTY;
        FallbackResolver fallbackResolver;
        boolean lazyFallbacks;
        Class<?> annotatedType;
        Object fallbackMethodTarget;
        ClassLoader classLoader;

        private Builder(Class<T> type) {
            this.type = requireInterface(type);
            interfaces.add(type);
        }

        /**
         * Additional interfaces the proxy should expose; the implementation must implement them too.
         */
        public Builder<T> alsoImplements(Class<?>... more) {
            for (Class<?> iface : more) {
                interfaces.add(requireInterface(iface));
            }
            return this;
        }

        /**
         * Class-level feature name. Optional if the implementation class is annotated.
         */
        public Builder<T> feature(String feature) {
            this.feature = Objects.requireNonNull(feature, "feature");
            return this;
        }

        /**
         * The implementation used while its features are on.
         */
        public Builder<T> enabled(T implementation) {
            this.enabled = Objects.requireNonNull(implementation, "enabled");
            return this;
        }

        /**
         * Class-level fallback, used for every method while the class-level feature is
         * off. Also satisfies any annotation {@code fallback} class it is an instance of.
         */
        public Builder<T> fallback(T implementation) {
            this.fallbackInstance = Objects.requireNonNull(implementation, "fallback");
            return this;
        }

        /**
         * Class-level behaviour when off and there is no fallback. Setting it while a
         * class-level fallback (builder or annotation) is configured fails the build, since the
         * fallback would always win.
         */
        public Builder<T> whenDisabled(WhenDisabled whenDisabled) {
            this.whenDisabled = Objects.requireNonNull(whenDisabled, "whenDisabled");
            return this;
        }

        public Builder<T> toggles(FeatureToggles toggles) {
            Objects.requireNonNull(toggles, "toggles");
            this.toggles = () -> toggles;
            return this;
        }

        /**
         * Toggles resolved on use, for when the toggle source is created after the proxy. The
         * supplier is called on every call, so memoize it if creating the source is expensive.
         */
        public Builder<T> lazyToggles(Supplier<? extends FeatureToggles> supplier) {
            this.toggles = Objects.requireNonNull(supplier, "toggles");
            return this;
        }

        /**
         * Supplies the context each call is evaluated for, read once per call, typically from
         * the current request (for example a {@code ThreadLocal} your web filter sets).
         * Defaults to {@link FeatureContext#EMPTY}; a {@code null} result is treated as empty.
         * Context bound to a thread does not follow work handed to other threads.
         */
        public Builder<T> context(Supplier<? extends FeatureContext> context) {
            this.context = Objects.requireNonNull(context, "context");
            return this;
        }

        /**
         * Resolves annotation {@code fallback} classes to instances, eagerly at build time.
         */
        public Builder<T> fallbackResolver(FallbackResolver resolver) {
            this.fallbackResolver = Objects.requireNonNull(resolver, "resolver");
            this.lazyFallbacks = false;
            return this;
        }

        /**
         * Like {@link #fallbackResolver} but resolves on first disabled call (for DI containers).
         */
        public Builder<T> lazyFallbackResolver(FallbackResolver resolver) {
            this.fallbackResolver = Objects.requireNonNull(resolver, "resolver");
            this.lazyFallbacks = true;
            return this;
        }

        /**
         * Where to read annotations from; defaults to the implementation's class.
         */
        public Builder<T> annotatedType(Class<?> annotatedType) {
            this.annotatedType = Objects.requireNonNull(annotatedType, "annotatedType");
            return this;
        }

        /**
         * Object on which {@code fallbackMethod}s are called; defaults to the implementation.
         * Integrations pass the raw object when the implementation is itself a proxy.
         */
        public Builder<T> fallbackMethodTarget(Object target) {
            this.fallbackMethodTarget = Objects.requireNonNull(target, "target");
            return this;
        }

        public Builder<T> classLoader(ClassLoader classLoader) {
            this.classLoader = classLoader;
            return this;
        }

        public T build() {
            if (enabled == null) {
                throw new IllegalStateException("enabled implementation is required");
            }
            if (toggles == null) {
                throw new IllegalStateException("toggles are required");
            }
            for (Class<?> iface : interfaces) {
                if (!iface.isInstance(enabled)) {
                    throw new IllegalArgumentException("Implementation " + enabled.getClass().getName()
                            + " does not implement " + iface.getName());
                }
            }

            Map<Method, List<FeatureCheck>> routes = new RouteResolver(this).resolve();
            Set<String> features = new TreeSet<>();
            routes.values().forEach(checks -> checks.forEach(check -> features.add(check.feature())));

            Set<Class<?>> proxyInterfaces = new LinkedHashSet<>(interfaces);
            proxyInterfaces.add(Toggled.class);
            ClassLoader loader = classLoader != null ? classLoader : type.getClassLoader();

            // the raw object (not an integration's proxy around it) is what featuresUsedBy inspects
            Object target = fallbackMethodTarget != null ? fallbackMethodTarget : enabled;
            Object proxy = Proxy.newProxyInstance(loader, proxyInterfaces.toArray(Class<?>[]::new),
                    new FeatureRoutingHandler(enabled, target, routes, toggles, context,
                            Collections.unmodifiableSet(features)));
            return type.cast(proxy);
        }

        private static <C> Class<C> requireInterface(Class<C> type) {
            Objects.requireNonNull(type, "type");
            if (!type.isInterface()) {
                throw new IllegalArgumentException(type.getName() + " is not an interface");
            }
            return type;
        }
    }
}
