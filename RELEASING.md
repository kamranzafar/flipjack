# Releasing

Releases are built from a clean clone of the release tag, signed with your GPG key, and
uploaded to Maven Central with the `release` Maven profile.

## One-time setup

- A [Central Portal](https://central.sonatype.com) account with the `org.kamranzafar`
  namespace verified, and a user token.
- The token in `~/.m2/settings.xml`, under the server id `central`:

  ```xml
  <settings>
    <servers>
      <server>
        <id>central</id>
        <username>TOKEN_USERNAME</username>
        <password>TOKEN_PASSWORD</password>
      </server>
    </servers>
  </settings>
  ```

- The signing key published to a keyserver, so Central can verify signatures:

  ```sh
  gpg --keyserver keys.openpgp.org --send-keys C7D696C81718DC31
  gpg --keyserver keyserver.ubuntu.com --send-keys C7D696C81718DC31
  ```

## Release

Replace `1.0.0` with the version being released and `1.0.1` with the next one.

`main` is protected: changes reach it through pull requests, which need every CI check to pass.

1. **Set the release version** and date the changelog entry, in a pull request:

   ```sh
   git checkout -b release-1.0.0
   mvn versions:set -DnewVersion=1.0.0 -DgenerateBackupPoms=false
   # also update the version in README.md (Maven and Gradle snippets), and in CHANGELOG.md
   # rename "Unreleased" to the version and date, and update the compare links at the bottom
   git commit -am "Release 1.0.0"
   git push -u origin release-1.0.0
   gh pr create --fill --milestone 1.0.0
   ```

   Merge the pull request once its checks pass.

2. **Tag the merge commit on `main`:**

   ```sh
   git checkout main
   git pull
   git tag -a v1.0.0 -m "1.0.0"
   git push origin v1.0.0
   ```

3. **Build, sign and upload from a clean clone** of the tag:

   ```sh
   rm -rf /tmp/flipjack-release
   git clone --branch v1.0.0 git@github.com:kamranzafar/flipjack.git /tmp/flipjack-release
   cd /tmp/flipjack-release
   mvn -P release deploy
   ```

   GPG asks for the key passphrase. The upload is validated but not published.

4. **Publish:** in the Central Portal, open the deployment, check it, and click **Publish**.
   It appears on Maven Central within about half an hour. Then create the GitHub release:

   ```sh
   gh release create v1.0.0 --title v1.0.0 --verify-tag \
       --notes-file <(sed -n '/^## \[1.0.0\]/,/^## \[/p' CHANGELOG.md | sed '1d;$d')
   ```

5. **Start the next version**, in a pull request:

   ```sh
   git checkout -b start-1.0.1
   mvn versions:set -DnewVersion=1.0.1-SNAPSHOT -DgenerateBackupPoms=false
   # add an "Unreleased" section and compare link to CHANGELOG.md; leave README.md on the
   # released version, since snapshots aren't published to Maven Central
   git commit -am "Start 1.0.1-SNAPSHOT"
   git push -u origin start-1.0.1
   gh pr create --fill
   ```

   Then close the release's milestone on GitHub.

To publish without the manual step in the Portal, add `-Dcentral.autoPublish=true` to the
`deploy` command.
