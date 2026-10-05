create table jobs (
    id varchar(255) primary key,
    source_key varchar(100) not null,
    source_name varchar(255) not null,
    source_type varchar(255) not null,
    source_feed_url varchar(1000) not null,
    source_url varchar(1000) not null,
    external_job_id varchar(255) not null,
    title varchar(500) not null,
    company varchar(500) not null,
    location varchar(500) not null,
    longitude double precision,
    latitude double precision,
    department varchar(500),
    employment_type varchar(255),
    seniority varchar(255),
    schedule varchar(255),
    source_created_at varchar(255),
    raw_description text not null,
    content_hash varchar(64) not null,
    first_seen_at timestamp with time zone not null,
    last_seen_at timestamp with time zone not null,
    last_checked_at timestamp with time zone not null,
    active boolean not null,
    in_scope boolean not null,
    consecutive_missing_count integer not null,
    baseline_json text not null,
    analysis_json text not null
);

create index idx_jobs_active_scope on jobs (active, in_scope);
create index idx_jobs_source on jobs (source_key);

create table sync_state (
    id integer primary key,
    last_sync_at timestamp with time zone,
    summary_json text,
    sync_error text
);

insert into sync_state (id) values (1);
