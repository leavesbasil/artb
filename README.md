# Подбор рекламы в диалоге с агентом — прототип

Прототип по [дизайн-доку](docs/design.md): на каждом ходе диалога клиента с агентом банка подбирается одна кампания собственного продукта или осознанно не показывается ничего. Подбор идёт параллельно ответу агента; модель агента о рекламе не знает.

Ключевые моменты — в [презентации](https://claude.ai/artifact/LpsfwUwgECJeBnBrGPRFnu).

Стек: Java 21, Spring Boot 4.1, Spring AI 2.0, Postgres 17 + pgvector, Gradle 9.

## Модули

| Модуль | Что внутри |
| --- | --- |
| `ads-llm` | Назначение моделей этапам, `DefaultLlmGateway` (структурированный ответ, повтор при ошибке схемы), клиент GigaChat API и OpenAI-совместимого сервера, запись и воспроизведение ответов, маскирование персональных данных, эмбеддинги |
| `ads-campaign` | Каталог: кампании, креативы, интенты, таксономия тем; LLM-обогащение, одобрение, валидация креативов (ст. 28 ФЗ «О рекламе»); векторный индекс на pgvector; синтетический каталог; админ-API |
| `ads-targeting` | Чёрный список бинарных сегментов (`SegmentFilter`), мок сегментов клиента, eligible set на сессию |
| `ads-ranking` | `CandidateRanker` и прототипная реализация: порог τ, затем relevance × приоритет |
| `ads-matching` | Подбор на ходе: гейт → интент и темы → retrieval → LLM-судья → ранжирование; кэш сессии, дедлайн |
| `ads-delivery` | Офферы с подписанным токеном, события от поверхности, лимиты частоты, истечение |
| `ads-eval` | Golden set (32 диалога) и прогон с отчётом по метрикам |
| `ads-demo` | Spring Boot приложение: агент, чат, рекламный слот, отладочная панель |
| `llm-cli-proxy` | Отдельное приложение: OpenAI-совместимый API (`/v1/chat/completions`, `/v1/models`) поверх Claude Code CLI и Qwen Code CLI. Сейчас не используется, оставлен для отладки без GigaChat |

## Модели: GigaChat API

Все этапы и эмбеддинги идут в GigaChat API напрямую, через библиотеку [spring-ai-gigachat](https://github.com/ai-forever/spring-ai-gigachat) (`chat.giga:spring-ai-gigachat`). Библиотека получает OAuth-токен по ключу авторизации и обновляет его до истечения (токен живёт 30 минут), проверяет TLS по корневому сертификату Минцифры, умеет вход по клиентскому сертификату.

```
ads-demo / ads-eval ──► GigaChatClients ──OAuth──► ngw.devices.sberbank.ru:9443
                                         └──HTTPS──► api.giga.chat/v1: chat/completions, embeddings
```

| Этап | Модель по умолчанию |
| --- | --- |
| Гейт, интент | `GigaChat-2` |
| Судья, обогащение кампаний, агент демо | `GigaChat-2-Max` |
| Эмбеддинги | `EmbeddingsGigaR` (вектор 2560) |

JSON-схему GigaChat API в запросе не принимает: шлюз добавляет её инструкцией в промпт, проверяет ответ и при ошибке один раз повторяет. Маскирование персональных данных включено всегда.

Для отладки — публичный API с ключом физлица (`GIGACHAT_API_PERS`) и только синтетические данные. В контуре меняются адреса (`GIGACHAT_BASE_URL`, `GIGACHAT_AUTH_URL`), scope и способ входа; код тот же. Провайдер `internal` (любой OpenAI-совместимый сервер: gpt2giga, vLLM, `llm-cli-proxy`) остался в коде и подключается конфигом.

## Настройка GigaChat API

### 1. Получите ключ авторизации

В [личном кабинете GigaChat API](https://developers.sber.ru/studio) создайте проект GigaChat API и скопируйте **ключ авторизации** (Authorization key — Base64 от `client_id:client_secret`). Для физлица scope — `GIGACHAT_API_PERS`, для юрлица — `GIGACHAT_API_B2B` или `GIGACHAT_API_CORP`.

### 2. Скачайте корневой сертификат Минцифры

Сертификаты GigaChat выпущены российским УЦ, которому JVM по умолчанию не доверяет. Скачайте [russian_trusted_root_ca.cer](https://gu-st.ru/content/Other/doc/russian_trusted_root_ca.cer) и сохраните, например, в `~/certs/`.

### 3. Заполните `.env`

```bash
cp example.env .env
```

```properties
GIGACHAT_CREDENTIALS=<ключ авторизации>
GIGACHAT_SCOPE=GIGACHAT_API_PERS
GIGACHAT_CA_CERTS=/Users/<вы>/certs/russian_trusted_root_ca.cer
```

`.env` в `.gitignore`. Его читают демо (`spring.config.import`) и прогон golden set; переменные окружения с теми же именами имеют приоритет.

### 4. Проверьте ключ

```bash
set -a; source .env; set +a
```

```bash
curl -s --cacert "$GIGACHAT_CA_CERTS" https://ngw.devices.sberbank.ru:9443/api/v2/oauth -H "Authorization: Basic $GIGACHAT_CREDENTIALS" -H "RqUID: $(uuidgen)" -H 'Content-Type: application/x-www-form-urlencoded' -d "scope=$GIGACHAT_SCOPE"
```

В ответе — `access_token` и `expires_at`.

### 5. Настройки

| Переменная | По умолчанию | Что задаёт |
| --- | --- | --- |
| `GIGACHAT_CREDENTIALS` | — | Ключ авторизации |
| `GIGACHAT_SCOPE` | `GIGACHAT_API_PERS` | Scope доступа |
| `GIGACHAT_CA_CERTS` | — | Корневой сертификат (PEM) |
| `GIGACHAT_CERTIFICATE`, `GIGACHAT_PRIVATE_KEY` | — | Вход по клиентскому сертификату вместо ключа |
| `GIGACHAT_BASE_URL` | `https://api.giga.chat/v1/` | Адрес API (в контуре — свой) |
| `GIGACHAT_AUTH_URL` | `https://ngw.devices.sberbank.ru:9443/api/v2/oauth` | Адрес OAuth |
| `ADS_LLM_FAST_MODEL` | `GigaChat-2` | Модель гейта и интента |
| `ADS_LLM_STRONG_MODEL` | `GigaChat-2-Max` | Модель судьи, обогащения и агента |
| `ADS_EMBEDDING_PROVIDER` | `gigachat` | `hashing` — локальные лексические эмбеддинги, если на эмбеддинги GigaChat нет пакета токенов |
| `ADS_EMBEDDING_MODEL` | `EmbeddingsGigaR` | Модель эмбеддингов; `Embeddings` — дешевле, вектор 1024 |
| `GIGACHAT_MAX_CONCURRENCY` | `1` | Одновременных запросов к API, не больше; у ключа физлица — 1, в контуре — больше |
| `ADS_MATCHING_DEADLINE` | `2500ms` | Дедлайн подбора на ходе (`ads.delivery.default-deadline`); на ключе физлица мало — см. «Неполадки» |

Таймауты и повторы — в `ads.llm.gigachat.*`: `read-timeout: 60s`, `max-retries: 3` — повтор при сетевых ошибках, 5xx и 429 с паузой 0,5, 1, 2 с. Ключ авторизации можно писать в кавычках и с префиксом `Basic` — они снимаются; значение с двоеточием (`client_id:ключ`) отклоняется до запроса токена.

### Неполадки

| Симптом | Причина и что делать |
| --- | --- |
| Демо не стартует: «не задан ключ авторизации» | Нет `GIGACHAT_CREDENTIALS`: заполните `.env` (шаг 3) |
| `PKIX path building failed` | JVM не доверяет сертификату: укажите `GIGACHAT_CA_CERTS` (шаг 2) |
| 401 от OAuth | Неверный ключ или scope не совпадает с проектом в личном кабинете |
| 402 Payment Required | Нет пакета токенов на модель. Чаще всего — на эмбеддинги: у ключа физлица они платные. Купите пакет или запустите с `ADS_EMBEDDING_PROVIDER=hashing` (golden set — `-Pembedding=hashing`) |
| 404 / «model not found» | Модель недоступна для вашего scope: сверьте имена с `GET /models` |
| 429 | Превышен лимит одновременных запросов. Клиент ставит запросы в очередь (`GIGACHAT_MAX_CONCURRENCY`, по умолчанию 1) и повторяет 429; golden set на GigaChat идёт в один поток |
| Подбор всегда `TIMEOUT` | На ключе физлица ход идёт 10–25 с: агент и подбор стоят в одной очереди. Для демо поднимите `ADS_MATCHING_DEADLINE`, например `30s` |
| 400 «Can't decode 'Authorization' header» | В `GIGACHAT_CREDENTIALS` не ключ авторизации: нужен Base64 от `client_id:client_secret` из личного кабинета |
| Подбор всегда «без рекламы», `NO_CANDIDATES` | Каталог проиндексирован другой моделью эмбеддингов — пересоздайте базу (раздел «Запуск демо») |

## Запуск демо

Postgres с pgvector:

```bash
docker compose up -d
```

Демо на GigaChat (ключ — в `.env`, раздел «Настройка GigaChat API»). При первом старте в пустую базу заливаются 17 базовых синтетических продуктов, их интенты индексируются EmbeddingsGigaR. Без пакета токенов на эмбеддинги — `ADS_EMBEDDING_PROVIDER=hashing`; на ключе физлица — ещё `ADS_MATCHING_DEADLINE=30s`:

```bash
./gradlew :ads-demo:bootRun
```

Откройте http://localhost:8080. Слева — чат и рекламный слот, справа — решения по ходам: интент, темы, кандидаты, оценки судьи, задержки.

Векторы привязаны к модели эмбеддингов, а каталог заливается только в пустую базу. Если база заполнялась с другой моделью (например, в профиле `claude` с хэширующими эмбеддингами), пересоздайте её:

```bash
docker compose down -v && docker compose up -d
```

Профили:

- без профиля — GigaChat API на всех этапах, эмбеддинги EmbeddingsGigaR, маскирование включено;
- `claude`, `local` — через `llm-cli-proxy` (сейчас не используются), хэширующие эмбеддинги, 1000 синтетических кампаний, дедлайн выключен.

С реальным каталогом синтетику отключает `ADS_SYNTHETIC_COUNT=0`.

## llm-cli-proxy (сейчас не используется)

Основной путь — GigaChat API, прокси оставлен как запасной вариант для отладки без ключа GigaChat. Приложение подключается к нему как к OpenAI-совместимому серверу (`provider: internal`) в профилях `claude` и `local`; эмбеддинги там хэширующие.

Прокси вызывает CLI от имени пользователя машины и ходит к внешним моделям, поэтому — только локально и только с синтетическими данными.

```
ads-demo / ads-eval ──OpenAI API──► llm-cli-proxy :8090 ──► claude -p … / qwen -p …
```

Модель выбирается именем: `claude/haiku`, `claude/sonnet`, `qwen`, `qwen/<модель>` или псевдоним из `proxy.aliases` (`fast`, `strong`). JSON-схема из `response_format: json_schema` уходит в `claude --json-schema`; для qwen прокси добавляет её в промпт и вырезает JSON из ответа. Прокси слушает только `127.0.0.1`, при необходимости — с ключом (`PROXY_API_KEY`). Потоковые ответы и инструменты не поддерживаются.

### 1. Установите и авторизуйте CLI

Claude Code (нужен для профиля `claude` и golden set):

```bash
npm install -g @anthropic-ai/claude-code
```

Один раз войдите интерактивно: запустите `claude` и выполните вход. Проверка без интерактива:

```bash
claude -p "ответь одним словом: работает?" --output-format json
```

Qwen Code (нужен для профиля `local`):

```bash
npm install -g @qwen-code/qwen-code
```

Один раз запустите `qwen` интерактивно и авторизуйтесь — в headless-режиме `/auth` не работает. Отключите инструменты агента в настройках qwen (`~/.qwen/settings.json`; имя ключа зависит от версии — сверьтесь с документацией Qwen Code). Флаг `--yolo` прокси не использует.

### 2. Запустите прокси

```bash
./gradlew :llm-cli-proxy:bootRun
```

Или собранным jar:

```bash
./gradlew :llm-cli-proxy:bootJar
```

```bash
java -jar llm-cli-proxy/build/libs/llm-cli-proxy-0.1.0-SNAPSHOT.jar
```

Прокси сам убирает переменные `CLAUDECODE*`, поэтому его можно запускать и из терминала Claude Code.

### 3. Проверьте

```bash
curl -s http://127.0.0.1:8090/v1/models
```

```bash
curl -s http://127.0.0.1:8090/v1/chat/completions -H 'Content-Type: application/json' -d '{"model":"claude/haiku","messages":[{"role":"user","content":"Скажи «работает»"}]}'
```

Обычный ответ — 4–15 секунд: каждый вызов запускает отдельный процесс CLI.

### 4. Настройки

Задаются в `llm-cli-proxy/src/main/resources/application.yml`, переменными окружения или аргументами запуска, например `./gradlew :llm-cli-proxy:bootRun --args='--proxy.timeout=90s --proxy.aliases.strong=claude/opus'`.

| Свойство | По умолчанию | Что задаёт |
| --- | --- | --- |
| `server.port` (`PROXY_PORT`) | `8090` | Порт |
| `server.address` | `127.0.0.1` | Интерфейс; наружу прокси не открывать |
| `proxy.api-key` (`PROXY_API_KEY`) | пусто | Если задан — запросы должны нести `Authorization: Bearer <ключ>` |
| `proxy.timeout` | `60s` | Таймаут одной попытки; при зависании CLI — один повтор |
| `proxy.max-concurrency` | `4` | Сколько процессов одного CLI работает одновременно |
| `proxy.claude.command` | `claude` | Путь к Claude Code CLI |
| `proxy.claude.max-budget-usd` | `0.5` | Предохранитель стоимости одного вызова |
| `proxy.qwen.command` | `qwen` | Путь к Qwen Code CLI |
| `proxy.aliases.<имя>` | `fast: claude/haiku`, `strong: claude/sonnet` | Псевдонимы моделей |

### 5. Подключите приложение

Перед переключением с GigaChat пересоздайте базу (раздел «Запуск демо»): векторы привязаны к модели эмбеддингов. Профили `claude` и `local` уже смотрят на `http://localhost:8090/v1`, с таймаутом 180 с и без повторов в клиенте: зависший вызов повторяет прокси, а лишний вызов CLI дорог. Если прокси на другом адресе или с ключом:

```bash
ADS_LLM_BASE_URL=http://127.0.0.1:9090/v1 ADS_LLM_API_KEY=секрет ./gradlew :ads-demo:bootRun --args='--spring.profiles.active=claude'
```

Модели этапов меняются в `ads-demo/src/main/resources/application.yml` (`ads.llm.default-stage`, `ads.llm.stages.*`) — подойдёт любое имя, которое понимает прокси. Прокси работает и с любым другим OpenAI-совместимым клиентом.

### Неполадки

| Симптом | Причина и что делать |
| --- | --- |
| 400 «неизвестная модель» | Имя не `claude/…`, не `qwen…` и не псевдоним из `proxy.aliases` |
| 400 «stream не поддерживается» / «tools не поддерживаются» | Клиент просит потоковый ответ или инструменты — прокси их не умеет |
| 502 «вывод не JSON» или «CLI завершился с кодом 1» | CLI не авторизован или не найден: проверьте команду из шага 1 |
| 502 `error_max_budget_usd` | Вызов дороже предохранителя: увеличьте `proxy.claude.max-budget-usd` |
| 504 «CLI не ответил» | CLI завис дважды подряд: проверьте вызов вручную и сеть |
| Приложение падает по таймауту раньше прокси | `ads.llm.internal.timeout` должен быть больше двух попыток прокси (по умолчанию 180 с против 2 × 60 с) |

## Тесты

```bash
./gradlew test
```

Интеграционные тесты (`*IT`) поднимают Postgres с pgvector через Testcontainers — нужен Docker. Провайдер GigaChat проверяется на фейковом сервере (OAuth, чат, эмбеддинги); ключ для тестов не нужен. Совместимость прокси проверяется настоящим клиентом Spring AI OpenAI. Живой вызов Claude CLI:

```bash
./gradlew :llm-cli-proxy:test -PllmCli
```

## Golden set

На GigaChat (ключ — в `.env`):

```bash
./gradlew :ads-eval:eval
```

Модели и эмбеддинги меняются параметрами: `-Pmodel=GigaChat-2-Pro -PjudgeModel=GigaChat-2-Max -Pembedding=Embeddings`. Ответы моделей пишутся в `ads-eval/llm-cassettes`: повторный прогон с теми же промптами не тратит токены чата (`-Precording=replay` — без обращения к модели; эмбеддинги в кассеты не пишутся). Отчёт — `ads-eval/build/eval/report.md`.

Прогон на GigaChat 11.11.2026 — GigaChat-2 на гейте и интенте, GigaChat-2-Max на судье, хэширующие эмбеддинги (на EmbeddingsGigaR не было пакета токенов), ключ физлица, один поток:

| Метрика | GigaChat | Claude haiku |
| --- | --- | --- |
| Кейсов пройдено | 27 из 32 | 31 из 32 |
| precision@1 | 13/14 | 17/17 |
| Показ там, где реклама ожидалась | 14/18 | 17/18 |
| «Без рекламы», где не нужна | 12/12 | 12/12 |
| Показы на стоп-темах | 0 | 0 |
| Задержка подбора p50 / p95 | 4,3 / 24,8 с | 15 / 20,5 с (через CLI) |

Провалы GigaChat: на явных вопросах о зарплатной, пенсионной и детской карте интент ставит коммерческий интерес 0 (`LOW_INTENT`) — понимает его как намерение купить, а не как потребность в продукте банка; «счёт для ИП» не попал в кандидаты на хэширующих эмбеддингах; на автокредите судья выбрал кредит наличными. Все стоп-темы гейт отсёк. Прогон на Claude haiku: `-Pprovider=internal -Pmodel=claude/haiku` через `llm-cli-proxy`.

## API

| Метод | Путь | Назначение |
| --- | --- | --- |
| POST | `/ads/sessions` | Начало сессии: `sessionId`, `clientId` → eligible set |
| POST | `/ads/match` | Подбор на ходе → `AdOffer` или 204 |
| POST | `/ads/events` | `IMPRESSION`, `CLICK`, `QUESTION_CLICK`, `DISMISS` от поверхности |
| POST | `/admin/campaigns` | Черновик кампании + LLM-обогащение |
| POST | `/admin/campaigns/{id}/approve` | Одобрение, эмбеддинги, ACTIVE |
| GET | `/debug/offers?sessionId=` | Решения по ходам со всеми кандидатами |

## Известные ограничения прототипа

- На GigaChat golden set — 27 из 32 против 31 из 32 на Claude haiku: промпты и пороги подбирались под haiku. Нужна доводка промпта интента (коммерческий интерес к продуктам повседневного банкинга) и агента (после клика GigaChat не называет условия из дисклеймера, хотя и не выдумывает их).
- EmbeddingsGigaR не проверены: на ключе физлица не было пакета токенов (402). Пороги `min-similarity` (0.2), `negative-margin` (0.1), `cache-query-similarity` (0.9) подобраны на хэширующих эмбеддингах и требуют перекалибровки — у смысловых эмбеддингов сходство разных запросов выше, и кэш сессии может срабатывать лишний раз.
- На ключе физлица GigaChat пропускает один запрос за раз: ход в демо занимает 10–25 с, дедлайн 2,5 с не выдерживается. В контуре лимит и дедлайн нужно замерить заново.
- Векторы привязаны к модели эмбеддингов (`embedding_model`); отдельной команды переиндексации нет — индекс строится при одобрении и импорте кампании. При смене модели базу с синтетикой пересоздают.
- Маскирование — регулярными выражениями; ФИО не ловятся, нужен NER-сервис.
- В синтетическом каталоге варианты одного продукта почти одинаковы, поэтому топ-20 кандидатов часто занимает один продукт.
- Конверсии (действия в сценарии) не принимаются: в бою они приходят фоновыми потоками.
