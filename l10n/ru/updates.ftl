updates-checking = Проверяем…
updates-check-failed = Не удалось проверить обновления
updates-none = Обновлений нет
updates-engine-confirm = Доступен Gecko { $version } из источника «{ $source }», { $size } МБ. Скачать?
updates-downloading = Скачиваем…
updates-downloading-percent = Скачиваем… { $percent }%
updates-rollback-confirm = Вернуть Gecko { $previous }? Gecko { $active } больше не будет предлагаться. Браузер перезапустится.
updates-rollback-action = Вернуть
updates-restart = Перезапустить браузер
updates-restart-summary = Скачан Gecko { $version }, начнёт работать после перезапуска
updates-shell = Обновить { -brand }
updates-shell-summary = Доступна сборка { $version }. Система попросит подтвердить установку
updates-never-checked = Ещё не проверялось
updates-last-check = Последняя проверка: { $time }
updates-last-available = , доступен Gecko { $version }
updates-last-shell = , доступна новая версия { -brand }
updates-last-error = , ошибка: { $reason }
updates-last-none = , обновлений нет
updates-check-now = Проверить сейчас
updates-how = Как обновлять
updates-auto = Проверять автоматически
updates-how-often = Как часто
updates-install = Установка
updates-wifi-only = Скачивать только по Wi-Fi
updates-wifi-only-summary = Около 95 МБ за обновление
updates-source = Источник обновлений
updates-sources = Источники
updates-installed = Установлено
updates-shell-build = Сборка { $code }
updates-running = Работает{ $origin }
updates-after-restart = После перезапуска{ $origin }
updates-previous = Предыдущий{ $origin }. Нажмите, чтобы вернуть его
updates-rollback-note = Если новый движок { $count } раза подряд не запустится, браузер сам вернёт предыдущий
updates-debug = Отладка
updates-debug-mode = Режим отладки
updates-debug-mode-summary = Установка движка и { -brand } с любого адреса, в том числе http, без проверки подписи
updates-debug-warning = Без проверки подписи поставить можно любой код. Он будет работать внутри браузера с доступом ко всему, включая пароли. Включайте только для своих сборок.
updates-debug-enable = Включить
updates-debug-enable-auth = Включить режим отладки
updates-debug-engine = Движок Gecko { $version }, { $size } МБ
updates-debug-shell-downloading = Скачиваем { -brand }…
updates-debug-shell-downloading-percent = Скачиваем { -brand }… { $percent }%
updates-debug-engine-downloading-percent = Скачиваем движок… { $percent }%
updates-debug-nothing = По этому адресу нет ни движка для этой версии, ни сборки { -brand }
updates-debug-install = Установить
updates-debug-from-address = Установить с адреса
updates-debug-from-address-summary = Адрес engines.json, например http://192.168.1.5:8790/engines.json
updates-debug-index-address = Адрес engines.json
updates-debug-check = Проверить
updates-debug-failed = Не удалось: { $reason }
updates-check-off = проверка выключена
updates-check-interval = проверка { $interval }
updates-source-confirm =
    Обновления движка и { -brand } будут приходить только из «{ $source }».

    Отпечаток ключа SHA-256:
    { $fingerprint }
updates-source-change = Сменить
sources-add = Добавить источник
sources-add-warning = Источник сможет устанавливать код, который выполняется в браузере
sources-fingerprint-confirm =
    Отпечаток ключа SHA-256:
    { $fingerprint }

    Сверьте его с отпечатком, который опубликовал издатель. Добавлять источник?
sources-note = Источник - адрес списка движков и ключ его издателя. Движок ставится, только если список подписан этим ключом. Обновления приходят только из источника обновлений, остальные источники можно проверить вручную на их странице
sources-disabled = Выключен
