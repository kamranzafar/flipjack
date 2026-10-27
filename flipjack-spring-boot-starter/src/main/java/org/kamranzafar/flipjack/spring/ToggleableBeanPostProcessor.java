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

package org.kamranzafar.flipjack.spring;

import org.kamranzafar.flipjack.FeatureContext;
import org.kamranzafar.flipjack.FeatureToggles;
import org.kamranzafar.flipjack.ToggledFeature;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;
import org.springframework.util.function.SingletonSupplier;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * Wraps Spring beans that use Flipjack's toggleable feature support.
 *
 * <p>The core Flipjack library performs annotation discovery, validation and
 * routing. This Spring integration supplies the Spring-specific dependencies:
 * the bean, its target class, feature-toggle source, fallback bean resolver and
 * optional feature context provider.</p>
 *
 * <p>The post processor runs with the lowest precedence so that other Spring
 * proxies such as transactions and caching can be applied before the
 * toggleable wrapper.</p>
 */
public class ToggleableBeanPostProcessor
        implements BeanPostProcessor,
        BeanFactoryAware,
        BeanClassLoaderAware,
        SmartInitializingSingleton,
        Ordered {

    private static final FeatureContextProvider NO_CONTEXT =
            () -> FeatureContext.EMPTY;

    private final Supplier<FeatureToggles> toggles;

    private final Supplier<FeatureContextProvider> contextProvider;

    private BeanFactory beanFactory;

    private ClassLoader beanClassLoader =
            ClassUtils.getDefaultClassLoader();

    /**
     * Creates the post processor using the default context provider.
     *
     * @param toggles provider for feature toggles
     */
    public ToggleableBeanPostProcessor(
            ObjectProvider<FeatureToggles> toggles) {

        this(toggles, () -> NO_CONTEXT);
    }

    /**
     * Creates the post processor with a configurable context provider.
     *
     * @param toggles provider for feature toggles
     * @param contextProviders provider for feature context
     */
    public ToggleableBeanPostProcessor(
            ObjectProvider<FeatureToggles> toggles,
            ObjectProvider<FeatureContextProvider> contextProviders) {

        this(
                toggles,
                () -> contextProviders.getIfAvailable(
                        () -> NO_CONTEXT
                )
        );
    }

    /**
     * Internal constructor used to initialize lazy suppliers.
     *
     * @param toggles provider for feature toggles
     * @param contextProvider context-provider supplier
     */
    private ToggleableBeanPostProcessor(
            ObjectProvider<FeatureToggles> toggles,
            Supplier<FeatureContextProvider> contextProvider) {

        /*
         * Resolve lazily so this post processor does not force early creation
         * of the FeatureToggles bean.
         */
        this.toggles =
                SingletonSupplier.of(toggles::getObject);

        this.contextProvider =
                SingletonSupplier.of(contextProvider);
    }

    /**
     * Resolves the context provider before application traffic starts.
     *
     * <p>This makes an invalid context-provider configuration fail during
     * startup rather than during a request.</p>
     */
    @Override
    public void afterSingletonsInstantiated() {
        contextProvider.get();
    }

    /**
     * Receives the Spring BeanFactory.
     *
     * @param beanFactory Spring bean factory
     */
    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /**
     * Receives the application class loader.
     *
     * @param classLoader application class loader
     */
    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.beanClassLoader = classLoader;
    }

    /**
     * Runs after other normal bean post processors.
     *
     * @return lowest Spring precedence
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * Wraps a toggleable bean after its normal initialization.
     *
     * @param bean initialized Spring bean
     * @param beanName Spring bean name
     * @return original bean or toggleable wrapper
     * @throws BeansException when bean configuration is invalid
     */
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Object postProcessAfterInitialization(
            Object bean,
            String beanName) throws BeansException {

        Class<?> targetClass = AopUtils.getTargetClass(bean);

        if (!ToggledFeature.isToggleable(targetClass)) {
            return bean;
        }

        Class<?>[] interfaces = serviceInterfaces(targetClass);

        if (interfaces.length == 0) {
            throw new BeanCreationException(
                    beanName,
                    "@Toggleable bean "
                            + targetClass.getName()
                            + " must implement at least one interface"
            );
        }

        /*
         * fallbackMethods must execute against the real target object rather
         * than against an AOP proxy.
         */
        Object rawTarget =
                AopProxyUtils.getSingletonTarget(bean);

        try {

            return ToggledFeature
                    .builder((Class) interfaces[0])

                    .alsoImplements(
                            Arrays.copyOfRange(
                                    interfaces,
                                    1,
                                    interfaces.length
                            )
                    )

                    .enabled(bean)

                    .annotatedType(targetClass)

                    .fallbackMethodTarget(
                            rawTarget != null
                                    ? rawTarget
                                    : bean
                    )

                    .lazyToggles(toggles)

                    .context(
                            () -> contextProvider
                                    .get()
                                    .currentContext()
                    )

                    .lazyFallbackResolver(
                            type -> beanFactory.getBean(type)
                    )

                    .classLoader(beanClassLoader)

                    .build();

        } catch (IllegalArgumentException | IllegalStateException e) {

            throw new BeanCreationException(
                    beanName,
                    e.getMessage(),
                    e
            );
        }
    }

    /**
     * Returns the application-facing interfaces implemented by the bean.
     *
     * <p>Spring infrastructure interfaces and {@code java.io} interfaces are
     * excluded because they should not be exposed by the Flipjack proxy.</p>
     *
     * @param type bean class
     * @return service interfaces
     */
    private static Class<?>[] serviceInterfaces(Class<?> type) {

        return Arrays.stream(
                        ClassUtils.getAllInterfacesForClass(type)
                )

                .filter(
                        i -> !i.getName()
                                .startsWith("org.springframework.")

                                && !i.getName()
                                .startsWith("java.io.")
                )

                .toArray(Class<?>[]::new);
    }
}