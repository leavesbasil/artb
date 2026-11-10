CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE topic (
    id               text PRIMARY KEY,
    name             text    NOT NULL,
    parent_id        text    NULL REFERENCES topic (id),
    sensitive        boolean NOT NULL DEFAULT false,   -- стоп-тема: рекламу не показываем
    description      text    NOT NULL,                 -- идёт в промпт классификации
    taxonomy_version int     NOT NULL DEFAULT 1
);

CREATE SEQUENCE catalog_version_seq;

CREATE TABLE campaign (
    id                   uuid PRIMARY KEY,
    external_key         text UNIQUE,
    name                 text        NOT NULL,
    status               text        NOT NULL,         -- DRAFT | REVIEW | ACTIVE | PAUSED | ENDED
    business_description text        NOT NULL,
    topics               text[]      NOT NULL DEFAULT '{}',
    priority             int         NOT NULL DEFAULT 50,
    start_at             timestamptz NULL,
    end_at               timestamptz NULL,
    excluded_segments    text[]      NOT NULL DEFAULT '{}',  -- чёрный список сегментов
    excluded_topics      text[]      NOT NULL DEFAULT '{}',
    catalog_version      bigint      NOT NULL DEFAULT 0,
    updated_at           timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX campaign_status_idx ON campaign (status);

CREATE TABLE campaign_creative (
    id            uuid PRIMARY KEY,
    campaign_id   uuid NOT NULL REFERENCES campaign (id) ON DELETE CASCADE,
    type          text NOT NULL,                       -- CARD | QUESTION
    title         text NULL,
    body          text NULL,
    image_url     text NULL,
    cta           text NULL,
    url           text NULL,
    question_text text NULL,
    disclaimer    text NULL,
    erid          text NULL,                           -- указывает менеджер; text: формат не всегда числовой
    approved_by   text NULL,
    approved_at   timestamptz NULL
);

CREATE INDEX campaign_creative_campaign_idx ON campaign_creative (campaign_id);

CREATE TABLE campaign_intent (
    id              uuid PRIMARY KEY,
    campaign_id     uuid    NOT NULL REFERENCES campaign (id) ON DELETE CASCADE,
    text            text    NOT NULL,
    polarity        text    NOT NULL,                  -- POSITIVE | NEGATIVE
    embedding       vector  NULL,                      -- размерность зависит от модели
    embedding_model text    NULL,
    approved        boolean NOT NULL DEFAULT false
);

CREATE INDEX campaign_intent_campaign_idx ON campaign_intent (campaign_id);
