# Локальный сервер телеметрии BMS

Node.js-сервер принимает телеметрию Android-приложения и сохраняет её в SQLite. Нужен Node.js 24 или новее.

## Установка и запуск

Откройте PowerShell в каталоге `server`:

```powershell
npm install
$env:API_KEY = "придумайте_длинный_секретный_ключ"
npm start
```

По умолчанию сервер слушает `0.0.0.0:3000`, а база создаётся в `server/data/bms.sqlite`. Дополнительно можно задать переменные `HOST`, `PORT` и `DB_PATH`. Значение `API_KEY` по умолчанию предназначено только для первого локального запуска — для постоянной работы обязательно задайте свой ключ.

## Web-админка

После запуска откройте админку в браузере:

```text
http://localhost:3000/
```

Для входа используйте тот же `API_KEY`, который задан для сервера. При первом локальном запуске форма подставляет значение по умолчанию `change_me_api_key_2026`; замените его, если вы задали свой ключ.

Если сервер уже был запущен до обновления файлов админки, перезапустите его в PowerShell: остановите текущий процесс через `Ctrl+C`, затем снова выполните `npm.cmd start` в каталоге `server`.

## Доступ из локальной сети Windows

Узнайте IPv4-адрес компьютера командой `ipconfig`. Например, при адресе `192.168.1.50` сервер будет доступен в локальной сети по URL:

```text
http://192.168.1.50:3000
```

Если Windows Defender Firewall блокирует подключение, запустите PowerShell от имени администратора и разрешите входящие TCP-подключения к порту:

```powershell
New-NetFirewallRule -DisplayName "BMS Local Server" -Direction Inbound -Protocol TCP -LocalPort 3000 -Action Allow -Profile Private
```

Открывайте порт только в доверенной частной сети. На следующем этапе Android-приложение будет переключено с текущего адреса сервера на IP-адрес этого компьютера.

## Проверка

Проверка состояния без ключа:

```powershell
Invoke-RestMethod http://localhost:3000/health
```

Отправка минимального тестового пакета (ключ можно передать в JSON):

```powershell
$body = @{
  api_key = $env:API_KEY
  bms_uid = "test-bms-001"
  voltage = 52.4
  current = -8.7
  soc = 76.5
  cells = @{ "1" = 3.275; "2" = 3.281 }
  temps = @{ "1" = 23; "2" = 27 }
  errors = @()
  raw = @{}
  events = @()
} | ConvertTo-Json -Depth 10

Invoke-RestMethod -Method Post `
  -Uri http://localhost:3000/api/v1/telemetry `
  -ContentType "application/json" `
  -Body $body
```

Получение списка батарей и последних записей:

```powershell
$headers = @{ "x-api-key" = $env:API_KEY }
Invoke-RestMethod -Headers $headers http://localhost:3000/api/v1/batteries
Invoke-RestMethod -Headers $headers "http://localhost:3000/api/v1/batteries/test-bms-001/telemetry?limit=10"
```

## API

- `GET /health` — состояние сервера, авторизация не нужна.
- `POST /api/v1/telemetry` — приём телеметрии; ключ в поле `api_key` JSON или заголовке `x-api-key`.
- `POST /api/upload.php` — совместимый адрес приёма для текущей Android-сборки.
- `GET /api/v1/batteries` — список батарей; ключ только в заголовке `x-api-key`.
- `GET /api/v1/batteries/:bmsUid/telemetry?limit=100` — история батареи; `limit` от 1 до 1000, ключ в `x-api-key`.

Ошибки и ответы API возвращаются в JSON. Успешная запись отвечает `{"ok":true,"log_id":N}`.

## Тесты

```powershell
npm test
```
