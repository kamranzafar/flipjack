# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1.0] - 2026-11-02

### Fixed

- Compile endpoint parameter names so Spring Actuator can bind `/actuator/flipjack/{name}` correctly.
- Published poms point each module's project and SCM URLs at the repository, not at a
  non-existent per-module path.

### Added

- `flipjack-openfeature` module: `OpenFeatureToggles` uses any OpenFeature provider as the
  toggle source, with `defaultEnabled`, `requireReady(timeout)` and `onEvaluationError`, and
  maps `FeatureContext` to the OpenFeature evaluation context.
- Optional `subject` query parameter for evaluating a feature through the Actuator endpoint.
- Spring Boot 4.1 support: the starter works unchanged on Spring Boot 4.1 as well as 3.5, and
  CI tests it against both.

### Changed

- When the toggle source can't list its features, the Spring Boot consistency check now says
  it was skipped: as info under `consistency-check: warn`, and as a warning under `fail`,
  which has no effect with such a source. It was a debug log before. Startup still never
  fails in this case.

### Security

- Build against Jackson 2.21.8 and log4j 2.25.5 instead of the versions Spring Boot 3.5.16
  manages (Jackson 2.21.4, log4j-api 2.24.3), which have known vulnerabilities. Both are only
  on Flipjack's own build and test classpath (Jackson through the optional Actuator
  dependency), so applications get the versions their own Spring Boot manages.
- CI scans dependencies for known vulnerabilities with OSV-Scanner and fails the build if it
  finds any.

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

[Unreleased]: https://github.com/kamranzafar/flipjack/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/kamranzafar/flipjack/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/kamranzafar/flipjack/releases/tag/v1.0.0
