# Security policy / سياسة الأمان

## الإبلاغ عن ثغرة (عربي)

نرجو **عدم** نشر الثغرات في مشكلات (Issues) عامة.

1. استعمل الإبلاغ الخاص عن الثغرات في GitHub:
   <https://github.com/sarmadiq9090-coder/scan-kiwi/security/advisories/new>
2. إن تعذّر ذلك: راسلنا على `support@fastkiwi.app` بعنوان يبدأ بـ `[SECURITY]`.

نردّ خلال 7 أيام، ونتفق معك على موعد النشر (إفصاح منسّق، عادةً خلال 90 يومًا من الإبلاغ أو بعد صدور الإصلاح).
لا ترفق مستندات حقيقية أو بيانات شخصية؛ ملف تجريبي يكفي.

## Reporting a vulnerability (English)

Please do **not** open a public issue for security problems.

1. Use GitHub private vulnerability reporting:
   <https://github.com/sarmadiq9090-coder/scan-kiwi/security/advisories/new>
2. If that is not possible, e-mail `support@fastkiwi.app` with a subject starting `[SECURITY]`.

We reply within 7 days and agree a disclosure date with you (coordinated disclosure, normally within 90 days of
the report or once a fix is released). Please do not send real documents or personal data; a synthetic sample is enough.

## Scope

- The Android app in this repository, latest release.
- Of special interest: anything that could make a document leave the phone without the user choosing Share/Save,
  intent or FileProvider exposure, PDF encryption weaknesses, and supply-chain issues in the build.

## Supported versions

Only the latest release receives fixes. Updates are signed with the same key (certificate SHA-256 in README).
