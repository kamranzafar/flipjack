<div align="center">
<pre>
  __ _ _       _            _                 _   ON
 / _| (_)_ __ (_) __ _  ___| | __            (_)    
| |_| | | '_ \| |/ _` |/ __| |/ /            /      
|  _| | | |_) | | (_| | (__|   &lt;       _____/__     
|_| |_|_| .__// |\__,_|\___|_|\_\     |   (o)  |    
        |_| |__/                      |________|    
                                       OFF          
&nbsp;
feature toggles for Java, without the if-else
</pre>

[![Build](https://github.com/kamranzafar/flipjack/actions/workflows/build.yml/badge.svg)](https://github.com/kamranzafar/flipjack/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/org.kamranzafar.flipjack/flipjack-core)](https://central.sonatype.com/artifact/org.kamranzafar.flipjack/flipjack-core)
[![Java](https://img.shields.io/badge/Java-17%2B-orange?logo=openjdk)](https://adoptium.net/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

</div>

**Flipjack:** Lightweight runtime feature toggles for Java at class and method level, _without the if-else_. Your feature code contains
no toggle checks: a proxy checks the toggles on every call and routes to the new code,
a fallback, or neither.

| Module | Depends on | Use it for |
|---|---|---|
| `flipjack-core` | nothing (Java 17+) | Plain Java, or any DI container |
| `flipjack-spring-boot-starter` | core + Spring Boot | Auto-wiring of `@Toggleable` beans and `flipjack.*` config |

Maven:

```xml
<dependency>
    <groupId>org.kamranzafar.flipjack</groupId>
    <artifactId>flipjack-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

Gradle (Kotlin DSL, `build.gradle.kts`):

```kotlin
implementation("org.kamranzafar.flipjack:flipjack-core:1.0.0")
```

Gradle (Groovy DSL, `build.gradle`):

```groovy
implementation 'org.kamranzafar.flipjack:flipjack-core:1.0.0'
```

On Spring Boot, use the starter instead; see [Spring Boot](#spring-boot).

## Usage

The examples keep feature names in one constants class (see
[Catching misspelt and stale feature names](#catching-misspelt-and-stale-feature-names)):

```java
public final class Features {
    public static final String NEW_PRICING      = "new-pricing";
    public static final String PREMIUM          = "premium";
    public static final String PDF_EXPORT       = "pdf-export";
    public static final String FAST_CHECKOUT    = "fast-checkout";
    public static final String NEW_REFUNDS      = "new-refunds";
    public static final String ORDER_EMAILS     = "order-emails";
    public static final String CHECKOUT_V2      = "checkout-v2";
    public static final String EXPRESS_SHIPPING = "express-shipping";
}
```

The annotations are the same with or without Spring. The Spring Boot snippets below are
complete as shown: the starter finds the annotated beans and wires them. For plain Java,
see [Wiring without Spring](#wiring-without-spring).

### Class level: replace an implementation

Use this when a new implementation replaces an old one, such as a new pricing engine.

```java
public interface PricingService {
    Quote quote(Cart cart);
    Money discount(Cart cart);
}

@Service
public class LegacyPricing implements PricingService { ... }

@Service
@Toggleable(value = Features.NEW_PRICING, fallback = LegacyPricing.class)
public class NewPricing implements PricingService { ... }
```

Callers inject the interface and never check the toggle:

```java
@RestController
class QuoteController {
    private final PricingService pricing;

    QuoteController(PricingService pricing) {
        this.pricing = pricing;
    }

    @PostMapping("/quotes")
    Quote quote(@RequestBody Cart cart) {
        return pricing.quote(cart);
    }
}
```

| `new-pricing` | `pricing.quote(cart)` and `pricing.discount(cart)` run on |
|---|---|
| on | `NewPricing` |
| off | `LegacyPricing` |

### Class level: add behaviour with a decorator

Use this when the new feature wraps existing behaviour rather than replacing it.

```java
public interface Greeting {
    String greet(String name);
}

@Service
public class BasicGreeting implements Greeting {
    public String greet(String name) {
        return "Hello " + name;
    }
}

@Service
@Toggleable(value = Features.PREMIUM, fallback = BasicGreeting.class)
public class PremiumGreeting implements Greeting {
    private final Greeting basic;

    public PremiumGreeting(BasicGreeting basic) {   // inject the concrete class it decorates
        this.basic = basic;
    }

    public String greet(String name) {
        return basic.greet(name) + ", welcome back!";
    }
}
```

| `premium` | `greeting.greet("Sam")` returns |
|---|---|
| on | `Hello Sam, welcome back!` |
| off | `Hello Sam` |

The decorator injects `BasicGreeting` by class, not `Greeting`: injecting the interface would
give it the toggled proxy, which would route back to itself.

### Class level: no fallback

When there is nothing to fall back to, choose what a disabled call does:

```java
public interface ReportExporter {
    Optional<byte[]> exportPdf(Report report);
}

@Service
@Toggleable(value = Features.PDF_EXPORT, whenDisabled = WhenDisabled.NO_OP)
public class PdfExporter implements ReportExporter { ... }
```

| `whenDisabled` | A call while `pdf-export` is off |
|---|---|
| `THROW` (default) | throws `FeatureDisabledException` |
| `NO_OP` | does nothing and returns `null`, `Optional.empty()` or a primitive default (here `Optional.empty()`) |

### Method level

Toggle individual methods; each has its own feature and its own behaviour when off.
Methods without the annotation always run.

```java
public interface Orders {
    Receipt checkout(Cart cart);
    Refund refund(OrderId id);
    void sendConfirmation(OrderId id);
    Order find(OrderId id);
}

@Service
public class LegacyOrders implements Orders { ... }

@Service
public class OrderService implements Orders {

    // fallbackMethod: another method on this object, with the same parameters (may be private)
    @Toggleable(value = Features.FAST_CHECKOUT, fallbackMethod = "standardCheckout")
    public Receipt checkout(Cart cart) { ... }

    private Receipt standardCheckout(Cart cart) { ... }

    // fallback: the same method on another bean
    @Toggleable(value = Features.NEW_REFUNDS, fallback = LegacyOrders.class)
    public Refund refund(OrderId id) { ... }

    // whenDisabled: skip the call entirely
    @Toggleable(value = Features.ORDER_EMAILS, whenDisabled = WhenDisabled.NO_OP)
    public void sendConfirmation(OrderId id) { ... }

    // not annotated: always runs
    public Order find(OrderId id) { ... }
}
```

| Call | Feature on | Feature off |
|---|---|---|
| `orders.checkout(cart)` | `OrderService.checkout` | `OrderService.standardCheckout` |
| `orders.refund(id)` | `OrderService.refund` | `LegacyOrders.refund` |
| `orders.sendConfirmation(id)` | `OrderService.sendConfirmation` | nothing |
| `orders.find(id)` | `OrderService.find` | (not toggled) |

The annotation can also go on the interface method, which toggles that method for every
implementation; the implementation's annotation wins if both exist:

```java
public interface Reports {
    @Toggleable(value = Features.PDF_EXPORT, whenDisabled = WhenDisabled.NO_OP)
    Optional<byte[]> pdf(ReportId id);

    byte[] csv(ReportId id);
}
```

Method-level toggles apply to calls from outside the component. A call from inside it,
such as `this.checkout(cart)`, goes straight to the method. To toggle a step the component
calls itself, see [Inside a method](#inside-a-method-toggle-one-step).

### Both levels together

A class toggle gates the whole component and method toggles refine it. The class toggle
is checked first:

```java
@Service
public class CheckoutV1 implements Checkout { ... }

@Service
@Toggleable(value = Features.CHECKOUT_V2, fallback = CheckoutV1.class)
public class CheckoutV2 implements Checkout {

    public Payment pay(Cart cart) { ... }

    @Toggleable(value = Features.EXPRESS_SHIPPING, fallbackMethod = "standardShipping")
    public Shipment ship(Order order) { ... }

    private Shipment standardShipping(Order order) { ... }
}
```

| `checkout-v2` | `express-shipping` | `pay` runs | `ship` runs |
|---|---|---|---|
| off | any | `CheckoutV1.pay` | `CheckoutV1.ship` |
| on | off | `CheckoutV2.pay` | `CheckoutV2.standardShipping` |
| on | on | `CheckoutV2.pay` | `CheckoutV2.ship` |

### Inside a method: toggle one step

When only one step of a larger method changes, such as a tax calculation, hold that step
in a field built with `ToggledFeature.either`. Both variants stay private methods next to
the code that uses them, and the caller has no condition:

```java
public interface TaxRule {
    Money tax(Cart cart);
}

@Service
public class CheckoutService {
    private final TaxRule tax;

    public CheckoutService(FeatureToggles toggles) {
        this.tax = ToggledFeature.either(TaxRule.class, Features.REGIONAL_TAX,
                this::regionalTax, this::flatTax, toggles);
    }

    public Money total(Cart cart) {
        return cart.subtotal().plus(tax.tax(cart));
    }

    private Money regionalTax(Cart cart) { ... }

    private Money flatTax(Cart cart) { ... }
}
```

| `regional-tax` | `tax.tax(cart)` runs |
|---|---|
| on | `regionalTax` |
| off | `flatTax` |

Unlike a method-level toggle, this works for calls from inside the component, because they
go through the field's proxy. The consistency check finds the feature through the field (in
plain Java, pass `checkoutService` to `FeatureConsistency.check`), as does `/actuator/flipjack`,
so it needs no extra declaration. `CheckoutService` itself needs no
`@Toggleable` and no interface.

### Wiring without Spring

The same annotated classes, wired by hand. Each proxy reads its feature names and
fallbacks from the annotations:

```java
FeatureToggles toggles = PollingFeatureToggles.builder(config::loadFeatureToggles).build();

// Class level: the fallback instance is passed directly
PricingService pricing = ToggledFeature.of(PricingService.class, new NewPricing(), new LegacyPricing(), toggles);

BasicGreeting basic = new BasicGreeting();
Greeting greeting = ToggledFeature.of(Greeting.class, new PremiumGreeting(basic), basic, toggles);

ReportExporter exporter = ToggledFeature.of(ReportExporter.class, new PdfExporter(), null, toggles);

// Method level: fallback classes named in annotations are resolved from instances you supply
Orders orders = ToggledFeature.builder(Orders.class)
        .enabled(new OrderService())
        .fallbackResolver(FallbackResolver.of(new LegacyOrders()))
        .toggles(toggles)
        .build();

// Both levels
Checkout checkout = ToggledFeature.of(Checkout.class, new CheckoutV2(), new CheckoutV1(), toggles);
```

Without annotations, the builder sets a class-level toggle directly:

```java
ReportExporter exporter = ToggledFeature.builder(ReportExporter.class)
        .feature(Features.PDF_EXPORT)
        .enabled(new PlainPdfExporter())
        .whenDisabled(WhenDisabled.NO_OP)
        .toggles(toggles)
        .build();
```

### Reference: what handles a call when its feature is off

| Level | 1st | 2nd | 3rd |
|---|---|---|---|
| Class | builder `fallback(instance)` | annotation `fallback` class | `whenDisabled` |
| Method | `fallbackMethod` | annotation `fallback` class | `whenDisabled` |

A class-level `fallback` must implement every interface the component exposes; a
method-level `fallback` must implement that method's interface. `fallbackMethod` is
method-level only and must have the same parameter types and a compatible return type.
`whenDisabled` applies only when there is no fallback, so `whenDisabled = NO_OP` next to a
`fallback` or `fallbackMethod`, or the builder's `whenDisabled(...)` next to a class-level
fallback, is rejected. Mistakes in any of these fail when the proxy is built, at startup.

## Toggle sources and decorators

Sources: `PollingFeatureToggles` (polls a loader your application provides; see below),
`InMemoryFeatureToggles`, `PropertiesFileFeatureToggles` (reloads on change), or implement
the one-method `FeatureToggles` interface.

Cross-cutting behaviour is added by decorating any source. The default methods compose
them fluently, innermost first:

```java
OverridableFeatureToggles toggles = new DatabaseFeatureToggles(dataSource)
        .cached(Duration.ofSeconds(30))   // CachingFeatureToggles
        .failSafe(false)                  // FailSafeFeatureToggles
        .withOverrides();                 // OverridableFeatureToggles

FeatureToggles observed = toggles.observed(metrics::record); // ObservedFeatureToggles

toggles.override("new-quote", false);     // kill switch during an incident
```

| Decorator | Does | Recommended position |
|---|---|---|
| `CachingFeatureToggles` | Caches each state for a TTL; failures aren't cached | Right around a slow or remote source |
| `FailSafeFeatureToggles` | Never throws: last known state, else a default; logs or reports the error | Outside the cache |
| `OverridableFeatureToggles` | Runtime overrides win; clear to hand back to the source | Outside fail-safe, so kill switches always work |
| `ObservedFeatureToggles` | Reports every evaluation to a `FeatureToggleListener` | Outermost, to see the final decision |

## Loading toggles from your config store

Flipjack doesn't talk to SSM, Vault or anything else. Your application loads the toggles
however it already reads config, and hands them over through a `FeatureToggleLoader`,
usually a method reference:

```java
public class ToggleConfig {
    /** All toggles for this service, keyed by feature name: Boolean or "true"/"false". */
    public Map<String, Object> loadFeatureToggles() throws IOException {
        ... // read from SSM, Vault, a config service, a database
    }
}

PollingFeatureToggles toggles = PollingFeatureToggles.builder(toggleConfig::loadFeatureToggles)
        .interval(Duration.ofSeconds(30))
        .onChange((feature, before, after) -> log.info("{}: {} -> {}", feature, before, after))
        .build();
```

`PollingFeatureToggles` calls the loader once in `build()`, then on a background schedule,
and answers `isEnabled` from an in-memory snapshot:

- **One load per interval per instance**, however many features or calls; toggle checks
  never touch the network. Load everything in one bulk read (for example SSM
  `GetParametersByPath` or one Vault secret) rather than one read per feature.
- **Ready before traffic:** if the first load fails the service starts with defaults and
  keeps retrying; call `requireInitialLoad()` to fail startup instead.
- **Survives outages and bad edits:** if the loader throws (checked exceptions included) or
  any value isn't `true`/`false`, the last good snapshot is kept and the error is reported.
  No `cached()` or `failSafe()` needed.
- **Operable:** `refresh()` forces a load, `snapshot()` shows current state, `lastLoaded()`
  supports health checks, and `close()` stops the poller (closing an outer decorator does too).

## Per-user and per-tenant toggles

A toggle can be on for some callers and off for others. Each call carries a
`FeatureContext` (an optional subject such as a user or tenant id, plus string attributes),
and a source that supports targeting answers per context.

Implement the two-argument `isEnabled` in your source:

```java
public class UserTargetedToggles implements FeatureToggles {
    private final Map<String, Set<String>> usersByFeature;  // feature -> users it is on for

    public UserTargetedToggles(Map<String, Set<String>> usersByFeature) {
        this.usersByFeature = usersByFeature;
    }

    @Override
    public boolean isEnabled(String feature) {
        return false;                                        // no user known: off
    }

    @Override
    public boolean isEnabled(String feature, FeatureContext context) {
        return context.subject()
                .map(user -> usersByFeature.getOrDefault(feature, Set.of()).contains(user))
                .orElse(false);
    }
}
```

Then tell the proxy where the current context comes from, usually something your web
filter sets for each request:

```java
ThreadLocal<FeatureContext> currentUser = ThreadLocal.withInitial(() -> FeatureContext.EMPTY);

PricingService pricing = ToggledFeature.builder(PricingService.class)
        .enabled(new NewPricing())
        .fallback(new LegacyPricing())
        .toggles(new UserTargetedToggles(Map.of(Features.NEW_PRICING, Set.of("alice"))))
        .context(currentUser::get)
        .build();

currentUser.set(FeatureContext.of("alice").with("tenant", "acme"));
```

| Current context | `pricing.quote(cart)` runs on |
|---|---|
| `alice` | `NewPricing` |
| `bob` | `LegacyPricing` |
| none (`FeatureContext.EMPTY`) | `LegacyPricing` |

The context is read once per call, so the class and method toggles decide for the same
caller. Without `.context(...)`, or when it returns `null`, calls use `FeatureContext.EMPTY`.
The built-in sources (`PollingFeatureToggles`, `InMemoryFeatureToggles`,
`PropertiesFileFeatureToggles`) ignore the context, so targeting needs a source like the
one above or an adapter to a flag service that targets users itself.

The decorators pass the context on, with these rules so one caller's answer never
leaks to another:

| Decorator | With a non-empty context |
|---|---|
| `CachingFeatureToggles` | Not cached: every lookup goes to the source |
| `FailSafeFeatureToggles` | On failure returns its default, never another caller's last state |
| `OverridableFeatureToggles` | Overrides still win for every context, so kill switches stay global |
| `ObservedFeatureToggles` | Passes the context to `FeatureToggleListener.onEvaluated(feature, context, enabled)` |

A custom decorator must override the two-argument `isEnabled` too; the default ignores the
context and calls the one-argument method, which drops it at that layer.

## Catching misspelt and stale feature names

Feature names are strings, so a typo (`"new-pricng"` in code, `new-pricing` in the store)
would silently run with the default state. Two lightweight safeguards:

**Keep names as constants**, one class per service, so there is a single catalogue and the
IDE can find and rename them:

```java
public final class Features {
    public static final String NEW_PRICING = "new-pricing";
    public static final String FAST_CHECKOUT = "fast-checkout";
}

@Toggleable(value = Features.NEW_PRICING, fallback = LegacyPricing.class)
public class NewPricing implements Pricing { ... }
```

**Run the consistency check at startup.** It compares the features your components use
(discovered automatically, including `either` steps held in their fields) with the features
the store holds, and reports:

- *missing from the store*: used by code but not configured, usually a typo; logged as a warning
- *unused in the store*: configured but used by nothing, usually a stale toggle to delete; logged as info

```java
FeatureConsistency.check(toggles, List.of(pricing, orders))
        .ifPresent(FeatureConsistencyReport::logIfInconsistent);
```

Prefer a toggled component or an [`either` step](#inside-a-method-toggle-one-step) to calling
`toggles.isEnabled(...)` directly: it keeps the condition out of your code and lets the check
find the feature. A feature checked directly can't be discovered; if you have one (say, in
code you can't restructure yet), pass it as a third argument,
`check(toggles, components, Set.of("audit-log"))`, or it shows as unused.

The check is skipped when the source can't list its features, or when a polling source
hasn't loaded yet, so a store outage at startup doesn't produce a wall of false warnings. Sources report what they hold through
`FeatureToggles.knownFeatures()`, which all built-in sources and decorators implement; a
custom source can override it to take part.

## Spring Boot

Add the starter, which brings in `flipjack-core`. It is built and tested against Spring Boot 3.5.

Maven:

```xml
<dependency>
    <groupId>org.kamranzafar.flipjack</groupId>
    <artifactId>flipjack-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

Gradle (Kotlin DSL, `build.gradle.kts`):

```kotlin
implementation("org.kamranzafar.flipjack:flipjack-spring-boot-starter:1.0.0")
```

Gradle (Groovy DSL, `build.gradle`):

```groovy
implementation 'org.kamranzafar.flipjack:flipjack-spring-boot-starter:1.0.0'
```

Then annotate components; fallback classes are resolved as beans.

```yaml
flipjack:
  default-enabled: false
  toggles:
    premium: true
    new-quote: false
    dynamic-discount: true
```

To load toggles from your own store, define a `FeatureToggles` bean that points at one of
your beans' methods. The configuration-backed default steps aside, and Spring closes the
chain (stopping the poller) at shutdown:

```java
@Bean
FeatureToggles featureToggles(ToggleConfig toggleConfig) {
    return PollingFeatureToggles.builder(toggleConfig::loadFeatureToggles)
            .interval(Duration.ofSeconds(30))
            .requireInitialLoad()
            .build()
            .withOverrides();
}
```

Alternatively, Spring Cloud AWS or Spring Cloud Vault can import the values into the Spring
environment so they bind to `flipjack.toggles.*` with no loader code; runtime refresh and
key-naming rules depend on the versions you use, so check their docs.

For [per-user toggles](#per-user-and-per-tenant-toggles), define a `FeatureContextProvider`
bean; every toggleable bean reads it once per call. Return `FeatureContext.EMPTY` when there
is no user, since it is also called outside requests. Without the bean, calls use the empty
context, and two provider beans fail startup:

```java
@Bean
FeatureContextProvider featureContext() {
    return () -> {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? FeatureContext.EMPTY : FeatureContext.of(auth.getName());
    };
}
```

The consistency check runs automatically once all beans are created:

```yaml
flipjack:
  consistency-check: warn        # off | warn (default) | fail: fail startup on features missing from the store
```

Features you check with `toggles.isEnabled(...)` directly can't be discovered; list them under
`flipjack.inline-features` so they aren't reported as unused, or better, move them into an
`either` step.

Runtime changes with the default source: add `spring-cloud-starter` and Actuator, expose
`refresh`, change the config at its source and `POST /actuator/refresh`.
`GET /actuator/flipjack` lists every configured or annotated feature with its effective state.
`GET /actuator/flipjack/{name}` returns one feature's state. Add `?subject=alice` to evaluate
it for a specific subject; the response includes that subject. Without the parameter, the
endpoint keeps using the default toggle evaluation as before.

## Kotlin

Flipjack works from Kotlin as it does from Java, with or without Spring. Feature names used
in annotations must be compile-time constants, so keep them as `const val`:

```kotlin
object Features {
    const val NEW_PRICING = "new-pricing"
    const val FAST_CHECKOUT = "fast-checkout"
    const val ORDER_EMAILS = "order-emails"
    const val REGIONAL_TAX = "regional-tax"
}
```

Class level, method level, and `whenDisabled`; annotation fallbacks take a `KClass`:

```kotlin
interface PricingService {
    fun quote(cart: Cart): Money
}

@Service
class LegacyPricing : PricingService { ... }

@Service
@Toggleable(value = Features.NEW_PRICING, fallback = LegacyPricing::class)
class NewPricing : PricingService { ... }

@Service
class OrderService : Orders {

    @Toggleable(value = Features.FAST_CHECKOUT, fallbackMethod = "standardCheckout")
    override fun checkout(cart: Cart): Receipt { ... }

    private fun standardCheckout(cart: Cart): Receipt { ... }

    @Toggleable(value = Features.ORDER_EMAILS, whenDisabled = WhenDisabled.NO_OP)
    override fun sendConfirmation(id: OrderId) { ... }
}
```

A step inside a method, with function references wrapped in the `fun interface`:

```kotlin
fun interface TaxRule {
    fun tax(cart: Cart): Money
}

@Service
class CheckoutService(toggles: FeatureToggles) {
    private val tax = ToggledFeature.either(TaxRule::class.java, Features.REGIONAL_TAX,
        TaxRule(::regionalTax), TaxRule(::flatTax), toggles)

    fun total(cart: Cart) = cart.subtotal + tax.tax(cart)

    private fun regionalTax(cart: Cart): Money { ... }

    private fun flatTax(cart: Cart): Money { ... }
}
```

Without Spring, wire it with `::class.java`:

```kotlin
val toggles = PollingFeatureToggles.builder(config::loadFeatureToggles).build()
val pricing = ToggledFeature.of(PricingService::class.java, NewPricing(), LegacyPricing(), toggles)
```

Per-user toggles in Spring, and a custom source, as lambdas:

```kotlin
@Configuration(proxyBeanMethods = false)
class FlipjackConfig {
    @Bean
    fun featureContext() = FeatureContextProvider {
        SecurityContextHolder.getContext().authentication
            ?.let { FeatureContext.of(it.name) }
            ?: FeatureContext.EMPTY
    }
}

val fromEnvironment = FeatureToggles { feature -> System.getenv("FEATURE_${feature.uppercase()}") == "true" }
```

Things to know:

- Kotlin classes are final by default. That's fine for toggled beans, since Flipjack proxies
  interfaces, not classes, so no `open` or `kotlin-spring` plugin is needed for them. A
  `@Configuration` class still needs either `proxyBeanMethods = false` or the `kotlin-spring`
  plugin, as with any Spring Kotlin code.
- Make a `fallbackMethod` `private`, not `internal`. Kotlin compiles `internal` functions under
  a changed name, so the method isn't found and the proxy fails to build.
- `suspend` functions on toggled interfaces haven't been tested.

## Design

| Pattern | Where |
|---|---|
| Proxy | `FeatureRoutingHandler`: one dynamic proxy per component, the only place toggles are checked |
| Strategy | `DisabledAction`: delegate to fallback object, call fallback method, throw, no-op |
| Chain of responsibility | Each method's route is an ordered list of `FeatureCheck`s (class, then method); the first check whose feature is off handles the call |
| Decorator | `FeatureToggles` layers: caching, fail-safe, overrides, observation; closing the outer layer closes the chain |
| Builder | `ToggledFeature.Builder`: one way to assemble a proxy, used by plain Java and Spring alike |

Routes are resolved and validated once, when the proxy is built, so each call is a map
lookup plus one `isEnabled` per check, and misconfiguration fails at startup: unknown or
incompatible `fallbackMethod`, `fallback` and `fallbackMethod` on the same method,
a `whenDisabled` that a fallback would override, `fallbackMethod` at class level, an annotated method that isn't on any proxied interface,
or a fallback class with no instance available.

## Rules and limitations

- Toggled types must be interfaces (JDK dynamic proxies); inject the interface.
- Calls from inside a component to its own methods (`this.discount()`) bypass the proxy,
  so method-level toggles apply to calls from outside the component; use an
  [`either` step](#inside-a-method-toggle-one-step) for logic the component calls itself.
- Step discovery looks one level deep: a proxy held directly in a component's (or bean's)
  field is found, one nested inside another field's object is not.
- The context is read on the calling thread; context held in a `ThreadLocal` does not
  follow work handed to other threads (`@Async`, executors), so propagate it yourself.
- Spring: one toggleable bean per interface (it becomes `@Primary`); annotate component
  classes, not `@Bean` methods.

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md), and the
[good first issues](https://github.com/kamranzafar/flipjack/labels/good%20first%20issue)
to get started.

## License

Copyright 2026 Kamran Zafar. Licensed under the [Apache License, Version 2.0](LICENSE).
