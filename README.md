# Подбор рекламы в диалоге с агентом — прототип

Прототип по дизайн-доку: на каждом ходе диалога клиента с агентом банка подбирается одна кампания собственного продукта или осознанно не показывается ничего. Подбор идёт параллельно ответу агента; модель агента о рекламе не знает.

Стек: Java 21, Spring Boot 4.1, Spring AI 2.0, Postgres 17 + pgvector, Gradle 9.

## Модули

| Модуль | Что внутри |
| --- | --- |
| `ads-llm` | Назначение моделей этапам, `DefaultLlmGateway` (структурированный ответ, повтор при ошибке схемы), клиент OpenAI-совместимого сервера, запись и воспроизведение ответов, маскирование персональных данных, эмбеддинги |
| `ads-campaign` | Каталог: кампании, креативы, интенты, таксономия тем; LLM-обогащение, одобрение, валидация креативов (ст. 28 ФЗ «О рекламе»); векторный индекс на pgvector; синтетический каталог; админ-API |
| `ads-targeting` | Чёрный список бинарных сегментов (`SegmentFilter`), мок сегментов клиента, eligible set на сессию |
| `ads-ranking` | `CandidateRanker` и прототипная реализация: порог τ, затем relevance × приоритет |
| `ads-matching` | Подбор на ходе: гейт → интент и темы → retrieval → LLM-судья → ранжирование; кэш сессии, дедлайн |
| `ads-delivery` | Офферы с подписанным токеном, события от поверхности, лимиты частоты, истечение |
| `ads-eval` | Golden set (32 диалога) и прогон с отчётом по метрикам |
| `ads-demo` | Spring Boot приложение: агент, чат, рекламный слот, отладочная панель |
| `llm-cli-proxy` | Отдельное приложение: OpenAI-совместимый API (`/v1/chat/completions`, `/v1/models`) поверх Claude Code CLI и Qwen Code CLI. Основное приложение о CLI не знает |

## Модели: прод и локальная отладка

