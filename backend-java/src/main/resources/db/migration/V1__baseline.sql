-- Baseline schema, captured with pg_dump --schema-only from the live database
-- on 2026-07-25 and verified to reproduce it exactly.
--
-- The psql meta-commands pg_dump 17+ emits (\restrict / \unrestrict) are stripped:
-- they are a psql client feature, and Flyway — which is what actually applies this
-- file on a fresh deploy — speaks SQL only and fails on them.
--
-- PostgreSQL database dump
--


-- Dumped from database version 15.18 (Debian 15.18-1.pgdg13+1)
-- Dumped by pg_dump version 15.18 (Debian 15.18-1.pgdg13+1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: api_keys; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.api_keys (
    id integer NOT NULL,
    shop_id integer NOT NULL,
    key_hash text NOT NULL,
    prefix text NOT NULL,
    revoked boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    last_used_at timestamp with time zone
);


--
-- Name: api_keys_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.api_keys_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: api_keys_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.api_keys_id_seq OWNED BY public.api_keys.id;


--
-- Name: audit_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_log (
    id integer NOT NULL,
    actor_username text,
    action text NOT NULL,
    target_type text,
    target_id integer,
    details text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: audit_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.audit_log_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: audit_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.audit_log_id_seq OWNED BY public.audit_log.id;


--
-- Name: delivery_ratings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.delivery_ratings (
    ticket_id integer NOT NULL,
    volunteer_id integer,
    rating smallint NOT NULL,
    comment text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: fcm_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fcm_tokens (
    id integer NOT NULL,
    user_id integer NOT NULL,
    token text NOT NULL,
    role text NOT NULL,
    related_id integer,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: fcm_tokens_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.fcm_tokens_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: fcm_tokens_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.fcm_tokens_id_seq OWNED BY public.fcm_tokens.id;


--
-- Name: lots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.lots (
    id integer NOT NULL,
    shop_id integer NOT NULL,
    description text,
    quantity real,
    initial_quantity real,
    unit text DEFAULT 'кг'::text NOT NULL,
    unit_weight_kg real DEFAULT 1.0 NOT NULL,
    expiry_date date,
    photo text,
    address text,
    time_slot text,
    status text DEFAULT 'active'::text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    taken_at timestamp with time zone,
    taken_by text,
    category text,
    comment text,
    requires_cold boolean DEFAULT false NOT NULL,
    city text
);


--
-- Name: lots_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.lots_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: lots_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.lots_id_seq OWNED BY public.lots.id;


--
-- Name: needy; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.needy (
    id integer NOT NULL,
    name text NOT NULL,
    contact text,
    status text DEFAULT 'pending'::text NOT NULL,
    document text,
    created_at timestamp with time zone NOT NULL,
    kyc_score real,
    kyc_verdict text,
    kyc_notes text,
    kyc_checked_at timestamp with time zone
);


--
-- Name: needy_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.needy_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: needy_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.needy_id_seq OWNED BY public.needy.id;


--
-- Name: needy_profile; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.needy_profile (
    needy_id integer NOT NULL,
    address text,
    family_size integer,
    preferences text,
    urgency text,
    available_time text,
    last_received_at timestamp with time zone,
    document text,
    apartment text,
    floor_num text,
    entrance text,
    city text,
    lat real,
    lon real,
    geo_push_enabled boolean DEFAULT true NOT NULL,
    displaced_count integer DEFAULT 0 NOT NULL
);


--
-- Name: notifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notifications (
    id integer NOT NULL,
    shop_id integer,
    lot_id integer,
    type text,
    payload text,
    created_at timestamp with time zone NOT NULL,
    read integer DEFAULT 0 NOT NULL,
    needy_id integer,
    volunteer_id integer
);


--
-- Name: notifications_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.notifications_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: notifications_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.notifications_id_seq OWNED BY public.notifications.id;


--
-- Name: push_subscriptions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.push_subscriptions (
    id integer NOT NULL,
    user_id integer NOT NULL,
    endpoint text NOT NULL,
    p256dh text NOT NULL,
    auth text NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: push_subscriptions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.push_subscriptions_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: push_subscriptions_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.push_subscriptions_id_seq OWNED BY public.push_subscriptions.id;


--
-- Name: receipts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.receipts (
    id integer NOT NULL,
    shop_id integer NOT NULL,
    photo text,
    sha256 text,
    fingerprint text,
    merchant text,
    receipt_date date,
    total real,
    currency text,
    items text,
    fraud_score real,
    fraud_reasons text,
    status text DEFAULT 'parsed'::text NOT NULL,
    lot_ids text,
    created_at timestamp with time zone NOT NULL,
    confirmed_at timestamp with time zone
);


--
-- Name: receipts_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.receipts_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: receipts_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.receipts_id_seq OWNED BY public.receipts.id;


--
-- Name: shops; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.shops (
    id integer NOT NULL,
    name text NOT NULL,
    contact text,
    lat real,
    lon real,
    created_at timestamp with time zone NOT NULL,
    city text,
    plan text DEFAULT 'basic'::text NOT NULL,
    kind text DEFAULT 'business'::text NOT NULL
);


--
-- Name: shops_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.shops_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: shops_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.shops_id_seq OWNED BY public.shops.id;


--
-- Name: teams; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.teams (
    id integer NOT NULL,
    name text NOT NULL,
    join_code text NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: teams_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.teams_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: teams_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.teams_id_seq OWNED BY public.teams.id;


--
-- Name: telegram_link_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.telegram_link_tokens (
    id integer NOT NULL,
    token text NOT NULL,
    user_id integer NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: telegram_link_tokens_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.telegram_link_tokens_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: telegram_link_tokens_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.telegram_link_tokens_id_seq OWNED BY public.telegram_link_tokens.id;


--
-- Name: telegram_login_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.telegram_login_tokens (
    id integer NOT NULL,
    token text NOT NULL,
    user_id integer,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: telegram_login_tokens_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.telegram_login_tokens_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: telegram_login_tokens_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.telegram_login_tokens_id_seq OWNED BY public.telegram_login_tokens.id;


--
-- Name: ticket_messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ticket_messages (
    id integer NOT NULL,
    ticket_id integer NOT NULL,
    sender_role text NOT NULL,
    sender_id integer NOT NULL,
    body text NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: ticket_messages_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ticket_messages_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ticket_messages_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ticket_messages_id_seq OWNED BY public.ticket_messages.id;


--
-- Name: tickets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tickets (
    id integer NOT NULL,
    needy_id integer NOT NULL,
    items text,
    available_time text,
    address text,
    lat real,
    lon real,
    lot_id integer,
    quantity real DEFAULT 1.0 NOT NULL,
    expires_at timestamp with time zone,
    status text DEFAULT 'open'::text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    assigned_volunteer text,
    fulfilled_at timestamp with time zone,
    apartment text,
    floor_num text,
    entrance text,
    self_pickup boolean DEFAULT false,
    qr_secret text,
    assigned_volunteer_id integer,
    delivery_photo text,
    delivery_photo_status text,
    delivery_photo_ai_verdict text,
    delivery_photo_ai_score real,
    delivery_photo_ai_notes text,
    delivery_photo_reviewed_at timestamp with time zone
);


--
-- Name: tickets_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tickets_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tickets_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tickets_id_seq OWNED BY public.tickets.id;


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id integer NOT NULL,
    username text NOT NULL,
    hashed_password text NOT NULL,
    role text NOT NULL,
    related_id integer,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    is_blocked boolean DEFAULT false NOT NULL,
    telegram_chat_id text,
    google_id text,
    yandex_id text
);


--
-- Name: users_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.users_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: users_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.users_id_seq OWNED BY public.users.id;


--
-- Name: volunteer_routes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.volunteer_routes (
    id integer NOT NULL,
    volunteer_id integer NOT NULL,
    points text,
    status text NOT NULL,
    lot_id integer,
    started_at timestamp with time zone NOT NULL,
    finished_at timestamp with time zone,
    last_activity_at timestamp with time zone,
    start_dist_m real,
    antifraud_ping_at timestamp with time zone
);


--
-- Name: volunteer_routes_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.volunteer_routes_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: volunteer_routes_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.volunteer_routes_id_seq OWNED BY public.volunteer_routes.id;


--
-- Name: volunteers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.volunteers (
    id integer NOT NULL,
    name text NOT NULL,
    contact text,
    lat real,
    lon real,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone,
    city text,
    has_thermal_bag boolean DEFAULT false NOT NULL,
    availability text,
    team_id integer,
    status text,
    document text,
    kyc_score real,
    kyc_verdict text,
    kyc_notes text,
    kyc_checked_at timestamp with time zone
);


--
-- Name: volunteers_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.volunteers_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: volunteers_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.volunteers_id_seq OWNED BY public.volunteers.id;


--
-- Name: webhooks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.webhooks (
    id integer NOT NULL,
    shop_id integer NOT NULL,
    url text NOT NULL,
    secret text NOT NULL,
    events text DEFAULT '*'::text NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    last_status integer,
    last_delivery_at timestamp with time zone
);


--
-- Name: webhooks_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.webhooks_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: webhooks_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.webhooks_id_seq OWNED BY public.webhooks.id;


--
-- Name: api_keys id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_keys ALTER COLUMN id SET DEFAULT nextval('public.api_keys_id_seq'::regclass);


--
-- Name: audit_log id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log ALTER COLUMN id SET DEFAULT nextval('public.audit_log_id_seq'::regclass);


--
-- Name: fcm_tokens id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fcm_tokens ALTER COLUMN id SET DEFAULT nextval('public.fcm_tokens_id_seq'::regclass);


--
-- Name: lots id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.lots ALTER COLUMN id SET DEFAULT nextval('public.lots_id_seq'::regclass);


--
-- Name: needy id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.needy ALTER COLUMN id SET DEFAULT nextval('public.needy_id_seq'::regclass);


--
-- Name: notifications id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications ALTER COLUMN id SET DEFAULT nextval('public.notifications_id_seq'::regclass);


--
-- Name: push_subscriptions id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.push_subscriptions ALTER COLUMN id SET DEFAULT nextval('public.push_subscriptions_id_seq'::regclass);


--
-- Name: receipts id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.receipts ALTER COLUMN id SET DEFAULT nextval('public.receipts_id_seq'::regclass);


--
-- Name: shops id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.shops ALTER COLUMN id SET DEFAULT nextval('public.shops_id_seq'::regclass);


--
-- Name: teams id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.teams ALTER COLUMN id SET DEFAULT nextval('public.teams_id_seq'::regclass);


--
-- Name: telegram_link_tokens id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.telegram_link_tokens ALTER COLUMN id SET DEFAULT nextval('public.telegram_link_tokens_id_seq'::regclass);


--
-- Name: telegram_login_tokens id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.telegram_login_tokens ALTER COLUMN id SET DEFAULT nextval('public.telegram_login_tokens_id_seq'::regclass);


--
-- Name: ticket_messages id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ticket_messages ALTER COLUMN id SET DEFAULT nextval('public.ticket_messages_id_seq'::regclass);


--
-- Name: tickets id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tickets ALTER COLUMN id SET DEFAULT nextval('public.tickets_id_seq'::regclass);


--
-- Name: users id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users ALTER COLUMN id SET DEFAULT nextval('public.users_id_seq'::regclass);


--
-- Name: volunteer_routes id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.volunteer_routes ALTER COLUMN id SET DEFAULT nextval('public.volunteer_routes_id_seq'::regclass);


--
-- Name: volunteers id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.volunteers ALTER COLUMN id SET DEFAULT nextval('public.volunteers_id_seq'::regclass);


--
-- Name: webhooks id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.webhooks ALTER COLUMN id SET DEFAULT nextval('public.webhooks_id_seq'::regclass);


--
-- Name: api_keys api_keys_key_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_keys
    ADD CONSTRAINT api_keys_key_hash_key UNIQUE (key_hash);


--
-- Name: api_keys api_keys_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_keys
    ADD CONSTRAINT api_keys_pkey PRIMARY KEY (id);


--
-- Name: audit_log audit_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT audit_log_pkey PRIMARY KEY (id);


--
-- Name: delivery_ratings delivery_ratings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.delivery_ratings
    ADD CONSTRAINT delivery_ratings_pkey PRIMARY KEY (ticket_id);


--
-- Name: fcm_tokens fcm_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fcm_tokens
    ADD CONSTRAINT fcm_tokens_pkey PRIMARY KEY (id);


--
-- Name: fcm_tokens fcm_tokens_token_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fcm_tokens
    ADD CONSTRAINT fcm_tokens_token_key UNIQUE (token);


--
-- Name: lots lots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.lots
    ADD CONSTRAINT lots_pkey PRIMARY KEY (id);


--
-- Name: needy needy_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.needy
    ADD CONSTRAINT needy_pkey PRIMARY KEY (id);


--
-- Name: needy_profile needy_profile_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.needy_profile
    ADD CONSTRAINT needy_profile_pkey PRIMARY KEY (needy_id);


--
-- Name: notifications notifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_pkey PRIMARY KEY (id);


--
-- Name: push_subscriptions push_subscriptions_endpoint_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.push_subscriptions
    ADD CONSTRAINT push_subscriptions_endpoint_key UNIQUE (endpoint);


--
-- Name: push_subscriptions push_subscriptions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.push_subscriptions
    ADD CONSTRAINT push_subscriptions_pkey PRIMARY KEY (id);


--
-- Name: receipts receipts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.receipts
    ADD CONSTRAINT receipts_pkey PRIMARY KEY (id);


--
-- Name: shops shops_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.shops
    ADD CONSTRAINT shops_pkey PRIMARY KEY (id);


--
-- Name: teams teams_join_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.teams
    ADD CONSTRAINT teams_join_code_key UNIQUE (join_code);


--
-- Name: teams teams_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.teams
    ADD CONSTRAINT teams_pkey PRIMARY KEY (id);


--
-- Name: telegram_link_tokens telegram_link_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.telegram_link_tokens
    ADD CONSTRAINT telegram_link_tokens_pkey PRIMARY KEY (id);


--
-- Name: telegram_link_tokens telegram_link_tokens_token_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.telegram_link_tokens
    ADD CONSTRAINT telegram_link_tokens_token_key UNIQUE (token);


--
-- Name: telegram_login_tokens telegram_login_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.telegram_login_tokens
    ADD CONSTRAINT telegram_login_tokens_pkey PRIMARY KEY (id);


--
-- Name: telegram_login_tokens telegram_login_tokens_token_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.telegram_login_tokens
    ADD CONSTRAINT telegram_login_tokens_token_key UNIQUE (token);


--
-- Name: ticket_messages ticket_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ticket_messages
    ADD CONSTRAINT ticket_messages_pkey PRIMARY KEY (id);


--
-- Name: tickets tickets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tickets
    ADD CONSTRAINT tickets_pkey PRIMARY KEY (id);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: users users_username_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_username_key UNIQUE (username);


--
-- Name: volunteer_routes volunteer_routes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.volunteer_routes
    ADD CONSTRAINT volunteer_routes_pkey PRIMARY KEY (id);


--
-- Name: volunteers volunteers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.volunteers
    ADD CONSTRAINT volunteers_pkey PRIMARY KEY (id);


--
-- Name: webhooks webhooks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.webhooks
    ADD CONSTRAINT webhooks_pkey PRIMARY KEY (id);


--
-- Name: idx_delivery_ratings_volunteer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_delivery_ratings_volunteer ON public.delivery_ratings USING btree (volunteer_id);


--
-- Name: idx_fcm_tokens_role_related; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fcm_tokens_role_related ON public.fcm_tokens USING btree (role, related_id);


--
-- Name: idx_fcm_tokens_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fcm_tokens_user ON public.fcm_tokens USING btree (user_id);


--
-- Name: idx_lots_shop_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_lots_shop_status ON public.lots USING btree (shop_id, status);


--
-- Name: idx_lots_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_lots_status ON public.lots USING btree (status);


--
-- Name: idx_notifications_needy_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notifications_needy_id ON public.notifications USING btree (needy_id) WHERE (needy_id IS NOT NULL);


--
-- Name: idx_notifications_shop_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notifications_shop_id ON public.notifications USING btree (shop_id) WHERE (shop_id IS NOT NULL);


--
-- Name: idx_notifications_volunteer_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notifications_volunteer_id ON public.notifications USING btree (volunteer_id) WHERE (volunteer_id IS NOT NULL);


--
-- Name: idx_push_subscriptions_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_push_subscriptions_user ON public.push_subscriptions USING btree (user_id);


--
-- Name: idx_receipts_fp; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_receipts_fp ON public.receipts USING btree (fingerprint) WHERE (fingerprint IS NOT NULL);


--
-- Name: idx_receipts_sha; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_receipts_sha ON public.receipts USING btree (sha256);


--
-- Name: idx_receipts_shop; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_receipts_shop ON public.receipts USING btree (shop_id);


--
-- Name: idx_routes_volunteer_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_routes_volunteer_status ON public.volunteer_routes USING btree (volunteer_id, status);


--
-- Name: idx_ticket_messages_ticket; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ticket_messages_ticket ON public.ticket_messages USING btree (ticket_id, id);


--
-- Name: idx_tickets_assigned_volunteer_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tickets_assigned_volunteer_id ON public.tickets USING btree (assigned_volunteer_id) WHERE (assigned_volunteer_id IS NOT NULL);


--
-- Name: idx_tickets_needy_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tickets_needy_status ON public.tickets USING btree (needy_id, status);


--
-- Name: idx_tickets_photo_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tickets_photo_status ON public.tickets USING btree (delivery_photo_status) WHERE (delivery_photo_status IS NOT NULL);


--
-- Name: idx_tickets_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tickets_status ON public.tickets USING btree (status);


--
-- Name: idx_volunteers_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_volunteers_status ON public.volunteers USING btree (status);


--
-- Name: idx_webhooks_shop; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_webhooks_shop ON public.webhooks USING btree (shop_id) WHERE active;


--
-- Name: uq_routes_one_active_per_volunteer; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_routes_one_active_per_volunteer ON public.volunteer_routes USING btree (volunteer_id) WHERE (status = 'in_progress'::text);


--
-- Name: uq_telegram_link_tokens_user; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_telegram_link_tokens_user ON public.telegram_link_tokens USING btree (user_id);


--
-- Name: uq_tickets_one_active_per_needy; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_tickets_one_active_per_needy ON public.tickets USING btree (needy_id) WHERE (status = ANY (ARRAY['open'::text, 'assigned'::text]));


--
-- Name: uq_users_google_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_users_google_id ON public.users USING btree (google_id) WHERE (google_id IS NOT NULL);


--
-- Name: uq_users_role_related; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_users_role_related ON public.users USING btree (role, related_id) WHERE (related_id IS NOT NULL);


--
-- Name: uq_users_yandex_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_users_yandex_id ON public.users USING btree (yandex_id) WHERE (yandex_id IS NOT NULL);


--
-- Name: api_keys api_keys_shop_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_keys
    ADD CONSTRAINT api_keys_shop_id_fkey FOREIGN KEY (shop_id) REFERENCES public.shops(id);


--
-- Name: delivery_ratings delivery_ratings_ticket_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.delivery_ratings
    ADD CONSTRAINT delivery_ratings_ticket_id_fkey FOREIGN KEY (ticket_id) REFERENCES public.tickets(id) ON DELETE CASCADE;


--
-- Name: fcm_tokens fcm_tokens_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fcm_tokens
    ADD CONSTRAINT fcm_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: lots lots_shop_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.lots
    ADD CONSTRAINT lots_shop_id_fkey FOREIGN KEY (shop_id) REFERENCES public.shops(id);


--
-- Name: needy_profile needy_profile_needy_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.needy_profile
    ADD CONSTRAINT needy_profile_needy_id_fkey FOREIGN KEY (needy_id) REFERENCES public.needy(id);


--
-- Name: push_subscriptions push_subscriptions_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.push_subscriptions
    ADD CONSTRAINT push_subscriptions_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: receipts receipts_shop_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.receipts
    ADD CONSTRAINT receipts_shop_id_fkey FOREIGN KEY (shop_id) REFERENCES public.shops(id);


--
-- Name: telegram_link_tokens telegram_link_tokens_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.telegram_link_tokens
    ADD CONSTRAINT telegram_link_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: telegram_login_tokens telegram_login_tokens_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.telegram_login_tokens
    ADD CONSTRAINT telegram_login_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: ticket_messages ticket_messages_ticket_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ticket_messages
    ADD CONSTRAINT ticket_messages_ticket_id_fkey FOREIGN KEY (ticket_id) REFERENCES public.tickets(id) ON DELETE CASCADE;


--
-- Name: tickets tickets_needy_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tickets
    ADD CONSTRAINT tickets_needy_id_fkey FOREIGN KEY (needy_id) REFERENCES public.needy(id);


--
-- Name: volunteers volunteers_team_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.volunteers
    ADD CONSTRAINT volunteers_team_id_fkey FOREIGN KEY (team_id) REFERENCES public.teams(id);


--
-- Name: webhooks webhooks_shop_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.webhooks
    ADD CONSTRAINT webhooks_shop_id_fkey FOREIGN KEY (shop_id) REFERENCES public.shops(id);


--
-- PostgreSQL database dump complete
--


