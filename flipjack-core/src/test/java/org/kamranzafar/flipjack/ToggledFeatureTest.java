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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToggledFeatureTest {

    // ---- class-level: decorator --------------------------------------------

    public interface Feature {
        String execute();
    }

    public static class BasicFeature implements Feature {
        @Override
        public String execute() {
            return "Basic Feature";
        }
    }

    @Toggleable("premium")
    public static class PremiumFeatureDecorator implements Feature {
        private final Feature delegate;

        public PremiumFeatureDecorator(Feature delegate) {
            this.delegate = delegate;
        }

        @Override
        public String execute() {
            return delegate.execute() + " + Premium Feature";
        }
    }

    @Test
    void classLevelRoutesBetweenDecoratorAndFallback() {
        InMemoryFeatureToggles toggles = new InMemoryFeatureToggles();
        Feature basic = new BasicFeature();
        Feature feature = ToggledFeature.of(Feature.class, new PremiumFeatureDecorator(basic), basic, toggles);

        assertEquals("Basic Feature", feature.execute());
        toggles.enable("premium");
        assertEquals("Basic Feature + Premium Feature", feature.execute());
    }

    // ---- method-level: fallbackMethod ----------------------------------------

    public interface Pricing {
        String quote();

        String discount();
    }

    public static class SmartPricing implements Pricing {
        @Override
        public String quote() {
            return "quote";
        }

        @Override
        @Toggleable(value = "smart-discount", fallbackMethod = "flatDiscount")
        public String discount() {
            return "smart discount";
        }

        private String flatDiscount() {
            return "flat discount";
        }
    }

    @Test
    void methodLevelFallbackMethod() {
        InMemoryFeatureToggles toggles = new InMemoryFeatureToggles();
        Pricing pricing = ToggledFeature.builder(Pricing.class)
                .enabled(new SmartPricing()).toggles(toggles).build();

        assertEquals("quote", pricing.quote()); // untoggled method passes straight through
        assertEquals("flat discount", pricing.discount());
        toggles.enable("smart-discount");
        assertEquals("smart discount", pricing.discount());
    }

    // ---- method-level: fallback object ---------------------------------------

    public static class LegacyPricing implements Pricing {
        @Override
        public String quote() {
            return "legacy quote";
        }

        @Override
        public String discount() {
            return "legacy discount";
        }
    }

    public static class NewPricing implements Pricing {
        @Override
        @Toggleable(value = "new-quote", fallback = LegacyPricing.class)
        public String quote() {
            return "new quote";
        }

        @Override
        public String discount() {
            return "new discount";
        }
    }

    @Test
    void methodLevelFallbackObject() {
        InMemoryFeatureToggles toggles = new InMemoryFeatureToggles();
        Pricing pricing = ToggledFeature.builder(Pricing.class)
                .enabled(new NewPricing())
                .fallbackResolver(FallbackResolver.of(new LegacyPricing()))
                .toggles(toggles)
                .build();

        assertEquals("legacy quote", pricing.quote());
        assertEquals("new discount", pricing.discount());
        toggles.enable("new-quote");
        assertEquals("new quote", pricing.quote());
        assertEquals(Set.of(LegacyPricing.class), ((Toggled) pricing).fallbackTypes());
    }

    // ---- method-level: annotation on the interface ---------------------------

    public interface Reports {
        @Toggleable(value = "pdf-reports", whenDisabled = WhenDisabled.NO_OP)
        Optional<String> pdf();

        String csv();
    }

    public static class ReportService implements Reports {
        @Override
        public Optional<String> pdf() {
            return Optional.of("pdf");
        }

        @Override
        public String csv() {
            return "csv";
        }
    }

    @Test
    void annotationOnInterfaceMethod() {
        InMemoryFeatureToggles toggles = new InMemoryFeatureToggles();
        Reports reports = ToggledFeature.builder(Reports.class)
                .enabled(new ReportService()).toggles(toggles).build();

        assertEquals(Optional.empty(), reports.pdf());
        assertEquals("csv", reports.csv());
        toggles.enable("pdf-reports");
        assertEquals(Optional.of("pdf"), reports.pdf());
    }

    // ---- class and method together ---------------------------------------------

    public interface Checkout {
        String pay();

        String ship();
    }

    public static class CheckoutV1 implements Checkout {
        @Override
        public String pay() {
            return "v1 pay";
        }

        @Override
        public String ship() {
            return "v1 ship";
        }
    }

    @Toggleable("checkout-v2")
    public static class CheckoutV2 implements Checkout {
        @Override
        public String pay() {
            return "v2 pay";
        }

        @Override
        @Toggleable(value = "express-shipping", fallbackMethod = "standardShipping")
        public String ship() {
            return "v2 express";
        }

        String standardShipping() {
            return "v2 standard";
        }
    }

    @Test
    void classCheckRunsBeforeMethodCheck() {
        InMemoryFeatureToggles toggles = new InMemoryFeatureToggles();
        Checkout checkout = ToggledFeature.builder(Checkout.class)
                .enabled(new CheckoutV2()).fallback(new CheckoutV1()).toggles(toggles).build();

        toggles.enable("express-shipping"); // irrelevant while the class feature is off
        assertEquals("v1 pay", checkout.pay());
        assertEquals("v1 ship", checkout.ship());

        toggles.enable("checkout-v2").disable("express-shipping");
        assertEquals("v2 pay", checkout.pay());
        assertEquals("v2 standard", checkout.ship());

        toggles.enable("express-shipping");
        assertEquals("v2 express", checkout.ship());

        assertEquals(Set.of("checkout-v2", "express-shipping"), ((Toggled) checkout).toggledFeatures());
    }

    // ---- disabled behaviour, exceptions, file source ---------------------------

    public interface Notifier {
        void send(String message);

        boolean isAvailable();
    }

    @Test
    void throwsOrNoOpsWithoutFallback() {
        InMemoryFeatureToggles toggles = new InMemoryFeatureToggles();
        Notifier real = new Notifier() {
            public void send(String message) {
            }

            public boolean isAvailable() {
                return true;
            }
        };

        Notifier throwing = ToggledFeature.builder(Notifier.class)
                .feature("sms").enabled(real).toggles(toggles).build();
        assertThrows(FeatureDisabledException.class, () -> throwing.send("hi"));

        Notifier noOp = ToggledFeature.builder(Notifier.class)
                .feature("sms").enabled(real).whenDisabled(WhenDisabled.NO_OP).toggles(toggles).build();
        noOp.send("hi");
        assertFalse(noOp.isAvailable());
        toggles.enable("sms");
        assertTrue(noOp.isAvailable());
    }

    @Test
    void propagatesFeatureExceptionsUnwrapped() {
        Feature failing = () -> {
            throw new IllegalStateException("boom");
        };
        Feature feature = ToggledFeature.builder(Feature.class)
                .feature("x").enabled(failing).toggles(name -> true).build();
        assertThrows(IllegalStateException.class, feature::execute);
    }

    @Test
    void reloadsPropertiesFileWhenItChanges() throws IOException {
        Path file = Files.createTempFile("features", ".properties");
        Files.writeString(file, "premium=false\n");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(60)));

        FeatureToggles toggles = new PropertiesFileFeatureToggles(file, "", false, Duration.ZERO);
        Feature basic = new BasicFeature();
        Feature feature = ToggledFeature.of(Feature.class, new PremiumFeatureDecorator(basic), basic, toggles);
        assertEquals("Basic Feature", feature.execute());

        Files.writeString(file, "premium=true\n");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
        assertEquals("Basic Feature + Premium Feature", feature.execute());
    }

    // ---- validation ------------------------------------------------------------

    public static class StrayAnnotation implements Feature {
        @Override
        public String execute() {
            return "";
        }

        @Toggleable("x")
        void helper() {
        }
    }

    public static class BothFallbacks implements Feature {
        @Override
        @Toggleable(value = "x", fallback = BasicFeature.class, fallbackMethod = "other")
        public String execute() {
            return "";
        }

        String other() {
            return "";
        }
    }

    public static class MissingFallbackMethod implements Feature {
        @Override
        @Toggleable(value = "x", fallbackMethod = "nope")
        public String execute() {
            return "";
        }
    }

    public static class WrongReturnType implements Feature {
        @Override
        @Toggleable(value = "x", fallbackMethod = "count")
        public String execute() {
            return "";
        }

        int count() {
            return 0;
        }
    }

    @Toggleable(value = "x", fallbackMethod = "other")
    public static class ClassLevelFallbackMethod implements Feature {
        @Override
        public String execute() {
            return "";
        }
    }

    public static class NeedsResolver implements Feature {
        @Override
        @Toggleable(value = "x", fallback = BasicFeature.class)
        public String execute() {
            return "";
        }
    }

    @Toggleable(value = "x", fallback = BasicFeature.class, whenDisabled = WhenDisabled.NO_OP)
    public static class ClassFallbackAndNoOp implements Feature {
        @Override
        public String execute() {
            return "";
        }
    }

    public static class FallbackMethodAndNoOp implements Feature {
        @Override
        @Toggleable(value = "x", fallbackMethod = "other", whenDisabled = WhenDisabled.NO_OP)
        public String execute() {
            return "";
        }

        String other() {
            return "";
        }
    }

    public static class MethodFallbackAndNoOp implements Feature {
        @Override
        @Toggleable(value = "x", fallback = BasicFeature.class, whenDisabled = WhenDisabled.NO_OP)
        public String execute() {
            return "";
        }
    }

    @Toggleable(value = "x", fallback = BasicFeature.class)
    public static class AnnotatedClassFallback implements Feature {
        @Override
        public String execute() {
            return "";
        }
    }

    @Toggleable(value = "x", whenDisabled = WhenDisabled.NO_OP)
    public static class AnnotatedNoOp implements Feature {
        @Override
        public String execute() {
            return "new";
        }
    }

    @Test
    void rejectsWhenDisabledThatAFallbackWouldOverride() {
        FeatureToggles off = name -> false;
        assertThrows(IllegalArgumentException.class, () -> build(new ClassFallbackAndNoOp(), off));
        assertThrows(IllegalArgumentException.class, () -> build(new FallbackMethodAndNoOp(), off));
        assertThrows(IllegalArgumentException.class, () -> build(new MethodFallbackAndNoOp(), off));

        // builder whenDisabled alongside a builder or annotation fallback
        assertThrows(IllegalStateException.class, () -> ToggledFeature.builder(Feature.class)
                .feature("x").enabled(new BasicFeature()).fallback(new BasicFeature())
                .whenDisabled(WhenDisabled.NO_OP).toggles(off).build());
        assertThrows(IllegalStateException.class, () -> ToggledFeature.builder(Feature.class)
                .enabled(new AnnotatedClassFallback()).fallbackResolver(FallbackResolver.of(new BasicFeature()))
                .whenDisabled(WhenDisabled.THROW).toggles(off).build());
    }

    @Test
    void builderFallbackStillOverridesAnAnnotatedNoOp() {
        Feature feature = ToggledFeature.of(Feature.class, new AnnotatedNoOp(), new BasicFeature(), name -> false);
        assertEquals("Basic Feature", feature.execute());
    }

    @Test
    void rejectsMisconfigurationAtBuildTime() {
        FeatureToggles on = name -> true;
        assertThrows(IllegalArgumentException.class, () -> ToggledFeature.builder(BasicFeature.class));
        assertThrows(IllegalStateException.class, () -> build(new BasicFeature(), on));
        assertThrows(IllegalArgumentException.class, () -> build(new StrayAnnotation(), on));
        assertThrows(IllegalArgumentException.class, () -> build(new BothFallbacks(), on));
        assertThrows(IllegalArgumentException.class, () -> build(new MissingFallbackMethod(), on));
        assertThrows(IllegalArgumentException.class, () -> build(new WrongReturnType(), on));
        assertThrows(IllegalArgumentException.class, () -> build(new ClassLevelFallbackMethod(), on));
        assertThrows(IllegalStateException.class, () -> build(new NeedsResolver(), on));
    }

    @Test
    void detectsToggleableTypes() {
        assertTrue(ToggledFeature.isToggleable(PremiumFeatureDecorator.class));
        assertTrue(ToggledFeature.isToggleable(SmartPricing.class));
        assertTrue(ToggledFeature.isToggleable(ReportService.class)); // via interface method
        assertFalse(ToggledFeature.isToggleable(BasicFeature.class));
    }

    private static Feature build(Feature implementation, FeatureToggles toggles) {
        return ToggledFeature.builder(Feature.class).enabled(implementation).toggles(toggles).build();
    }
}
