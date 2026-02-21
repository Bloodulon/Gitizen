Gitizen

Gitizen — это системный плагин для серверов Minecraft (Paper/Spigot), предназначенный для автоматизации деплоя скриптов Denizen напрямую из репозиториев GitHub.
Russian (Русский)
Описание

Плагин обеспечивает связь между вашей средой разработки на GitHub и рабочим сервером. Он устраняет необходимость использования ручных методов передачи файлов (таких как FTP или SFTP), позволяя обновлять логику сервера одной командой.
Основные функции

    Принудительная синхронизация: Использование стратегии Fetch и Hard Reset гарантирует, что локальные файлы на сервере будут идентичны состоянию репозитория, исключая ошибки слияния (merge conflicts).

    Логирование изменений: Система сравнивает хеши коммитов и выводит список модифицированных файлов с маркерами статуса: [ADD] (добавлен), [MODIFY] (изменен), [DELETE] (удален).

    Автоматизация команд: После завершения загрузки файлов плагин инициирует внутреннюю команду перезагрузки скриптов Denizen.

    Безопасность: Поддержка аутентификации через Personal Access Tokens (PAT) позволяет работать с приватными репозиториями без передачи пароля от аккаунта.

Команды и права доступа
Команда	Описание	Право (Permission)
/gitizen setup [url] [token]	Конфигурация репозитория и токена доступа.	gitizen.admin
/gitizen sync	Запуск процесса синхронизации.	gitizen.sync
/gitizen list	Вывод списка всех файлов .dsc в папке скриптов.	gitizen.admin
Порядок установки

    Разместите JAR-файл в папку plugins и запустите сервер.

    Сгенерируйте токен доступа в настройках GitHub (Developer Settings > Personal access tokens).

    Выполните команду настройки: /gitizen setup <url> <token> или же зайдите в plugins\Gitizen\config.yml и настройте в ручную (РЕКОМЕНДОВАНО)

    Выполните /gitizen sync для первичной загрузки данных.

English
Description

Gitizen is a utility plugin for Minecraft servers that synchronizes the Denizen scripts directory with a remote GitHub repository. It streamlines the development workflow by removing the need for manual file uploads.
Features

    Conflict-Free Sync: By employing Fetch and Hard Reset operations, the plugin ensures the server's script folder strictly matches the remote repository, bypassing any potential local conflicts.

    Change Tracking: During synchronization, the plugin analyzes the difference between commits and reports all changes using status markers: [ADD], [MODIFY], and [DELETE].

    Execution Hooks: Automatically triggers the Denizen script reload process upon successful synchronization.

    Private Repository Support: Securely connects to GitHub using Personal Access Tokens (PAT).

Commands and Permissions
Command	Description	Permission
/gitizen setup [url] [token]	Configures the repository URL and access token.	gitizen.admin
/gitizen sync	Triggers the synchronization process.	gitizen.sync
/gitizen list	Displays a list of all current .dsc files.	gitizen.admin
Installation

    Install the plugin and restart the server.

    Create a GitHub Personal Access Token with the 'repo' scope.

    Use the command: /gitizen setup <url> <token> to link your repository OR go to plugins\Gitizen\config.yml and configure manually (RECOMMENDED)

    Run /gitizen sync to deploy your scripts.
