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

import org.junit.jupiter.api.Test;
import org.kamranzafar.flipjack.Toggleable;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.Scope;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fallback beans are checked by name at startup: a missing one, or one that is only the
 * toggled bean itself, fails startup; anything Spring might still settle is left to the call.
 */
class FallbackStartupCheckTest {

    public interface Pricing {
        String quote();
    }

    public static class Legacy implements Pricing {
        private final String name;

        public Legacy() {
            this("legacy");
        }

        Legacy(String name) {
            this.name = name;
        }

        @Override
        public String quote() {
            return name;
        }
    }

    @Component("newPricing")
    @Toggleable(value = "new-pricing", fallback = Legacy.class)
    public static class NewPricing implements Pricing {
        @Override
        public String quote() {
            return "new";
        }
    }

    @Component("selfPricing")
    @Toggleable(value = "new-pricing", fallback = Pricing.class)
    public static class SelfPricing implements Pricing {
        @Override
        public String quote() {
            return "self";
        }
    }

    @Component("lazySelfPricing")
    @Lazy
    @Toggleable(value = "new-pricing", fallback = Pricing.class)
    public static class LazySelfPricing implements Pricing {
        @Override
        public String quote() {
            return "lazy self";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OneLegacy {
        @Bean
        Legacy legacy() {
            return new Legacy();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoLegacies {
        @Bean
        Legacy legacyA() {
            return new Legacy("a");
        }

        @Bean
        Legacy legacyB() {
            return new Legacy("b");
        }
    }

    /**
     * A scope that is inactive at startup, like a request scope outside a request.
     */
    static class SwitchableScope implements Scope {
        private final Map<String, Object> beans = new HashMap<>();
        boolean active;

        @Override
        public Object get(String name, ObjectFactory<?> factory) {
            if (!active) {
                throw new IllegalStateException("scope 'switchable' is not active");
            }
            return beans.computeIfAbsent(name, n -> factory.getObject());
        }

        @Override
        public Object remove(String name) {
            return beans.remove(name);
        }

        @Override
        public void registerDestructionCallback(String name, Runnable callback) {
        }

        @Override
        public Object resolveContextualObject(String key) {
            return null;
        }

        @Override
        public String getConversationId() {
            return null;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ScopedLegacy {
        static final SwitchableScope SCOPE = new SwitchableScope();

        @Bean
        static BeanFactoryPostProcessor switchableScope() {
            return factory -> factory.registerScope("switchable", SCOPE);
        }

        @Bean
        @org.springframework.context.annotation.Scope("switchable")
        Legacy legacy() {
            return new Legacy("scoped");
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FlipjackAutoConfiguration.class))
            .withPropertyValues("flipjack.toggles.new-pricing=false");

    private static String rootMessage(Throwable failure) {
        return NestedExceptionUtils.getMostSpecificCause(failure).getMessage();
    }

    @Test
    void aPresentFallbackStartsAndIsUsed() {
        runner.withUserConfiguration(OneLegacy.class, NewPricing.class)
                .run(context -> assertThat(context.getBean("newPricing", Pricing.class).quote()).isEqualTo("legacy"));
    }

    @Test
    void aMissingFallbackBeanFailsStartupNamingTheBean() {
        runner.withUserConfiguration(NewPricing.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("newPricing");
                    assertThat(rootMessage(context.getStartupFailure()))
                            .contains(Legacy.class.getName(), "has no bean");
                });
    }

    @Test
    void aFallbackThatIsOnlyTheToggledBeanItselfFailsStartup() {
        runner.withUserConfiguration(SelfPricing.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure())).contains("'selfPricing' itself");
                });
    }

    @Test
    void aLazyToggledBeanIsCheckedWhenItIsCreated() {
        runner.withUserConfiguration(LazySelfPricing.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThatThrownBy(() -> context.getBean("lazySelfPricing"))
                            .satisfies(e -> assertThat(rootMessage(e)).contains("'lazySelfPricing' itself")
                                    .doesNotContain("circular"));
                });
    }

    @Test
    void aFallbackInAScopeInactiveAtStartupStillStartsAndWorksInItsScope() {
        runner.withUserConfiguration(ScopedLegacy.class, NewPricing.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ScopedLegacy.SCOPE.active = true;
                    try {
                        assertThat(context.getBean("newPricing", Pricing.class).quote()).isEqualTo("scoped");
                    } finally {
                        ScopedLegacy.SCOPE.active = false;
                    }
                });
    }

    @Test
    void severalFallbackCandidatesStillStartAndAreLeftToTheCall() {
        runner.withUserConfiguration(TwoLegacies.class, NewPricing.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThatThrownBy(() -> context.getBean("newPricing", Pricing.class).quote())
                            .isInstanceOf(NoUniqueBeanDefinitionException.class);
                });
    }
}
