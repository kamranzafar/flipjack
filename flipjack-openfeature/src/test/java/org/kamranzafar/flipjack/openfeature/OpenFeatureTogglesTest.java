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
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventProvider;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEventDetails;
import dev.openfeature.sdk.ProviderState;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.exceptions.FatalError;
import dev.openfeature.sdk.exceptions.FlagNotFoundError;
import dev.openfeature.sdk.providers.memory.Flag;
import dev.openfeature.sdk.providers.memory.InMemoryProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.kamranzafar.flipjack.FeatureContext;
import org.kamranzafar.flipjack.FeatureToggles;
import org.kamranzafar.flipjack.OverridableFeatureToggles;
import org.kamranzafar.flipjack.Toggleable;
import org.kamranzafar.flipjack.ToggledFeature;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class OpenFeatureTogglesTest {

    private static final OpenFeatureAPI API = OpenFeatureAPI.getInstance();

    @AfterAll
    static void shutdown() {
        API.shutdown();
    }

    /**
     * A client bound to a fresh domain, so tests don't share a provider.
     */
    private static Client clientFor(FeatureProvider provider) {
        String domain = UUID.randomUUID().toString();
        API.setProviderAndWait(domain, provider);
        return API.getClient(domain);
    }

    private static Flag<Boolean> flag(boolean enabled) {
        return Flag.<Boolean>builder()
                .variant("on", true)
                .variant("off", false)
                .defaultVariant(enabled ? "on" : "off")
                .build();
    }

    @Test
    void evaluatesBooleanFlags() {
        Client client = clientFor(new InMemoryProvider(Map.of("premium", flag(true), "new-quote", flag(false))));
        OpenFeatureToggles toggles = OpenFeatureToggles.builder(client).build();

        assertTrue(toggles.isEnabled("premium"));
        assertFalse(toggles.isEnabled("new-quote"));
        assertTrue(toggles.isDisabled("new-quote"));
        assertSame(client, toggles.client());
    }

    @Test
    void mapsFeatureContextToEvaluationContext() {
        Flag<Boolean> beta = Flag.<Boolean>builder()
                .variant("on", true)
                .variant("off", false)
                .defaultVariant("off")
                .contextEvaluator((flag, context) -> "alice".equals(context.getTargetingKey())
                        || "eu".equals(context.getValue("region") == null ? null : context.getValue("region").asString()))
                .build();
        OpenFeatureToggles toggles = OpenFeatureToggles.builder(clientFor(new InMemoryProvider(Map.of("beta", beta))))
                .build();

        assertTrue(toggles.isEnabled("beta", FeatureContext.of("alice")));
        assertFalse(toggles.isEnabled("beta", FeatureContext.of("bob")));
        assertTrue(toggles.isEnabled("beta", FeatureContext.of("bob").with("region", "eu")));
        assertTrue(toggles.isEnabled("beta", FeatureContext.EMPTY.with("region", "eu")));
        assertFalse(toggles.isEnabled("beta"));
    }

    @Test
    void emptyContextKeepsClientContext() {
        Flag<Boolean> beta = Flag.<Boolean>builder()
                .variant("on", true)
                .variant("off", false)
                .defaultVariant("off")
                .contextEvaluator((flag, context) -> "alice".equals(context.getTargetingKey()))
                .build();
        Client client = clientFor(new InMemoryProvider(Map.of("beta", beta)));
        client.setEvaluationContext(new ImmutableContext("alice"));
        OpenFeatureToggles toggles = OpenFeatureToggles.builder(client).build();

        assertTrue(toggles.isEnabled("beta", FeatureContext.EMPTY));
    }

    @Test
    void missingFlagUsesDefaultAndIsReportedEveryTime() {
        List<ErrorCode> errors = new CopyOnWriteArrayList<>();
        InMemoryProvider provider = new InMemoryProvider(Map.of());
        OpenFeatureToggles toggles = OpenFeatureToggles.builder(clientFor(provider))
                .onEvaluationError((feature, details) -> errors.add(details.getErrorCode()))
                .build();

        assertFalse(toggles.isEnabled("premium"));
        assertFalse(toggles.isEnabled("premium"));
        assertEquals(List.of(ErrorCode.FLAG_NOT_FOUND, ErrorCode.FLAG_NOT_FOUND), errors);

        OpenFeatureToggles enabledByDefault = OpenFeatureToggles.builder(clientFor(new InMemoryProvider(Map.of())))
                .defaultEnabled(true)
                .onEvaluationError((feature, details) -> { })
                .build();
        assertTrue(enabledByDefault.isEnabled("premium"));
    }

    @Test
    void defaultLogWarnsOncePerFeatureUntilItRecovers() {
        List<String> messages = new CopyOnWriteArrayList<>();
        SwitchableProvider provider = new SwitchableProvider();
        OpenFeatureToggles toggles = OpenFeatureToggles.builder(clientFor(provider))
                .errorLog(new ThrottledErrorLog(messages::add))
                .build();

        toggles.isEnabled("premium");
        toggles.isEnabled("premium");
        toggles.isEnabled("new-quote");
        assertEquals(2, messages.size(), messages.toString());
        assertTrue(messages.get(0).contains("'premium'") && messages.get(0).contains("FLAG_NOT_FOUND"), messages.get(0));

        provider.state = true;
        assertTrue(toggles.isEnabled("premium"));
        provider.state = null;
        toggles.isEnabled("premium");
        toggles.isEnabled("new-quote");

        assertEquals(3, messages.size(), messages.toString());
        assertTrue(messages.get(2).contains("'premium'"), messages.get(2));
    }

    @Test
    void customHandlerReplacesTheDefaultLog() {
        List<String> messages = new CopyOnWriteArrayList<>();
        List<String> errors = new CopyOnWriteArrayList<>();
        OpenFeatureToggles toggles = OpenFeatureToggles.builder(clientFor(new SwitchableProvider()))
                .errorLog(new ThrottledErrorLog(messages::add))
                .onEvaluationError((feature, details) -> errors.add(feature))
                .build();

        toggles.isEnabled("premium");
        toggles.isEnabled("premium");

        assertEquals(List.of("premium", "premium"), errors);
        assertTrue(messages.isEmpty(), messages.toString());
    }

    @Test
    void typeMismatchUsesDefault() {
        Flag<String> checkout = Flag.<String>builder().variant("v2", "v2").defaultVariant("v2").build();
        List<ErrorCode> errors = new CopyOnWriteArrayList<>();
        OpenFeatureToggles toggles = OpenFeatureToggles.builder(clientFor(new InMemoryProvider(Map.of("checkout", checkout))))
                .onEvaluationError((feature, details) -> errors.add(details.getErrorCode()))
                .build();

        assertFalse(toggles.isEnabled("checkout"));
        assertEquals(List.of(ErrorCode.TYPE_MISMATCH), errors);
    }

    @Test
    void brokenErrorHandlerDoesNotBreakTheCall() {
        OpenFeatureToggles toggles = OpenFeatureToggles.builder(clientFor(new InMemoryProvider(Map.of())))
                .onEvaluationError((feature, details) -> {
                    throw new IllegalStateException("broken handler");
                })
                .build();

        assertFalse(toggles.isEnabled("premium"));
    }

    @Test
    void overridesWinOverAProviderThatIsDown() {
        List<ErrorCode> errors = new CopyOnWriteArrayList<>();
        OverridableFeatureToggles toggles = OpenFeatureToggles.builder(clientFor(new FailingProvider()))
                .onEvaluationError((feature, details) -> errors.add(details.getErrorCode()))
                .build()
                .withOverrides();

        assertFalse(toggles.isEnabled("premium"));
        assertEquals(List.of(ErrorCode.GENERAL), errors);

        toggles.override("premium", true);
        assertTrue(toggles.isEnabled("premium"));
        assertTrue(toggles.isEnabled("premium", FeatureContext.of("alice")));
    }

    @Test
    void requireReadyPassesForAReadyProvider() {
        OpenFeatureToggles toggles = OpenFeatureToggles.builder(clientFor(new InMemoryProvider(Map.of("premium", flag(true)))))
                .requireReady(Duration.ofSeconds(5))
                .build();

        assertTrue(toggles.isEnabled("premium"));
    }

    @Test
    void requireReadyFailsWhenTheProviderIsNotReadyInTime() {
        CountDownLatch release = new CountDownLatch(1);
        String domain = UUID.randomUUID().toString();
        API.setProvider(domain, new SlowProvider(release));
        try {
            OpenFeatureToggles.Builder builder = OpenFeatureToggles.builder(API.getClient(domain))
                    .requireReady(Duration.ofMillis(200));

            IllegalStateException error = assertThrows(IllegalStateException.class, builder::build);
            assertTrue(error.getMessage().contains("not ready"), error.getMessage());
        } finally {
            release.countDown();
        }
    }

    @Test
    void requireReadyWaitsForAProviderThatBecomesReady() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        String domain = UUID.randomUUID().toString();
        API.setProvider(domain, new SlowProvider(release));
        Client client = API.getClient(domain);
        assertEquals(ProviderState.NOT_READY, client.getProviderState());
        Thread releaser = releaseAfter(Duration.ofMillis(300), release::countDown);

        long start = System.nanoTime();
        OpenFeatureToggles.builder(client).requireReady(Duration.ofSeconds(10)).build();
        Duration waited = Duration.ofNanos(System.nanoTime() - start);

        assertEquals(ProviderState.READY, client.getProviderState());
        assertTrue(waited.compareTo(Duration.ofMillis(200)) >= 0, "returned before the provider was ready: " + waited);
        assertTrue(waited.compareTo(Duration.ofSeconds(5)) < 0, "kept waiting after ready: " + waited);
        releaser.join();
    }

    @Test
    void requireReadyKeepsWaitingThroughARecoverableError() throws InterruptedException {
        RecoveringProvider provider = new RecoveringProvider();
        String domain = UUID.randomUUID().toString();
        API.setProvider(domain, provider);
        Client client = API.getClient(domain);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (client.getProviderState() != ProviderState.ERROR && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(ProviderState.ERROR, client.getProviderState());
        Thread recoverer = releaseAfter(Duration.ofMillis(300), provider::recover);

        OpenFeatureToggles toggles = OpenFeatureToggles.builder(client).requireReady(Duration.ofSeconds(10)).build();

        assertEquals(ProviderState.READY, client.getProviderState());
        assertTrue(toggles.isEnabled("premium"));
        recoverer.join();
    }

    @Test
    void requireReadyFailsAtOnceWhenTheProviderFailedFatally() {
        String domain = UUID.randomUUID().toString();
        API.setProvider(domain, new FatalProvider());
        OpenFeatureToggles.Builder builder = OpenFeatureToggles.builder(API.getClient(domain))
                .requireReady(Duration.ofSeconds(10));

        long start = System.nanoTime();
        IllegalStateException error = assertThrows(IllegalStateException.class, builder::build);

        assertTrue(error.getMessage().contains("fatally"), error.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2)) < 0,
                "should not wait for the timeout");
    }

    @Test
    void requireReadyRejectsNonPositiveTimeouts() {
        OpenFeatureToggles.Builder builder = OpenFeatureToggles.builder(clientFor(new InMemoryProvider(Map.of())));

        assertThrows(IllegalArgumentException.class, () -> builder.requireReady(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> builder.requireReady(Duration.ofSeconds(-1)));
    }

    @Test
    void closeLeavesTheProviderRunning() {
        Client client = clientFor(new InMemoryProvider(Map.of("premium", flag(true))));
        OpenFeatureToggles toggles = OpenFeatureToggles.builder(client).build();

        toggles.close();
        toggles.withOverrides().close();

        assertEquals(ProviderState.READY, client.getProviderState());
        assertTrue(toggles.isEnabled("premium"));
    }

    @Test
    void cannotListFeatures() {
        OpenFeatureToggles toggles = OpenFeatureToggles.builder(clientFor(new InMemoryProvider(Map.of("premium", flag(true)))))
                .build();

        assertTrue(toggles.knownFeatures().isEmpty());
    }

    // ---- README example ----------------------------------------------------------

    public interface PricingService {
        String quote();
    }

    public static class LegacyPricing implements PricingService {
        @Override
        public String quote() {
            return "legacy";
        }
    }

    @Toggleable(value = "new-pricing", fallback = LegacyPricing.class)
    public static class NewPricing implements PricingService {
        @Override
        public String quote() {
            return "new";
        }
    }

    @Test
    void routesCallsByProviderState() {
        InMemoryProvider provider = new InMemoryProvider(Map.of("new-pricing", flag(true)));
        FeatureToggles toggles = OpenFeatureToggles.builder(clientFor(provider))
                .requireReady(Duration.ofSeconds(5))
                .build()
                .withOverrides();
        PricingService pricing = ToggledFeature.of(PricingService.class, new NewPricing(), new LegacyPricing(), toggles);

        assertEquals("new", pricing.quote());
        provider.updateFlag("new-pricing", flag(false));
        assertEquals("legacy", pricing.quote());
    }

    // ---- test providers ----------------------------------------------------------

    private static Thread releaseAfter(Duration delay, Runnable action) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            action.run();
        });
        thread.start();
        return thread;
    }

    /**
     * Fails to initialize with a recoverable error (state ERROR), then reports ready when
     * {@link #recover()} is called, as a provider does once its backend is reachable again.
     */
    private static final class RecoveringProvider extends EventProvider {
        @Override
        public Metadata getMetadata() {
            return () -> "recovering";
        }

        @Override
        public void initialize(EvaluationContext context) {
            throw new IllegalStateException("backend unreachable");
        }

        void recover() {
            emitProviderReady(ProviderEventDetails.builder().build());
        }

        @Override
        public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue, EvaluationContext ctx) {
            return ProviderEvaluation.<Boolean>builder().value(true).build();
        }

        @Override
        public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue, EvaluationContext ctx) {
            return ProviderEvaluation.<String>builder().value(defaultValue).build();
        }

        @Override
        public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue, EvaluationContext ctx) {
            return ProviderEvaluation.<Integer>builder().value(defaultValue).build();
        }

        @Override
        public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue, EvaluationContext ctx) {
            return ProviderEvaluation.<Double>builder().value(defaultValue).build();
        }

        @Override
        public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue, EvaluationContext ctx) {
            return ProviderEvaluation.<Value>builder().value(defaultValue).build();
        }
    }

    /**
     * Throws on every evaluation, like a provider whose backend is down.
     */
    private static class FailingProvider implements FeatureProvider {
        @Override
        public Metadata getMetadata() {
            return () -> "failing";
        }

        @Override
        public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue, EvaluationContext ctx) {
            throw new IllegalStateException("backend down");
        }

        @Override
        public ProviderEvaluation<String> getStringEvaluation(String key, String defaultValue, EvaluationContext ctx) {
            throw new IllegalStateException("backend down");
        }

        @Override
        public ProviderEvaluation<Integer> getIntegerEvaluation(String key, Integer defaultValue, EvaluationContext ctx) {
            throw new IllegalStateException("backend down");
        }

        @Override
        public ProviderEvaluation<Double> getDoubleEvaluation(String key, Double defaultValue, EvaluationContext ctx) {
            throw new IllegalStateException("backend down");
        }

        @Override
        public ProviderEvaluation<Value> getObjectEvaluation(String key, Value defaultValue, EvaluationContext ctx) {
            throw new IllegalStateException("backend down");
        }
    }

    /**
     * Answers {@code state} for every flag; {@code null} means the flag doesn't exist.
     */
    private static final class SwitchableProvider extends FailingProvider {
        volatile Boolean state;

        @Override
        public ProviderEvaluation<Boolean> getBooleanEvaluation(String key, Boolean defaultValue, EvaluationContext ctx) {
            if (state == null) {
                throw new FlagNotFoundError("no flag " + key);
            }
            return ProviderEvaluation.<Boolean>builder().value(state).build();
        }
    }

    /**
     * Fails to initialize with an error it can't recover from, so its state becomes FATAL.
     */
    private static final class FatalProvider extends FailingProvider {
        @Override
        public void initialize(EvaluationContext context) {
            throw new FatalError("bad credentials");
        }
    }

    /**
     * Stays NOT_READY until {@code release} counts down.
     */
    private static final class SlowProvider extends FailingProvider {
        private final CountDownLatch release;

        SlowProvider(CountDownLatch release) {
            this.release = release;
        }

        @Override
        public void initialize(EvaluationContext context) throws Exception {
            release.await();
        }
    }
}
