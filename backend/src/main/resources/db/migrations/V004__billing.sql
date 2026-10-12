CREATE TABLE ct_billing_orders (
 id varchar(100) PRIMARY KEY,
 family_id varchar(100) NOT NULL REFERENCES ct_families(id) ON DELETE CASCADE,
 actor_id varchar(100) NOT NULL,
 request_id varchar(100) NOT NULL,
 request_sha256 char(64) NOT NULL,
 out_trade_no varchar(32) NOT NULL UNIQUE,
 product_id varchar(100) NOT NULL,
 feature varchar(16) NOT NULL CHECK(feature IN ('profiles','scenarios','analytics')),
 title varchar(100) NOT NULL,
 amount_minor bigint NOT NULL CHECK(amount_minor > 0),
 currency varchar(3) NOT NULL CHECK(currency='CNY'),
 duration_days integer NOT NULL CHECK(duration_days BETWEEN 1 AND 3660),
 provider varchar(30) NOT NULL,
 merchant_id varchar(100) NOT NULL,
 app_id varchar(100) NOT NULL,
 status varchar(20) NOT NULL CHECK(status IN ('CREATED','PENDING','PAID','CLOSED','REFUND_PENDING','REFUNDED','FAILED')),
 checkout_url text,
 provider_reference varchar(200),
 transaction_id varchar(200),
 out_refund_no varchar(32) UNIQUE,
 created_at timestamptz NOT NULL,
 updated_at timestamptz NOT NULL,
 UNIQUE(family_id,request_id)
);
CREATE INDEX ct_billing_orders_family ON ct_billing_orders(family_id,created_at);
CREATE TABLE ct_billing_grants (
 id varchar(100) PRIMARY KEY,
 family_id varchar(100) NOT NULL REFERENCES ct_families(id) ON DELETE CASCADE,
 feature varchar(16) NOT NULL CHECK(feature IN ('profiles','scenarios','analytics')),
 source varchar(16) NOT NULL CHECK(source IN ('manual-test','purchase')),
 order_id varchar(100) UNIQUE REFERENCES ct_billing_orders(id),
 starts_at timestamptz NOT NULL,
 expires_at timestamptz NOT NULL,
 revoked_at timestamptz,
 granted_by varchar(100) NOT NULL,
 CHECK(expires_at > starts_at)
);
CREATE INDEX ct_billing_grants_family ON ct_billing_grants(family_id,feature);
CREATE TABLE ct_billing_event_inbox (
 provider varchar(30) NOT NULL,
 event_id varchar(200) NOT NULL,
 request_sha256 char(64) NOT NULL,
 order_id varchar(100) NOT NULL REFERENCES ct_billing_orders(id),
 event_type varchar(30) NOT NULL,
 received_at timestamptz NOT NULL,
 PRIMARY KEY(provider,event_id)
);
