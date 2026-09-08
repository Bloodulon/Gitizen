# Gitizen

Безопасное обновление Denizen-скриптов из Git прямо на Minecraft-сервере.

Gitizen загружает нужную ветку репозитория, проверяет новые скрипты, аккуратно
заменяет рабочий каталог Denizen и автоматически возвращает предыдущую версию,
если Denizen обнаруживает ошибку.

---

## Contents / Содержание

- [Русский](#ru)
  - [Возможности](#ru-features)
  - [Требования](#ru-requirements)
  - [Установка](#ru-installation)
  - [Быстрый старт](#ru-quick-start)
  - [Профили production и staging](#ru-profiles)
  - [Команды](#ru-commands)
  - [Rollback](#ru-rollback)
  - [Discord и Telegram](#ru-notifications)
  - [Настройки безопасности](#ru-security)
  - [Частые проблемы](#ru-troubleshooting)
- [English](#en)
  - [Features](#en-features)
  - [Requirements](#en-requirements)
  - [Installation](#en-installation)
  - [Quick start](#en-quick-start)
  - [Production and staging profiles](#en-profiles)
  - [Commands](#en-commands)
  - [Rollback](#en-rollback)
  - [Discord and Telegram](#en-notifications)
  - [Security](#en-security)
  - [Troubleshooting](#en-troubleshooting)

---

<a id="ru"></a>

# Русский

<a id="ru-features"></a>

## Возможности

- Синхронизация Denizen-скриптов с GitHub по HTTPS.
- Работа с приватными и публичными репозиториями.
- Несколько профилей, например `production` и `staging`.
- Отдельная ветка и каталог для каждого профиля.
- Проверка файлов `.dsc` parser-ом установленного Denizen.
- Безопасная замена каталога через staging-папку.
- Автоматический возврат предыдущей версии при ошибке загрузки Denizen.
- Ручной rollback по номеру deployment или commit hash.
- Команда статуса с текущим и удалённым commit.
- История deployment и постоянные метрики.
- Discord и Telegram уведомления.
- Интерактивные сообщения: hover-подсказки, копирование hash и ссылки на GitHub.
- Защита от одновременного запуска двух операций одного профиля.
- Обнаружение файлов, вручную изменённых в рабочем каталоге.

Git checkout хранится в `plugins/Gitizen/repositories`, а не в
`plugins/Denizen/scripts`. Поэтому папка Denizen не содержит `.git` и используется
только для активных скриптов.

<a id="ru-requirements"></a>

## Требования

- Paper 1.21.x.
- Java 21 или новее.
- Установленный и запущенный Denizen.
- Исходящий HTTPS-доступ к GitHub.
- Для уведомлений — исходящий HTTPS-доступ к Discord или Telegram.

Gitizen зависит от Denizen. Если Denizen отсутствует, Paper не загрузит Gitizen.

<a id="ru-installation"></a>

## Установка

1. Остановите Minecraft-сервер.
2. Скопируйте `Gitizen-1.0.0.jar` в папку `plugins`.
3. Убедитесь, что `Denizen.jar` также находится в папке `plugins`.
4. Запустите сервер один раз.
5. Остановите сервер и откройте `plugins/Gitizen/config.yml`.
6. Укажите URL репозитория и нужную ветку.
7. Если репозиторий приватный, настройте токен.
8. Запустите сервер и выполните `/gitizen status`.
9. Для первого deployment выполните `/gitizen sync`.

Перед первой установкой рекомендуется сохранить резервную копию
`plugins/Denizen/scripts`. Во время дальнейших deployment Gitizen самостоятельно
сохраняет предыдущий рабочий каталог до завершения проверки.

<a id="ru-quick-start"></a>

## Быстрый старт

### Публичный репозиторий

Минимальная конфигурация:

```yaml
default-profile: "production"

profiles:
  production:
    repo-url: "https://github.com/USER/REPOSITORY.git"
    branch: "main"
    repository-subdirectory: ""
    target-directory: "scripts"
    reload-denizen: true
    username: "gitizen"
    token-env: "GITIZEN_GITHUB_TOKEN"
    token: ""
```

Если `.dsc` находятся, например, в папке `minecraft/denizen` внутри репозитория:

```yaml
repository-subdirectory: "minecraft/denizen"
```

После сохранения конфигурации:

```text
/gitizen reload
/gitizen status production
/gitizen sync production
```

### Приватный репозиторий

Создайте GitHub fine-grained Personal Access Token с доступом только к нужному
репозиторию и разрешением Contents: Read.

Предпочтительный вариант — переменная окружения:

```text
GITIZEN_GITHUB_TOKEN=github_pat_...
```

Название переменной указывается в:

```yaml
token-env: "GITIZEN_GITHUB_TOKEN"
```

На игровых хостингах переменные окружения могут называться «Environment variables»,
«Startup variables» или «Secrets». Если панель их не поддерживает, токен можно
записать непосредственно в конфиг:

```yaml
token-env: ""
token: "github_pat_..."
```

Не передавайте PAT через Minecraft-команду. Команда `/gitizen setup` намеренно
не принимает токен, чтобы он не оказался в консольных логах.

<a id="ru-profiles"></a>

## Профили production и staging

Профиль — это сохранённый сценарий deployment. Он отвечает на пять вопросов:

1. Из какого Git-репозитория загружать файлы?
2. Какую ветку использовать?
3. Из какой папки внутри репозитория брать скрипты?
4. В какую папку Denizen их поместить?
5. Нужно ли после этого перезагрузить активные скрипты?

Названия `production` и `staging` сами по себе ничего не переключают. Это обычные
имена профилей, которые можно изменить. Поведение определяется значениями
`branch`, `repository-subdirectory`, `target-directory` и `reload-denizen`.

В стандартной схеме профили используются так:

| Профиль | Ветка Git | Куда копируются файлы | Denizen исполняет эти файлы? |
|---|---|---|---|
| `production` | `main` | `plugins/Denizen/scripts` | Да, сразу после успешной проверки |
| `staging` | `develop` | `plugins/Denizen/scripts-staging` | Нет, это отдельная тестовая копия |

Пример двух профилей:

```yaml
default-profile: "production"

profiles:
  production:
    repo-url: "https://github.com/USER/REPOSITORY.git"
    branch: "main"
    repository-subdirectory: "denizen"
    target-directory: "scripts"
    reload-denizen: true
    username: "gitizen"
    token-env: "GITIZEN_GITHUB_TOKEN"
    token: ""

  staging:
    repo-url: "https://github.com/USER/REPOSITORY.git"
    branch: "develop"
    repository-subdirectory: "denizen"
    target-directory: "scripts-staging"
    reload-denizen: false
    username: "gitizen"
    token-env: "GITIZEN_GITHUB_TOKEN"
    token: ""
```

### Что произойдёт при sync production

```text
/gitizen sync production
```

Gitizen выполнит следующую последовательность:

1. Загрузит ветку `main` из указанного `repo-url`.
2. Возьмёт только содержимое папки `denizen` из репозитория.
3. Проверит найденные `.dsc` parser-ом Denizen.
4. Подготовит новую версию во временной папке.
5. Заменит `plugins/Denizen/scripts`.
6. Перезагрузит Denizen, потому что указано `reload-denizen: true`.
7. При ошибке автоматически вернёт предыдущие скрипты.

После успешной команды изменения становятся активными на игровом сервере.

### Что произойдёт при sync staging

```text
/gitizen sync staging
```

Gitizen загрузит ветку `develop`, проверит файлы и положит их в
`plugins/Denizen/scripts-staging`. Рабочая папка `plugins/Denizen/scripts` при
этом не изменяется, а Denizen продолжает исполнять текущую production-версию.

Профиль staging полезен, чтобы:

- убедиться, что нужные файлы действительно загружаются из Git;
- заранее проверить структуру `.dsc`;
- посмотреть подготовленную версию на сервере;
- не затронуть игроков во время подготовки релиза.

Важно: Gitizen не переносит staging в production автоматически. Обычный безопасный
процесс выглядит так:

1. Разработчик отправляет изменения в ветку `develop`.
2. Администратор выполняет `/gitizen sync staging` и проверяет результат.
3. Изменения объединяются из `develop` в `main` через GitHub.
4. Администратор выполняет `/gitizen sync production`.

Staging-проверка подтверждает, что файлы читаются parser-ом, но не проверяет их
поведение внутри игры: `reload-denizen: false` означает, что эти файлы не
подключаются к работающему Denizen.

### Разница между source и target

Эти два параметра часто путают:

- `repository-subdirectory` — откуда брать файлы **внутри Git-репозитория**;
- `target-directory` — куда помещать файлы **на Minecraft-сервере**.

Для приведённого примера путь выглядит так:

```text
GitHub: REPOSITORY/denizen/
                   │
                   ├─ production → plugins/Denizen/scripts/
                   └─ staging    → plugins/Denizen/scripts-staging/
```

`target-directory` задаётся относительно `plugins/Denizen`:

| Значение | Полный путь на сервере |
|---|---|
| `scripts` | `plugins/Denizen/scripts` |
| `scripts-staging` | `plugins/Denizen/scripts-staging` |
| `test/server-one` | `plugins/Denizen/test/server-one` |

Указать `../` и выйти за пределы `plugins/Denizen` нельзя — Gitizen заблокирует
такую конфигурацию.

### Что означает reload-denizen

- `true` — после замены файлов Gitizen просит Denizen загрузить новую версию.
  Используйте это для активного production-каталога.
- `false` — файлы копируются и проверяются, но работающий Denizen их не загружает.
  Используйте это для staging, архива или ручной проверки.

Если указано `reload-denizen: true`, Gitizen сверяет `target-directory` с реальной
активной папкой Denizen. При несовпадении deployment останавливается. Это защищает
от ситуации, когда команда сообщает об успехе, но Denizen продолжает использовать
другую папку.

### Разные репозитории и токены

Профили не обязаны использовать один репозиторий. Например, production можно
загружать из закрытого основного репозитория, а staging — из отдельного тестового.
Для разных токенов укажите разные переменные окружения:

```yaml
profiles:
  production:
    token-env: "GITIZEN_PRODUCTION_TOKEN"
  staging:
    token-env: "GITIZEN_STAGING_TOKEN"
```

`default-profile: "production"` означает, что команда `/gitizen sync` без имени
профиля равнозначна `/gitizen sync production`.

Профиль можно создать командой:

```text
/gitizen setup production https://github.com/USER/REPOSITORY.git main
```

После изменения `repository-subdirectory`, каталога или секретов выполните
`/gitizen reload`.

<a id="ru-commands"></a>

## Команды

| Команда | Описание | Право |
|---|---|---|
| `/gitizen help` | Показать доступные команды | — |
| `/gitizen sync [profile]` | Проверить и развернуть выбранную ветку | `gitizen.sync` |
| `/gitizen status [profile]` | Показать Git HEAD, remote HEAD и метрики | `gitizen.status` |
| `/gitizen rollback [profile] <номер\|hash>` | Вернуть предыдущую версию | `gitizen.rollback` |
| `/gitizen logs [profile] [count]` | Показать последние commits | `gitizen.logs` |
| `/gitizen list [profile]` | Показать все `.dsc` рекурсивно | `gitizen.list` |
| `/gitizen profiles` | Показать настроенные профили | `gitizen.status` |
| `/gitizen setup <profile> <url> [branch]` | Создать или изменить профиль | `gitizen.admin` |
| `/gitizen reload` | Перечитать `config.yml` | `gitizen.admin` |

Если профиль не указан, используется `default-profile`.

Право `gitizen.admin` включает все остальные права.

### Что показывает status

`/gitizen status production` выводит:

- ветку профиля;
- HEAD внутреннего checkout;
- HEAD удалённой ветки;
- commit, который сейчас развёрнут;
- дату последнего успешного deployment;
- количество успешных и неуспешных операций;
- длительность и число файлов последней операции.

Если deployed commit отличается от remote HEAD, доступно обновление либо сейчас
активен rollback.

<a id="ru-rollback"></a>

## Rollback

Вернуть deployment, который был перед текущим:

```text
/gitizen rollback 1
```

То же для конкретного профиля:

```text
/gitizen rollback production 1
```

Откатиться на конкретный commit:

```text
/gitizen rollback production a1b2c3d
```

Число означает позицию среди предыдущих успешных deployment:

- `1` — версия перед текущей;
- `2` — ещё одна версия назад.

История хранится в `plugins/Gitizen/state.yml`. Её размер задаётся параметром
`deployment.max-history`.

После ручного rollback команда `/gitizen sync` снова развернёт актуальный
`origin/<branch>`.

### Автоматический rollback

Перед заменой рабочей папки Gitizen проверяет staging-файлы parser-ом Denizen.
После замены выполняется настоящий reload Denizen. Если Denizen сообщает об ошибке:

1. Новая папка удаляется.
2. Возвращается предыдущая папка.
3. Возвращается предыдущий Git HEAD.
4. Denizen повторно загружает восстановленные скрипты.
5. Ошибка записывается в метрики и отправляется в настроенные уведомления.

<a id="ru-notifications"></a>

## Discord и Telegram

Отдельно хостить бота или веб-приложение не требуется. Gitizen отправляет HTTPS-запрос
прямо с Minecraft-сервера. Хостинг должен разрешать исходящие соединения.

### Discord

1. Откройте настройки Discord-сервера.
2. Перейдите в Integrations → Webhooks.
3. Создайте webhook и скопируйте URL.
4. Передайте URL через переменную окружения:

```text
GITIZEN_DISCORD_WEBHOOK=https://discord.com/api/webhooks/...
```

Конфигурация:

```yaml
notifications:
  discord:
    webhook-url-env: "GITIZEN_DISCORD_WEBHOOK"
    webhook-url: ""
```

Если переменные окружения недоступны, URL можно записать в `webhook-url`.

### Telegram

1. Создайте бота через `@BotFather`.
2. Отправьте своему боту любое сообщение.
3. Получите ID пользователя, группы или канала.
4. Добавьте бота в нужную группу/канал и выдайте необходимые права.
5. Настройте переменные:

```text
GITIZEN_TELEGRAM_BOT_TOKEN=123456:ABC...
GITIZEN_TELEGRAM_CHAT_ID=-1001234567890
```

Конфигурация:

```yaml
notifications:
  telegram:
    bot-token-env: "GITIZEN_TELEGRAM_BOT_TOKEN"
    chat-id-env: "GITIZEN_TELEGRAM_CHAT_ID"
    bot-token: ""
    chat-id: ""
```

Если уведомления не приходят, проверьте токен, chat ID, права бота и доступ
Minecraft-хостинга к `api.telegram.org`.

<a id="ru-security"></a>

## Настройки безопасности

```yaml
security:
  require-https: true
  allowed-hosts:
    - "github.com"

validation:
  require-dsc-files: true
  max-script-size-kb: 1024

deployment:
  max-history: 20
  max-changes-in-chat: 30
```

- `require-https` запрещает незашифрованные и SSH URL.
- `allowed-hosts` ограничивает серверы, которым Gitizen может передать Git credentials.
- `require-dsc-files` запрещает deployment без Denizen-скриптов.
- `max-script-size-kb` ограничивает размер одного проверяемого скрипта.
- `max-history` определяет глубину rollback.
- `max-changes-in-chat` ограничивает количество строк изменений в Minecraft-чате.

Для GitHub Enterprise добавьте его домен в `allowed-hosts`.

Репозиторий Denizen фактически содержит исполняемую серверную логику. Выдавайте
доступ на запись только доверенным разработчикам и используйте read-only PAT.

<a id="ru-troubleshooting"></a>

## Частые проблемы

### «Ветка не найдена в origin»

Проверьте точное имя `branch`, регистр букв и наличие этой ветки в репозитории.

### «Хост отсутствует в security.allowed-hosts»

Добавьте домен Git-сервера в `security.allowed-hosts`. Не добавляйте неизвестные
домены, если используете приватный токен.

### Ошибка авторизации GitHub

Проверьте:

- URL использует HTTPS;
- PAT не истёк;
- PAT имеет Contents: Read для нужного репозитория;
- организация разрешила токен, если используется SSO;
- переменная окружения действительно передана процессу Java.

### «target-directory не совпадает с активным каталогом Denizen»

Для профиля с `reload-denizen: true` каталог должен совпадать с папкой, которую
реально использует Denizen. Обычно это `plugins/Denizen/scripts`, поэтому значение
должно быть `scripts`.

### Deployment отклонён до активации

Проверьте сообщения Gitizen и консоль. Возможные причины: пустой `.dsc`, ошибка
структуры YAML/Denizen, слишком большой файл, symlink или отсутствие `.dsc`.

### Уведомления не отправляются

Убедитесь, что хостинг разрешает исходящий HTTPS на:

- `github.com`;
- `discord.com`;
- `api.telegram.org`.

Некоторые игровые панели требуют отдельного разрешения внешних соединений.

### Старый config.yml

Конфигурация старых версий с ключами `repo-url`, `github-token` и `branch`
читается как профиль `production`. Для доступа ко всем новым настройкам рекомендуется
сделать копию старого файла, удалить его и перезапустить сервер, затем перенести URL,
ветку и токен в новую структуру.

## Служебные файлы

| Путь | Назначение |
|---|---|
| `plugins/Gitizen/config.yml` | Пользовательские настройки |
| `plugins/Gitizen/state.yml` | История и метрики |
| `plugins/Gitizen/repositories/<profile>` | Внутренние Git checkout |
| `plugins/Denizen/scripts` | Активные production-скрипты |

Не редактируйте внутренние checkout вручную. Изменения в активном каталоге будут
обнаружены и заменены при следующем `sync`.

---

<a id="en"></a>

# English

Gitizen safely deploys Denizen scripts from Git repositories directly to a
Minecraft server. It fetches the configured branch, validates the candidate
scripts, swaps the Denizen directory, and restores the previous version
automatically when Denizen reports an error.

<a id="en-features"></a>

## Features

- Public and private GitHub repositories over HTTPS.
- Multiple deployment profiles such as `production` and `staging`.
- A separate repository, branch, source directory, and target for each profile.
- Preflight validation using the parser of the installed Denizen plugin.
- Staged directory replacement and automatic rollback.
- Manual rollback by deployment number or commit hash.
- Status command with repository, remote, deployment, and metrics information.
- Persistent deployment history.
- Discord and Telegram notifications.
- Adventure components with hover details, hash copying, and GitHub links.
- Per-profile operation lock and working-directory drift detection.

Git checkouts are stored under `plugins/Gitizen/repositories`. The active
`plugins/Denizen/scripts` directory is not used as a Git repository.

<a id="en-requirements"></a>

## Requirements

- Paper 1.21.x.
- Java 21 or newer.
- Denizen installed and enabled.
- Outbound HTTPS access to GitHub.
- Outbound HTTPS access to Discord or Telegram when notifications are enabled.

Gitizen declares Denizen as a required dependency.

<a id="en-installation"></a>

## Installation

1. Stop the Minecraft server.
2. Copy `Gitizen-1.0.0.jar` to the `plugins` directory.
3. Make sure Denizen is installed.
4. Start the server once.
5. Stop it and open `plugins/Gitizen/config.yml`.
6. Configure the repository URL and branch.
7. Configure a token if the repository is private.
8. Start the server and run `/gitizen status`.
9. Run `/gitizen sync` for the first deployment.

Create a manual backup of `plugins/Denizen/scripts` before the first installation.
Gitizen protects the previous directory during later deployments.

<a id="en-quick-start"></a>

## Quick start

Minimal public-repository configuration:

```yaml
default-profile: "production"

profiles:
  production:
    repo-url: "https://github.com/USER/REPOSITORY.git"
    branch: "main"
    repository-subdirectory: ""
    target-directory: "scripts"
    reload-denizen: true
    username: "gitizen"
    token-env: "GITIZEN_GITHUB_TOKEN"
    token: ""
```

If scripts are stored in a repository subdirectory:

```yaml
repository-subdirectory: "minecraft/denizen"
```

Apply and deploy:

```text
/gitizen reload
/gitizen status production
/gitizen sync production
```

For a private repository, create a fine-grained GitHub Personal Access Token with
Contents: Read access to the required repository. Prefer an environment variable:

```text
GITIZEN_GITHUB_TOKEN=github_pat_...
```

If your hosting panel does not support environment variables:

```yaml
token-env: ""
token: "github_pat_..."
```

Do not send the token through a Minecraft command. `/gitizen setup` intentionally
does not accept secrets.

<a id="en-profiles"></a>

## Production and staging profiles

A profile is a saved deployment recipe. It answers five questions:

1. Which Git repository should be fetched?
2. Which branch should be used?
3. Which directory inside the repository contains the scripts?
4. Which Denizen directory should receive them?
5. Should the active Denizen scripts be reloaded afterward?

`production` and `staging` are not special keywords. They are ordinary profile
names. Their behavior comes from `branch`, `repository-subdirectory`,
`target-directory`, and `reload-denizen`.

The default workflow is:

| Profile | Git branch | Destination | Does Denizen execute it? |
|---|---|---|---|
| `production` | `main` | `plugins/Denizen/scripts` | Yes, after successful validation |
| `staging` | `develop` | `plugins/Denizen/scripts-staging` | No, it is a separate test copy |

```yaml
profiles:
  production:
    repo-url: "https://github.com/USER/REPOSITORY.git"
    branch: "main"
    repository-subdirectory: "denizen"
    target-directory: "scripts"
    reload-denizen: true
    username: "gitizen"
    token-env: "GITIZEN_GITHUB_TOKEN"
    token: ""

  staging:
    repo-url: "https://github.com/USER/REPOSITORY.git"
    branch: "develop"
    repository-subdirectory: "denizen"
    target-directory: "scripts-staging"
    reload-denizen: false
    username: "gitizen"
    token-env: "GITIZEN_GITHUB_TOKEN"
    token: ""
```

### What sync production does

`/gitizen sync production` fetches `main`, reads the repository's `denizen`
directory, validates its `.dsc` files, replaces `plugins/Denizen/scripts`, and
reloads Denizen. If the reload reports an error, Gitizen restores the previous
directory automatically. A successful production deployment becomes live
immediately.

### What sync staging does

`/gitizen sync staging` fetches `develop`, validates the files, and writes them
to `plugins/Denizen/scripts-staging`. It does not modify
`plugins/Denizen/scripts`, and Denizen continues running the current production
version.

Staging is useful for checking the downloaded files and their basic parser
compatibility without affecting players. It is not promoted automatically.
A typical workflow is:

1. Push changes to `develop`.
2. Run `/gitizen sync staging` and inspect the result.
3. Merge `develop` into `main` on GitHub.
4. Run `/gitizen sync production`.

Because staging uses `reload-denizen: false`, it does not test actual in-game
behavior.

### Source and target directories

- `repository-subdirectory` is the source directory **inside the Git repository**.
- `target-directory` is the destination **on the Minecraft server**.

```text
GitHub: REPOSITORY/denizen/
                   │
                   ├─ production → plugins/Denizen/scripts/
                   └─ staging    → plugins/Denizen/scripts-staging/
```

`target-directory` is relative to `plugins/Denizen`. For example, `scripts`
means `plugins/Denizen/scripts`. Paths cannot escape the Denizen directory.

### reload-denizen

- `true` loads the deployed files into the running Denizen instance. Use it for
  the active production directory.
- `false` copies and parses the files without activating them. Use it for staging
  or manual inspection.

When reload is enabled, Gitizen verifies that the target matches Denizen's actual
active script directory. A mismatch blocks deployment instead of reporting a false
success.

Profiles may use different repositories, branches, or credentials. Set a different
`token-env` name for each profile when separate tokens are required.

`default-profile: "production"` makes `/gitizen sync` equivalent to
`/gitizen sync production`.

Create or update a profile without exposing a token:

```text
/gitizen setup production https://github.com/USER/REPOSITORY.git main
```

<a id="en-commands"></a>

## Commands

| Command | Description | Permission |
|---|---|---|
| `/gitizen help` | Show available commands | — |
| `/gitizen sync [profile]` | Validate and deploy the configured branch | `gitizen.sync` |
| `/gitizen status [profile]` | Show Git state and deployment metrics | `gitizen.status` |
| `/gitizen rollback [profile] <number\|hash>` | Restore an earlier deployment | `gitizen.rollback` |
| `/gitizen logs [profile] [count]` | Show recent commits | `gitizen.logs` |
| `/gitizen list [profile]` | Recursively list `.dsc` files | `gitizen.list` |
| `/gitizen profiles` | Show configured profiles | `gitizen.status` |
| `/gitizen setup <profile> <url> [branch]` | Create or update a profile | `gitizen.admin` |
| `/gitizen reload` | Reload `config.yml` | `gitizen.admin` |

The configured `default-profile` is used when no profile is specified.
`gitizen.admin` includes every other Gitizen permission.

<a id="en-rollback"></a>

## Rollback

Restore the deployment before the current one:

```text
/gitizen rollback 1
```

Restore it for a specific profile:

```text
/gitizen rollback production 1
```

Deploy a specific commit:

```text
/gitizen rollback production a1b2c3d
```

`1` means the successful deployment immediately before the current one; `2`
means one version further back. History is stored in
`plugins/Gitizen/state.yml` and limited by `deployment.max-history`.

After a manual rollback, `/gitizen sync` deploys the current remote branch again.

For automatic rollback, Gitizen validates staging scripts first, swaps the directory,
reloads Denizen, checks Denizen's error flag, and restores the old directory and Git
HEAD when validation fails.

<a id="en-notifications"></a>

## Discord and Telegram

No separate hosting is required. Gitizen sends HTTPS requests directly from the
Minecraft server. Your provider must allow outbound connections.

Discord environment variable:

```text
GITIZEN_DISCORD_WEBHOOK=https://discord.com/api/webhooks/...
```

```yaml
notifications:
  discord:
    webhook-url-env: "GITIZEN_DISCORD_WEBHOOK"
    webhook-url: ""
```

Telegram environment variables:

```text
GITIZEN_TELEGRAM_BOT_TOKEN=123456:ABC...
GITIZEN_TELEGRAM_CHAT_ID=-1001234567890
```

```yaml
notifications:
  telegram:
    bot-token-env: "GITIZEN_TELEGRAM_BOT_TOKEN"
    chat-id-env: "GITIZEN_TELEGRAM_CHAT_ID"
    bot-token: ""
    chat-id: ""
```

For Telegram, create a bot with `@BotFather`, send it a message, determine the
target chat ID, and add the bot to the required group or channel.

<a id="en-security"></a>

## Security

```yaml
security:
  require-https: true
  allowed-hosts:
    - "github.com"

validation:
  require-dsc-files: true
  max-script-size-kb: 1024

deployment:
  max-history: 20
  max-changes-in-chat: 30
```

- Keep HTTPS enforcement enabled.
- Restrict `allowed-hosts` to trusted Git servers.
- Use a read-only, repository-scoped token.
- Grant repository write access only to trusted developers.
- Keep secrets in hosting environment variables when possible.

Add the hostname explicitly when using GitHub Enterprise.

<a id="en-troubleshooting"></a>

## Troubleshooting

### Branch not found in origin

Check the exact branch name, capitalization, and whether it exists remotely.

### Host is not in security.allowed-hosts

Add the trusted Git server hostname to `security.allowed-hosts`.

### GitHub authentication fails

Verify that the URL uses HTTPS, the token is not expired, it has Contents: Read
permission, any organization SSO requirement is satisfied, and the environment
variable reaches the Java process.

### Target directory does not match Denizen's active directory

A profile with `reload-denizen: true` must target Denizen's actual script folder.
For a default Denizen installation, use `target-directory: "scripts"`.

### Deployment fails before activation

Check Gitizen's message and the server console for an empty or invalid `.dsc`,
oversized file, symbolic link, or missing Denizen scripts.

### Notifications do not arrive

Confirm credentials and make sure the hosting provider allows outbound HTTPS access
to `discord.com` or `api.telegram.org`.

### Migrating an old configuration

Legacy `repo-url`, `github-token`, and `branch` keys are read as the
`production` profile. To use all new options, back up the old file, regenerate
`config.yml`, and copy the repository, branch, and token into the new structure.

## Data files

| Path | Purpose |
|---|---|
| `plugins/Gitizen/config.yml` | User configuration |
| `plugins/Gitizen/state.yml` | Deployment history and metrics |
| `plugins/Gitizen/repositories/<profile>` | Internal Git checkouts |
| `plugins/Denizen/scripts` | Active production scripts |

Do not manually edit internal checkouts. Manual changes in an active target are
detected and replaced during the next sync.

## Building from source

```shell
mvn clean verify
```

The integration tests create local Git repositories and verify branch selection,
origin URL changes, path containment, change detection, and manual target drift.

## License

MIT License. Copyright (c) 2026 Bloodulon.
