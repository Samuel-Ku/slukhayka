# Crash diagnostics: перший безпечний запуск

Цей контур нічого не збирає, доки його не налаштувати. Це навмисно: збої не
повинні перетворюватися на ще один прихований канал даних.

## 1. Read-only Crashlytics

Створіть окремий Google service account тільки для `crash-collect.yml`.
Він має читати потрібний Crashlytics `v1alpha` endpoint і не має прав на
зміну Firebase issue, Firestore, Storage або Secret Manager.

Зв'яжіть лише цей репозиторій і лише workflow `crash-collect.yml` із
Workload Identity Federation. Не створюйте service-account JSON key. WIF
саме для цього й потрібен: GitHub видає короткий OIDC token, а не зберігає
довгоживучий ключ. [Налаштування Google auth](https://github.com/google-github-actions/auth/blob/main/docs/EXAMPLES.md)
і [Crashlytics REST API](https://firebase.google.com/docs/reference/crashlytics/rest)
— джерела для фактичних IAM налаштувань.

Додайте GitHub Actions **Variables** (не Secrets):

| Variable | Значення |
| --- | --- |
| `CRASHLYTICS_WIF_PROVIDER` | Повне ім'я provider: `projects/<number>/locations/global/workloadIdentityPools/<pool>/providers/<provider>` |
| `CRASHLYTICS_READER_SERVICE_ACCOUNT` | email окремого read-only service account |
| `CRASHLYTICS_PROJECT_ID` | Firebase / Google Cloud project id |
| `CRASHLYTICS_ANDROID_APP_ID` | Firebase Android app id |

Provider використовує **project number**, не project id. Обмежте його GitHub
claims репозиторієм `Samuel-Ku/slukhayka` і workflow/branch, з якого дозволено
collect. Після зміни WIF зачекайте кілька хвилин: IAM тут не завжди
спрацьовує миттєво.

## 2. Перевірка без публікації

Запустіть `Crash diagnostics collect` вручну. Collect job має тільки
`contents: read` і `id-token: write`; queue не має ні того, ні іншого, а
publish має лише `issues: write`. Результат collect — artifact
`crash-sanitized-groups`, у якому тільки allowlisted дані. Там не повинно бути
raw event, URL, session/installation id, headers чи trace.

Перший запуск перевіряє лише те, що API відповідає і sanitizer відкидає
невідомі поля. Якщо job пише `needs-triage`, не додавайте ширші права «щоб
запрацювало»: спершу перевірте WIF identity, read scope та відповідь adapter.

## 3. OpenCode diagnosis proxy

Diagnosis запускається окремо від collect і отримує лише sanitized artifact
та checkout. Proxy для GLM-5.3-Flash має сам зберігати provider credential.
Workflow і OpenCode не отримують reusable Z.ai key.

Перед увімкненням diagnosis додайте public URL proxy як
`CRASH_DIAGNOSIS_PROXY_URL`. Proxy повинен приймати лише short-lived GitHub
OIDC token із фіксованою audience, дозволяти тільки цей репозиторій/workflow і
відповідати структурованим diagnosis contract. Не передавайте ключ у GitHub
Secret як заміну proxy.

## 4. Межа реалізації

Навіть proven diagnosis не мержиться сам. Implementation run повторює
зафіксовану red-команду, створює максимум один `codex/crash-<issue>` PR і
залишає його на CI та людське рішення. Авто-merge, release і deployment тут
не дозволені.
