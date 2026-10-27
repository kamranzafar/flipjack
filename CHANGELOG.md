# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Optional `subject` query parameter for evaluating a feature through the Actuator endpoint.

### Documentation

- Gradle (Kotlin and Groovy DSL) dependency snippets next to the Maven ones.
- A Kotlin section with examples for class-level, method-level and step toggles, Spring
  wiring, and Kotlin-specific notes.
- A Java version badge.

## [1.0.0] - 2026-10-27

First release.

### flipjack-core

- `@Toggleable` on classes and methods: a JDK dynamic proxy routes each call to the
  implementation, a fallback object, a `fallbackMethod`, or `whenDisabled` (`THROW` or `NO_OP`),
  so feature code contains no toggle checks.
- `ToggledFeature` builder and `ToggledFeature.either(...)` for toggling one step inside a method.
- Misconfiguration fails when the proxy is built: unknown or incompatible fallbacks, conflicting
  fallback settings, a `whenDisabled` that a fallback would override, and annotations with no effect.
- Toggle sources: `PollingFeatureToggles`, `InMemoryFeatureToggles`, `PropertiesFileFeatureToggles`,
  or any `FeatureToggles` implementation.
- Decorators: caching, fail-safe, runtime overrides and observation.
- Per-call `FeatureContext` for per-user and per-tenant targeting.
- `FeatureConsistency` check for misspelt and stale feature names.
- No runtime dependencies; Java 17+.

### flipjack-spring-boot-starter

- Auto-wiring of `@Toggleable` beans and `flipjack.*` configuration.
- Optional `FeatureContextProvider` bean for per-user toggles.
- Startup consistency check (`off`, `warn` or `fail`) and the `/actuator/flipjack` endpoint.
- Built and tested against Spring Boot 3.5.

[Unreleased]: https://github.com/kamranzafar/flipjack/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/kamranzafar/flipjack/releases/tag/v1.0.0