Приложение знает один протокол — OpenAI-совместимый (`provider: internal`). В проде это GigaChat внутри контура через официальный прокси Сбера [gpt2giga](https://developers.sber.ru/docs/ru/gigachain/tools/utilities/gpttogiga-proxy-server). Для отладки на синтетических данных тот же протокол отдаёт `llm-cli-proxy`:

```
прод:     ads-demo ──OpenAI API──► gpt2giga ──► GigaChat внутри контура
отладка:  ads-demo / ads-eval ──OpenAI API──► llm-cli-proxy :8090 ──► claude -p … / qwen -p …
```

Модель выбирается именем: `claude/haiku`, `claude/sonnet`, `qwen`, `qwen/<модель>` или псевдоним из `proxy.aliases` (`fast`, `strong`). JSON-схема из `response_format: json_schema` уходит в `claude --json-schema`; для qwen прокси добавляет её в промпт и вырезает JSON из ответа. Прокси слушает только `127.0.0.1`, при необходимости — с ключом (`PROXY_API_KEY`). Потоковые ответы и инструменты не поддерживаются.

## Настройка llm-cli-proxy

Прокси вызывает CLI от имени пользователя машины и ходит к внешним моделям, поэтому — только локально и только с синтетическими данными.

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

Профили `claude` и `local` уже смотрят на `http://localhost:8090/v1`, с таймаутом 180 с и без повторов в клиенте: зависший вызов повторяет прокси, а лишний вызов CLI дорог. Если прокси на другом адресе или с ключом:

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

## Запуск демо

Postgres с pgvector:

```bash
docker compose up -d
```

Прокси к CLI (установка и проверка — в разделе «Настройка llm-cli-proxy»):

```bash
./gradlew :llm-cli-proxy:bootRun
```

Демо, каталог из 1000 синтетических кампаний заливается при первом старте:

```bash
./gradlew :ads-demo:bootRun --args='--spring.profiles.active=claude'
```

Откройте http://localhost:8080. Слева — чат и рекламный слот, справа — решения по ходам: интент, темы, кандидаты, оценки судьи, задержки.

Профили:

- без профиля — прод-конфигурация: GigaChat через gpt2giga, маскирование включено, JSON-схема — инструкцией в промпте (`native-schema: false`), эмбеддинги — EmbeddingsGigaR;
- `claude` — все этапы через llm-cli-proxy на Claude (судья — `claude/sonnet`, остальное — `claude/haiku`), дедлайн выключен;
- `local` — все этапы через llm-cli-proxy на Qwen.

Профили через прокси — только для синтетических данных.

Прод-конфигурация задаётся переменными окружения:

| Переменная | По умолчанию | Что задаёт |
| --- | --- | --- |
| `ADS_LLM_BASE_URL` | `http://localhost:8090/v1` | Адрес gpt2giga; 8090 — его порт по умолчанию, совпадает с llm-cli-proxy |
| `ADS_LLM_API_KEY` | `none` | Ключ доступа к gpt2giga (не ключ GigaChat — тот хранится в gpt2giga) |
| `ADS_LLM_FAST_MODEL` | `GigaChat-2-Lite` | Модель гейта и интента |
| `ADS_LLM_STRONG_MODEL` | `GigaChat-2-Max` | Модель судьи, обогащения и агента |
| `ADS_EMBEDDING_MODEL` | `EmbeddingsGigaR` | Модель эмбеддингов |

ID моделей зависят от поставки GigaChat в контуре — сверьте их с `GET /v1/models` у gpt2giga.

## Тесты

```bash
./gradlew test
```

Интеграционные тесты (`*IT`) поднимают Postgres с pgvector через Testcontainers — нужен Docker. Совместимость прокси проверяется настоящим клиентом Spring AI OpenAI. Живой вызов Claude CLI:

```bash
./gradlew :llm-cli-proxy:test -PllmCli
```

## Golden set

При запущенном `llm-cli-proxy`:

```bash
./gradlew :ads-eval:eval -Pmodel=claude/haiku -PjudgeModel=claude/haiku -Precording=record
```

Ответы моделей пишутся в `ads-eval/llm-cassettes`: повторный прогон с теми же промптами бесплатный и детерминированный (`-Precording=replay` — без обращения к модели). Отчёт — `ads-eval/build/eval/report.md`.

Последний прогон (haiku на всех этапах): 31 из 32 кейсов, precision@1 100% (17/17), показ там, где ожидался, — 17/18, «без рекламы» — 12/12, показов на стоп-темах — 0. Единственный провал — двусмысленная реплика «…реквизиты для зарплаты. Можно сюда?»: гейт прочитал её как отправку реквизитов в чат и перестраховался.

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

- Эмбеддинги — хэширующие (`HashingTextEmbedder`): ловят лексику с учётом окончаний, но не смысл. Из-за этого негативный интент отсекает кампанию только с запасом `ads.matching.negative-margin` (0.1). В прод-конфигурации — EmbeddingsGigaR через gpt2giga. Векторы привязаны к модели (`embedding_model`), поэтому каталог, проиндексированный хэширующими эмбеддингами, на EmbeddingsGigaR ничего не найдёт: при смене модели кампании нужно переиндексировать. Отдельной команды переиндексации в прототипе нет — индекс строится при одобрении и импорте кампании.
- Работа на GigaChat не проверялась: golden set прогонялся только на Claude haiku, пороги подобраны на нём.
- Маскирование — регулярными выражениями; ФИО не ловятся, нужен NER-сервис.
- Задержка подбора через llm-cli-proxy — 5–20 с на ход: это свойство CLI, не пайплайна. Дедлайн проверен тестами, на прод-моделях его нужно замерить.
- В синтетическом каталоге варианты одного продукта почти одинаковы, поэтому топ-20 кандидатов часто занимает один продукт.
- Конверсии (действия в сценарии) не принимаются: в бою они приходят фоновыми потоками.
