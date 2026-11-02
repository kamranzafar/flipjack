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
package org.kamranzafar.flipjack.openfeature;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.ProviderState;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import org.junit.jupiter.api.Test;
import org.kamranzafar.flipjack.FeatureToggles;
import org.kamranzafar.flipjack.OverridableFeatureToggles;
import org.kamranzafar.flipjack.Toggleable;
import org.kamranzafar.flipjack.spring.FlipjackAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Spring Boot wiring shown in the README: an OpenFeature-backed {@code FeatureToggles} bean
 * replaces the configuration-backed default.
 */
class OpenFeatureSpringBootTest {

    public interface Pricing {
        String quote();
    }

    public static class LegacyPricing implements Pricing {
        @Override
        public String quote() {
            return "legacy";
        }
    }

    @Toggleable(value = "new-pricing", fallback = LegacyPricing.class)
    public static class NewPricing implements Pricing {
        @Override
        public String quote() {
            return "new";
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({LegacyPricing.class, NewPricing.class})
    static class App {

        @Bean
        InMemoryProvider provider() {
            return new InMemoryProvider(Map.of("new-pricing", flag(true)));
        }

        @Bean
        Client client(InMemoryProvider provider) {
            String domain = UUID.randomUUID().toString();
            OpenFeatureAPI.getInstance().setProviderAndWait(domain, provider);
            return OpenFeatureAPI.getInstance().getClient(domain);
        }

        // as in the README
        @Bean
        FeatureToggles featureToggles(Client client) {
            return OpenFeatureToggles.builder(client)
                    .requireReady(Duration.ofSeconds(10))
                    .build()
                    .withOverrides();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({LegacyPricing.class, NewPricing.class})
    static class NeverReadyApp {

        @Bean
        FeatureToggles featureToggles() {
            String domain = UUID.randomUUID().toString();
            OpenFeatureAPI.getInstance().setProvider(domain, new InMemoryProvider(Map.of()) {
                @Override
                public void initialize(dev.openfeature.sdk.EvaluationContext context) throws Exception {
                    Thread.sleep(Long.MAX_VALUE);
                }
            });
            return OpenFeatureToggles.builder(OpenFeatureAPI.getInstance().getClient(domain))
                    .requireReady(Duration.ofMillis(200))
                    .build();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FlipjackAutoConfiguration.class));

    private static Flag<Boolean> flag(boolean enabled) {
        return Flag.<Boolean>builder()
                .variant("on", true)
                .variant("off", false)
                .defaultVariant(enabled ? "on" : "off")
                .build();
    }

    @Test
    void openFeatureBeanReplacesTheConfigurationDefault() {
        runner.withUserConfiguration(App.class)
                // ignored: the OpenFeature bean is the source now
                .withPropertyValues("flipjack.toggles.new-pricing=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(FeatureToggles.class);
                    assertThat(context.getBean(FeatureToggles.class)).isInstanceOf(OverridableFeatureToggles.class);
                    assertThat(context.getBean(Pricing.class).quote()).isEqualTo("new");
                });
    }

    @Test
    void routesByProviderStateAndOverrides() {
        runner.withUserConfiguration(App.class).run(context -> {
            Pricing pricing = context.getBean(Pricing.class);
            InMemoryProvider provider = context.getBean(InMemoryProvider.class);

            provider.updateFlag("new-pricing", flag(false));
            assertThat(pricing.quote()).isEqualTo("legacy");

            context.getBean(OverridableFeatureToggles.class).override("new-pricing", true);
            assertThat(pricing.quote()).isEqualTo("new");
        });
    }

    @Test
    void closingTheContextLeavesTheProviderRunning() {
        Client[] client = new Client[1];
        runner.withUserConfiguration(App.class).run(context -> client[0] = context.getBean(Client.class));

        assertThat(client[0].getProviderState()).isEqualTo(ProviderState.READY);
    }

    @Test
    void startupFailsWhenTheProviderIsNotReady() {
        runner.withUserConfiguration(NeverReadyApp.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not ready");
        });
    }
}
