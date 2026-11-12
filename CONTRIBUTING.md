# Contributing to Flipjack

Thanks for your interest! Contributions of any size are welcome: code, tests, documentation,
or just trying Flipjack and reporting what's confusing.

## What Flipjack aims for

Flipjack is a **well-designed, lightweight** feature-toggle library where feature code needs
**no if-else**: a proxy decides which implementation runs. Changes are weighed against that goal:

- `flipjack-core` stays small and has no runtime dependencies.
- Integrations (Spring Boot, flag platforms) live in their own modules.
- Misconfiguration should fail when the proxy is built, at startup, not on a live call.
- Platform features such as admin UIs, rollout rules and experiments belong in flag services
  that plug in through `FeatureToggles`, not in Flipjack itself.

## Finding something to work on

- Issues labelled [`good first issue`](https://github.com/kamranzafar/flipjack/labels/good%20first%20issue)
  are small and self-contained.
- Issues labelled [`help wanted`](https://github.com/kamranzafar/flipjack/labels/help%20wanted)
  are larger; please share your plan in the issue before starting.
- For a new idea, open an issue first to discuss it.

### Claiming an issue

Comment **`/assign`** on an issue to take it; it's assigned to you automatically. Put the
command on the first line of your comment, for example `Hi! /assign`. A few rules keep things
fair for everyone:

- **One issue at a time.** You can be assigned to one open issue. Once it's closed (usually
  when your pull request is merged), or you release it, you can take another.
- **Already taken?** If someone else is assigned, pick another issue.
- **Can't continue?** Comment **`/unassign`** to release the issue for someone else.
- **Stay active.** After 10 days with no comment from you and no open pull request
  referencing the issue, you'll get a reminder; after 14 days the issue is released. A comment
  with a progress update, or a draft pull request, keeps it yours.
- **Keep your pull request moving.** If a review asks for changes and you haven't pushed or
  commented 7 days later, you'll get a reminder; 7 days after that, the pull request is closed
  and the issue is released. If you need more time, just say so in a comment.

Reference the issue in your pull request, for example `Fixes #12`, so it's linked and closes
when merged.

## Building

You need Java 17 or later and Maven 3.9+.

```sh
git clone https://github.com/kamranzafar/flipjack.git
cd flipjack
mvn verify
```

This builds every module and runs every test. CI runs the same command on Java 17, 21 and 25,
on Java 17 against Spring Boot 3.3 and 3.4, and once more against Spring Boot 4.1
(the default build uses 3.5). To test against Spring Boot 4.1 locally:

```sh
mvn verify -Dspring-boot.version=4.1.2
```

CI also scans the dependencies, test scope included, for known vulnerabilities with
[OSV-Scanner](https://google.github.io/osv-scanner/), for both Spring Boot versions, and fails
the build if it finds any (see `.github/scripts/osv-scan.sh`). To run the same scan locally
(with `osv-scanner` installed; add `-Dspring-boot.version=4.1.2` to `makeBom` for Spring Boot 4.1):

```sh
mvn cyclonedx:makeBom
osv-scanner scan source -L flipjack-core/target/bom.cdx.json \
    -L flipjack-spring-boot-starter/target/bom.cdx.json -L flipjack-openfeature/target/bom.cdx.json
```

If it reports a dependency that Spring Boot manages, and no Spring Boot patch release fixes it,
override the version in the parent `pom.xml` the way `jackson.version` and `log4j.version` do.

## Pull requests

- Keep each pull request small and focused on one change.
- Add or update tests for any behaviour change. `ReadmeExamplesTest` checks the README's
  examples, so update it if you change an example.
- Update the README and `CHANGELOG.md` (under **Unreleased**) for user-visible changes.
- Match the style of the surrounding code: naming, comment density, and short Javadoc that says
  what something is for.
- Keep the Apache 2.0 license header at the top of every new source file; copy it from any
  existing file.
- Make sure `mvn verify` passes before opening the pull request.

## Reporting bugs

Open an issue with the Flipjack and Java versions, the Spring Boot version if relevant, a short
code sample that reproduces the problem, and what you expected to happen.

## License

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE), the same license as the project.
