alter table jobs add column category varchar(100) not null default 'Other roles';

create index idx_jobs_category on jobs (category);
