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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The README's Spring guidance on several toggleable beans of one interface and on how fallback
 * beans are looked up.
 */
class QualifiedToggleableBeansTest {

    public interface Pricing {
        String quote();
    }

    public static class EuLegacy implements Pricing {
        private final String name;

        public EuLegacy() {
            this("eu-legacy");
        }

        EuLegacy(String name) {
            this.name = name;
        }

        @Override
        public String quote() {
            return name;
        }
    }

    public static class UsLegacy implements Pricing {
        @Override
        public String quote() {
            return "us-legacy";
        }
    }

    @Component("euPricing")
    @Toggleable(value = "eu-new", fallback = EuLegacy.class)
    public static class EuNew implements Pricing {
        @Override
        public String quote() {
            return "eu-new";
        }
    }

    @Component("usPricing")
    @Qualifier("us")
    @Toggleable(value = "us-new", fallback = UsLegacy.class)
    public static class UsNew implements Pricing {
        @Override
        public String quote() {
            return "us-new";
        }
    }

    @Component
    public static class QualifiedConsumer {
        final Pricing byName;
        final Pricing byQualifier;
        final Pricing fallbackByName;
        final List<Pricing> all;

        QualifiedConsumer(@Qualifier("euPricing") Pricing byName, @Qualifier("us") Pricing byQualifier,
                          @Qualifier("euLegacy") Pricing fallbackByName, List<Pricing> all) {
            this.byName = byName;
            this.byQualifier = byQualifier;
            this.fallbackByName = fallbackByName;
            this.all = all;
        }
    }

    @Component
    public static class UnqualifiedConsumer {
        UnqualifiedConsumer(Pricing pricing) {
        }
    }

    @Component
    public static class ParameterNameConsumer {
        ParameterNameConsumer(Pricing euPricing) {
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OneEach {
        @Bean
        EuLegacy euLegacy() {
            return new EuLegacy();
        }

        @Bean
        UsLegacy usLegacy() {
            return new UsLegacy();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoEuLegaciesOnePrimary {
        @Bean
        EuLegacy euLegacyA() {
            return new EuLegacy("a");
        }

        @Bean
        @Primary
        EuLegacy euLegacyB() {
            return new EuLegacy("b");
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FlipjackAutoConfiguration.class))
            .withPropertyValues("flipjack.toggles.eu-new=true", "flipjack.toggles.us-new=false");

    private static String rootMessage(Throwable failure) {
        return NestedExceptionUtils.getMostSpecificCause(failure).getMessage();
    }

    @Test
    void toggleableBeansOfOneInterfaceCanBeInjectedWithQualifiers() {
        runner.withUserConfiguration(OneEach.class, EuNew.class, UsNew.class, QualifiedConsumer.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    QualifiedConsumer consumer = context.getBean(QualifiedConsumer.class);
                    assertThat(consumer.byName.quote()).isEqualTo("eu-new");
                    assertThat(consumer.byQualifier.quote()).isEqualTo("us-legacy");
                    assertThat(consumer.fallbackByName.quote()).isEqualTo("eu-legacy");
                    assertThat(consumer.all).extracting(Pricing::quote)
                            .containsExactlyInAnyOrder("eu-legacy", "us-legacy", "eu-new", "us-legacy");
                });
    }

    @Test
    void unqualifiedInjectionWithTwoToggleableBeansFailsStartup() {
        runner.withUserConfiguration(OneEach.class, EuNew.class, UsNew.class, UnqualifiedConsumer.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure())).contains("more than one 'primary'");
                });
    }

    @Test
    void aMatchingParameterNameIsNotEnough() {
        runner.withUserConfiguration(OneEach.class, EuNew.class, UsNew.class, ParameterNameConsumer.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure())).contains("more than one 'primary'");
                });
    }

    @Test
    void aPrimaryFallbackBeanIsUsedWhenThereAreSeveral() {
        runner.withUserConfiguration(TwoEuLegaciesOnePrimary.class, EuNew.class)
                .withPropertyValues("flipjack.toggles.eu-new=false")
                .run(context -> assertThat(context.getBean("euPricing", Pricing.class).quote()).isEqualTo("b"));
    }

    @Test
    void aMissingFallbackBeanFailsStartup() {
        runner.withUserConfiguration(EuNew.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure())).contains(EuLegacy.class.getName());
                });
    }
}
