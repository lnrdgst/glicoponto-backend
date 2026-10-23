CREATE TABLE app_user (
    id uuid CONSTRAINT pk_app_user PRIMARY KEY,
    issuer text NOT NULL,
    subject text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_app_user_identity UNIQUE (issuer, subject),
    CONSTRAINT ck_app_user_identity CHECK (length(issuer) > 0 AND length(subject) > 0)
);

CREATE TABLE food (
    id uuid CONSTRAINT pk_food PRIMARY KEY,
    user_id uuid NOT NULL CONSTRAINT fk_food_user REFERENCES app_user(id),
    name varchar(200) NOT NULL CONSTRAINT ck_food_name CHECK (length(trim(name)) > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    revision bigint NOT NULL DEFAULT 1 CONSTRAINT ck_food_revision CHECK (revision > 0),
    CONSTRAINT uq_food_user_id UNIQUE (user_id, id),
    CONSTRAINT ck_food_dates CHECK (updated_at >= created_at AND (deleted_at IS NULL OR deleted_at >= created_at))
);

CREATE TABLE meal (
    id uuid CONSTRAINT pk_meal PRIMARY KEY,
    user_id uuid NOT NULL CONSTRAINT fk_meal_user REFERENCES app_user(id),
    kind varchar(80) NOT NULL CONSTRAINT ck_meal_kind CHECK (length(trim(kind)) > 0),
    at timestamptz NOT NULL,
    time_reference varchar(12) NOT NULL DEFAULT 'unknown'
        CONSTRAINT ck_meal_time_reference CHECK (time_reference IN ('meal_start', 'meal_end', 'unknown')),
    description varchar(10000),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    revision bigint NOT NULL DEFAULT 1 CONSTRAINT ck_meal_revision CHECK (revision > 0),
    CONSTRAINT uq_meal_user_id UNIQUE (user_id, id),
    CONSTRAINT ck_meal_dates CHECK (updated_at >= created_at AND (deleted_at IS NULL OR deleted_at >= created_at))
);

CREATE TABLE meal_item (
    id uuid CONSTRAINT pk_meal_item PRIMARY KEY,
    user_id uuid NOT NULL CONSTRAINT fk_meal_item_user REFERENCES app_user(id),
    meal_id uuid NOT NULL,
    food_id uuid,
    food_name varchar(200) NOT NULL CONSTRAINT ck_meal_item_name CHECK (length(trim(food_name)) > 0),
    -- Unconstrained NUMERIC plus explicit limits rejects excess scale instead of rounding it.
    quantity numeric NOT NULL,
    unit varchar(20) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    revision bigint NOT NULL DEFAULT 1 CONSTRAINT ck_meal_item_revision CHECK (revision > 0),
    CONSTRAINT fk_meal_item_meal_owner FOREIGN KEY (user_id, meal_id) REFERENCES meal(user_id, id),
    CONSTRAINT fk_meal_item_food_owner FOREIGN KEY (user_id, food_id) REFERENCES food(user_id, id),
    CONSTRAINT ck_meal_item_quantity CHECK (quantity > 0 AND quantity < 1000000000000 AND scale(quantity) <= 6),
    CONSTRAINT ck_meal_item_unit CHECK (unit IN ('g', 'kg', 'mg', 'mL', 'L', 'unit', 'portion', 'tsp', 'tbsp', 'cup')),
    CONSTRAINT ck_meal_item_dates CHECK (updated_at >= created_at AND (deleted_at IS NULL OR deleted_at >= created_at))
);

CREATE TABLE glucose_reading (
    id uuid CONSTRAINT pk_glucose_reading PRIMARY KEY,
    user_id uuid NOT NULL CONSTRAINT fk_glucose_reading_user REFERENCES app_user(id),
    value numeric NOT NULL,
    at timestamptz NOT NULL,
    context varchar(200),
    note varchar(10000),
    meal_id uuid,
    episode_id uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    revision bigint NOT NULL DEFAULT 1 CONSTRAINT ck_glucose_reading_revision CHECK (revision > 0),
    CONSTRAINT fk_glucose_reading_meal_owner FOREIGN KEY (user_id, meal_id) REFERENCES meal(user_id, id),
    CONSTRAINT ck_glucose_reading_value CHECK (value > 0 AND value < 1000000000000 AND scale(value) <= 6),
    CONSTRAINT ck_glucose_reading_dates CHECK (updated_at >= created_at AND (deleted_at IS NULL OR deleted_at >= created_at))
);
COMMENT ON COLUMN glucose_reading.episode_id IS 'Opaque legacy UUID, scoped to user_id; no episode resolution until owner-composite FK can be validated by a future migration.';

CREATE INDEX ix_food_user_created ON food(user_id, created_at, id) WHERE deleted_at IS NULL;
CREATE INDEX ix_meal_user_at ON meal(user_id, at, id) WHERE deleted_at IS NULL;
CREATE INDEX ix_meal_item_user_meal ON meal_item(user_id, meal_id, created_at, id);
CREATE INDEX ix_meal_item_user_food ON meal_item(user_id, food_id);
CREATE INDEX ix_glucose_reading_user_at ON glucose_reading(user_id, at, id) WHERE deleted_at IS NULL;
CREATE INDEX ix_glucose_reading_user_meal ON glucose_reading(user_id, meal_id);
CREATE INDEX ix_glucose_reading_user_episode ON glucose_reading(user_id, episode_id) WHERE episode_id IS NOT NULL;
