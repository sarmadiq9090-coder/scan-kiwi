# Contributing / المساهمة

## عربي

- **المشكلات (Issues) مرحّب بها**: الأخطاء والاقتراحات عبر قوالب المشكلات، أو من داخل التطبيق:
  الإعدادات ◂ «أبلغنا عن مشكلة أو اقتراح».
- **لا نقبل طلبات الدمج (Pull requests) حاليًا.** يمكنك فتح مشكلة تصف التغيير المقترح.
- لا ترفق مستندات حقيقية أو صورًا شخصية؛ صفحات المشكلات عامة.
- الثغرات الأمنية: راجع `SECURITY.md`.

## English

- **Issues are welcome**: bugs and suggestions through the issue templates, or from inside the app:
  Settings ▸ "Report a problem or suggestion".
- **Pull requests are not accepted for now.** Please open an issue describing the change instead.
- Never attach real documents or personal photos; issues are public.
- Security problems: see `SECURITY.md`.

## Building and testing

```
./gradlew :app:testFullDebugUnitTest      # JVM + Robolectric tests
./gradlew :app:assembleFullRelease      # unsigned unless keystore/keystore.properties exists
```

JDK 21, Android SDK platform 36 and build-tools 36.0.0. See README for details.

## Release format (for maintainers)

- The Git tag is `vX.Y.Z` and equals `versionName` ([SemVer 2.0.0](https://semver.org/)).
- Exactly **one `.apk` asset** per GitHub release, plus `SHA256SUMS.txt`, the SBOM and the provenance file.
- Stable releases are never marked pre-release; test builds are published **only** as pre-releases.
- `versionCode` always increases, and the release signing key never changes
  (Android refuses updates signed with another key).
- Every release gets a `CHANGELOG.md` entry (Keep a Changelog 1.1.0).
- Release APKs are signed locally, never in CI.
