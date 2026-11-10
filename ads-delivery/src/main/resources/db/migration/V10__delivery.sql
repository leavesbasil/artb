-- Решения о рекламе (в том числе «без рекламы») и события от поверхности.
CREATE TABLE ad_offer (
    id                uuid PRIMARY KEY,
    session_id        text        NOT NULL,
    client_id         text        NULL,
    turn_no           int         NOT NULL,
    created_at        timestamptz NOT NULL,
    expires_at        timestamptz NULL,
    status            text        NOT NULL,   -- ISSUED | SHOWN | EXPIRED | NO_AD
    intent_queries    jsonb       NULL,
    commercial_intent double precision NULL,
    topics            jsonb       NULL,       -- [{id, confidence}]
    candidates        jsonb       NULL,       -- [{campaignId, name, similarity, relevance, score, reason}]
    campaign_id       uuid        NULL REFERENCES campaign (id),
    creative_id       uuid        NULL REFERENCES campaign_creative (id),
    format            text        NULL,
    relevance         double precision NULL,
    score             double precision NULL,
    reason            text        NULL,
    no_ad_reason      text        NULL,       -- GATE_* | NO_ELIGIBLE | SENSITIVE_TOPIC | LOW_INTENT | NO_CANDIDATES | LOW_RELEVANCE | TIMEOUT | ERROR
    from_cache        boolean     NOT NULL DEFAULT false,
    latency_ms        jsonb       NULL,       -- по этапам
    models            jsonb       NULL        -- какая модель на каком этапе
);

CREATE INDEX ad_offer_session_idx ON ad_offer (session_id, turn_no);

CREATE TABLE ad_event (
    offer_id    uuid        NOT NULL REFERENCES ad_offer (id),
    type        text        NOT NULL,         -- IMPRESSION | CLICK | QUESTION_CLICK | DISMISS | CONVERSION
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    surface     text        NULL,
    payload     jsonb       NULL,
    PRIMARY KEY (offer_id, type)              -- идемпотентность
);
