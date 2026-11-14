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
import org.kamranzafar.flipjack.FeatureToggles;
import org.kamranzafar.flipjack.Toggled;
import org.kamranzafar.flipjack.ToggledFeature;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;

import static org.assertj.core.api.Assertions.assertThat;

class StepToggleDiscoveryTest {

    public interface TaxRule {
        long tax(long amount);
    }

    /**
     * A plain bean, not toggleable itself, with one toggled step.
     */
    public static class CheckoutService {
        private final TaxRule tax;

        public CheckoutService(FeatureToggles toggles) {
            this.tax = ToggledFeature.either(TaxRule.class, "regional-tax",
                    this::regionalTax, this::flatTax, toggles);
        }

        public long total(long amount) {
            return amount + tax.tax(amount);
        }

        private long regionalTax(long amount) {
            return amount / 5;
        }

        private long flatTax(long amount) {
            return amount / 10;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(CheckoutService.class)
    static class Components {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FlipjackAutoConfiguration.class))
            .withUserConfiguration(Components.class)
            .withPropertyValues("flipjack.consistency-check=fail");

    @Test
    void stepRoutesOnTheConfiguredToggle() {
        runner.withPropertyValues("flipjack.toggles.regional-tax=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CheckoutService.class).total(100)).isEqualTo(120);
        });
    }

    @Test
    void consistencyCheckFindsAMisspeltStepFeature() {
        runner.withPropertyValues("flipjack.toggles.regional-taks=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                    .hasMessageContaining("regional-tax");
        });
    }

    @Test
    void endpointListsStepFeatures() {
        runner.withPropertyValues("flipjack.toggles.regional-tax=false").run(context -> {
            FlipjackEndpoint endpoint = new FlipjackEndpoint(context.getBean(FlipjackProperties.class),
                    context.getBean(FeatureToggles.class), context.getBeanProvider(Toggled.class),
                    context.getBeanFactory());
            assertThat(endpoint.features()).containsEntry("regional-tax", false);
        });
    }

    /**
     * A plain bean to be wrapped in an AOP proxy for testing step discovery.
     */
    public static class ProxiedService {
        private final TaxRule tax;

        public ProxiedService(FeatureToggles toggles) {
            // Intentionally using a misspelled feature name
            this.tax = ToggledFeature.either(TaxRule.class, "misspelt-proxy-feature",
                    amount -> amount, amount -> amount, toggles);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProxyComponents {
        @Bean
        public Object proxiedServiceBean(FeatureToggles toggles) {
            ProxiedService target = new ProxiedService(toggles);

            ProxyFactory factory = new ProxyFactory(target);
            factory.setProxyTargetClass(true); // Forces a CGLIB subclass proxy

            return factory.getProxy();
        }
    }

    @Test
    void consistencyCheckFindsAMisspeltStepFeatureBehindAnAopProxy() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FlipjackAutoConfiguration.class))
                .withUserConfiguration(ProxyComponents.class)
                .withPropertyValues("flipjack.consistency-check=fail")
                .withPropertyValues("flipjack.toggles.some-other-toggle=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                            .hasMessageContaining("misspelt-proxy-feature");
                });
    }
}
