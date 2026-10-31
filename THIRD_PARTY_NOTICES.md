# Third-party notices

Komichi Agent's original code is MIT-licensed. No source code from the five comparison repositories was copied. Their licenses do not grant rights to their names, logos, or OAuth client registrations.

Operit (LGPL-3.0, https://github.com/AAswordman/Operit) was also consulted for its permission-based file-tool routing and the organization of chat progress, workspaces and workflows at commit dbf71916fae9750cfdc9f9a774f5a0fee56633fb. No Operit source code or assets were copied or bundled. See docs/root-file-operations.md and docs/operit-coverage.md for the referenced files and the independently implemented scope.

The Android app uses these separately licensed dependencies, including transitive dependencies resolved by Gradle:

| Component | License | Upstream |
|---|---|---|
| AndroidX / Compose / Lifecycle / Activity | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin / kotlinx.coroutines | Apache-2.0 | https://github.com/JetBrains/kotlin / https://github.com/Kotlin/kotlinx.coroutines |
| OkHttp / Okio | Apache-2.0 | https://github.com/square/okhttp / https://github.com/square/okio |
| Nimbus JOSE + JWT | Apache-2.0 | https://connect2id.com/products/nimbus-jose-jwt |
| Markwon | Apache-2.0 | https://github.com/noties/Markwon |
| commonmark-java (via Markwon) | BSD-2-Clause | https://github.com/commonmark/commonmark-java |
| Shizuku API / provider | Apache-2.0 | https://github.com/RikkaApps/Shizuku-API |
| Gradle wrapper (build tooling) | Apache-2.0 | https://github.com/gradle/gradle |

Android framework classes, including org.json, are supplied by Android. JVM-only test dependencies (JUnit 4, JSON-java, MockWebServer, coroutines-test) are not shipped in the app. JUnit 4 uses EPL-1.0; the resolved JSON-java 20250517 POM declares Public Domain. Refer to the resolved upstream POMs and artifacts for copyright and license text. Gradle's dependency report is recorded in docs/dependencies.txt when the build is validated.

This source release includes license texts in licenses/. Preserve applicable notices in redistributed builds. The app uses an original compass icon and does not include the ChatGPT logo.
