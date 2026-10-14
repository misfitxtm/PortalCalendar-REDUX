# Releasing

Release APKs are signed with a key that lives **outside the repo**. Keep it safe: if you lose it,
nobody can update an installed copy without uninstalling first.

## One-time: create the key

```sh
mkdir -p ~/.config/portalhub && chmod 700 ~/.config/portalhub
keytool -genkeypair -keystore ~/.config/portalhub/portalhub-redux-release.jks -alias portalhub-redux \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=PortalHub REDUX, O=<you>, C=US"
cat > ~/.config/portalhub/signing.properties <<'PROPS'
storeFile=/home/<you>/.config/portalhub/portalhub-redux-release.jks
storePassword=<the password you chose>
keyAlias=portalhub-redux
keyPassword=<the same password: PKCS12 keystores use one>
PROPS
chmod 600 ~/.config/portalhub/*
```

Back up the `.jks` and the passwords somewhere private (a password manager). Never commit them.
`PORTALHUB_SIGNING=/path/to/signing.properties` points the build at a different file.

## Each release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Build and verify:
   ```sh
   ./gradlew :app:assembleRelease
   APK=app/build/outputs/apk/release/app-release.apk
   apksigner verify --print-certs $APK          # certificate SHA-256 must match the one in the README
   aapt2 dump badging $APK | grep -c application-debuggable   # must print 0
   ```
3. Name it and write a checksum:
   ```sh
   cp $APK portalhub-redux-<version>.apk
   sha256sum portalhub-redux-<version>.apk > portalhub-redux-<version>.apk.sha256
   ```
4. Tag and publish:
   ```sh
   git tag -a v<version> -m "PortalHub REDUX <version>" && git push origin v<version>
   gh release create v<version> portalhub-redux-<version>.apk portalhub-redux-<version>.apk.sha256 \
     --title "PortalHub REDUX <version>" --notes "..."
   ```

## Adding it to OpenPortal

OpenPortal is a catalog of JSON files in [andronedev/openportal](https://github.com/andronedev/openportal).
Fork it, branch from `develop`, add `catalog/apps/portalhub-redux/app.json` (copy `catalog/_template/app.json`
and a similar entry), add the id to `catalog/index.json`, run `pnpm lint && pnpm build`, and open a pull
request. Use `"source": "github"`, `"repo": "misfitxtm/PortalCalendar-REDUX"` and an `assetPattern` that matches
the release file, e.g. `portalhub-redux.*\.apk$`.
