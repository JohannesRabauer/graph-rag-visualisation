# Releasing graphrag-core to Maven Central

`graphrag-core` and `graphrag-core-testkit` are published as
`dev.rabauer.graphrag:graphrag-core` and `dev.rabauer.graphrag:graphrag-core-testkit`
through the [Central Portal](https://central.sonatype.com), always together
and under the same version. The workflow `.github/workflows/release-core.yml`
builds and tests both, checks that every class is Java 21 (major version 65),
signs them and uploads them; the upload then waits in the Portal until someone
clicks **Publish**.

**A published version can never be deleted or replaced.** Check the deployment
in the Portal before publishing it.

## One-time setup

1. **Central Portal account** at https://central.sonatype.com.
2. **Namespace `dev.rabauer`:** in the Portal, *Namespaces* → *Add Namespace*,
   enter `dev.rabauer`, then add the verification key it shows as a DNS
   **TXT record** on `rabauer.dev` and click *Verify*. The TXT record can be
   removed once the namespace is verified.
3. **GPG signing key:**
   ```bash
   gpg --full-generate-key
   gpg --list-secret-keys --keyid-format=long
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --armor --export-secret-keys <KEY_ID>
   ```
   The last command prints the private key for the `GPG_PRIVATE_KEY` secret.
4. **Portal user token:** *Account* → *Generate User Token*. This gives a
   username/password pair (not your login).
5. **GitHub Actions secrets** (*Settings* → *Secrets and variables* → *Actions*):

   | Secret | Value |
   | --- | --- |
   | `CENTRAL_USERNAME` | token username |
   | `CENTRAL_PASSWORD` | token password |
   | `GPG_PRIVATE_KEY` | the armored private key |
   | `GPG_PASSPHRASE` | its passphrase |

## Cutting a release

1. In `CHANGELOG.md`, rename `[Unreleased]` to `[X.Y.Z] - YYYY-MM-DD` and commit.
   The POMs stay at `X.Y.Z-SNAPSHOT`; the workflow sets the release version
   in both itself.
2. Tag and push:
   ```bash
   git tag graphrag-core-vX.Y.Z
   git push origin graphrag-core-vX.Y.Z
   ```
   (Or run the workflow by hand from the *Actions* tab with the version as input.)
3. When the workflow is green, open *Deployments* in the Portal, check the files
   of both artifacts (jar, `-sources`, `-javadoc`, POM, each with an `.asc`
   signature) and click **Publish**. If the Portal shows two deployments (one
   per artifact), publish both. It takes a few minutes to an hour to appear on Maven Central.
4. Bump to the next snapshot: `version` in `graphrag-core/pom.xml` and
   `graphrag-core-testkit/pom.xml` and `graphrag-core.version` in the root `pom.xml`,
   and update the coordinates in `README.md` and `graphrag-core/README.md`. The
   website picks up the new version by itself: its workflow checks Maven Central
   every hour and redeploys when the release appears.

## Trying it locally

Build exactly what would be uploaded, without signing or uploading:

```bash
mvn -pl graphrag-core,graphrag-core-testkit -Prelease package -Dgpg.skip
```
