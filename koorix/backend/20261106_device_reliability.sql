-- Koorix only. No changes to shared Auth providers or other applications.
create table if not exists public.koorix_devices (
  device_id uuid primary key, secret_hash text not null check(length(secret_hash)=64),
  email text not null unique default ('koorix-'||gen_random_uuid()::text||'@device.koorix.invalid'),
  user_id uuid unique references auth.users(id) on delete cascade, created_at timestamptz not null default now()
);
create table if not exists public.koorix_device_rates (
  bucket text not null, slot timestamptz not null, requests integer not null default 0, primary key(bucket,slot)
);
create table if not exists public.koorix_jobs (
  job_id uuid primary key, user_id uuid not null references auth.users(id) on delete cascade,
  pages integer not null check(pages between 1 and 300), day date not null default current_date,
  completed boolean not null default false, created_at timestamptz not null default now()
);
create table if not exists public.koorix_plans (
  months integer primary key check(months in (1,3,6,12)), price_iqd integer not null check(price_iqd>0), days integer not null
);
insert into public.koorix_plans values (1,5000,30),(3,14000,90),(6,26000,180),(12,40000,365) on conflict do nothing;
create table if not exists public.koorix_orders (
  id uuid primary key default gen_random_uuid(), user_id uuid not null references auth.users(id) on delete cascade,
  months integer not null references public.koorix_plans(months), price_iqd integer not null,
  status text not null default 'pending' check(status in ('pending','approved','cancelled')),
  created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table if not exists public.koorix_admins (email text primary key);
alter table public.koorix_devices enable row level security;
alter table public.koorix_device_rates enable row level security;
alter table public.koorix_jobs enable row level security;
alter table public.koorix_plans enable row level security;
alter table public.koorix_orders enable row level security;
alter table public.koorix_admins enable row level security;
revoke all on public.koorix_devices,public.koorix_device_rates,public.koorix_jobs,public.koorix_orders,public.koorix_admins from anon,authenticated;
grant all on public.koorix_devices,public.koorix_device_rates,public.koorix_jobs,public.koorix_plans,public.koorix_orders,public.koorix_admins to service_role;
grant select on public.koorix_plans to anon,authenticated;
drop policy if exists koorix_plans_public on public.koorix_plans;
create policy koorix_plans_public on public.koorix_plans for select using(true);

-- Service-only device credential exchange. No user emails, passwords or CAPTCHA
-- credentials are collected. The random secret is never stored in plaintext.
create or replace function public.koorix_guest_begin(p_device_id uuid,p_secret_hash text,p_bucket text)
returns jsonb language plpgsql security definer set search_path=public as $$
declare v public.koorix_devices; v_count int; v_slot timestamptz:=date_trunc('hour',now());
begin
 if length(p_secret_hash)<>64 or p_secret_hash !~ '^[0-9a-f]+$' then raise exception 'invalid_device'; end if;
 perform pg_advisory_xact_lock(hashtextextended(p_device_id::text,0));
 select * into v from public.koorix_devices where device_id=p_device_id;
 if found then
  if v.secret_hash<>p_secret_hash then raise exception 'invalid_device'; end if;
 else
  delete from public.koorix_device_rates where slot<now()-interval '24 hours';
  insert into public.koorix_device_rates(bucket,slot,requests) values(p_bucket,v_slot,1)
   on conflict(bucket,slot) do update set requests=koorix_device_rates.requests+1 returning requests into v_count;
  if v_count>30 then raise exception 'device_rate_limit'; end if;
  insert into public.koorix_devices(device_id,secret_hash) values(p_device_id,p_secret_hash) returning * into v;
 end if;
 return jsonb_build_object('email',v.email,'user_id',v.user_id);
end $$;
create or replace function public.koorix_guest_finish(p_device_id uuid,p_secret_hash text,p_user_id uuid)
returns void language plpgsql security definer set search_path=public as $$
begin
 update public.koorix_devices set user_id=p_user_id where device_id=p_device_id and secret_hash=p_secret_hash and (user_id is null or user_id=p_user_id);
 if not found then raise exception 'invalid_device'; end if;
end $$;
revoke all on function public.koorix_guest_begin(uuid,text,text),public.koorix_guest_finish(uuid,text,uuid) from public,anon,authenticated;
grant execute on function public.koorix_guest_begin(uuid,text,text),public.koorix_guest_finish(uuid,text,uuid) to service_role;

create or replace function public.koorix_status_v2() returns jsonb language plpgsql security definer set search_path=public as $$
begin
 return public.koorix_my_status()||jsonb_build_object('usage_day',current_date,'server_ms',floor(extract(epoch from clock_timestamp())*1000));
end $$;
-- Repeated callbacks/restarts of one saved job cannot spend another daily use.
create or replace function public.koorix_authorize_job(p_job_id uuid,p_pages integer) returns jsonb
language plpgsql security definer set search_path=public as $$
declare v_uid uuid:=auth.uid(); v public.koorix_jobs; result jsonb;
begin
 if v_uid is null then raise exception 'not_authenticated'; end if;
 if p_pages not between 1 and 300 then raise exception 'invalid_pages'; end if;
 perform pg_advisory_xact_lock(hashtextextended(p_job_id::text,0));
 select * into v from public.koorix_jobs where job_id=p_job_id;
 if found then
  if v.user_id<>v_uid or v.pages<>p_pages or v.completed then
   return jsonb_build_object('allowed',false,'error','invalid_saved_job');
  end if;
  return public.koorix_status_v2()||jsonb_build_object('allowed',true,'reused',true);
 end if;
 result:=public.koorix_consume_job(p_pages);
 if (result->>'allowed')::boolean then
  insert into public.koorix_jobs(job_id,user_id,pages) values(p_job_id,v_uid,p_pages);
 end if;
 return result||jsonb_build_object('usage_day',current_date,'server_ms',floor(extract(epoch from clock_timestamp())*1000));
end $$;
create or replace function public.koorix_finish_job(p_job_id uuid) returns void
language plpgsql security definer set search_path=public as $$
begin
 if auth.uid() is null then raise exception 'not_authenticated'; end if;
 update public.koorix_jobs set completed=true where job_id=p_job_id and user_id=auth.uid();
end $$;
-- Offline spending is counted once when connectivity returns. It cannot grant
-- Premium or a new online allowance; the server can record an exhausted budget.
create or replace function public.koorix_sync_offline_job(p_job_id uuid,p_pages integer,p_day date,p_completed boolean)
returns void language plpgsql security definer set search_path=public as $$
declare v_uid uuid:=auth.uid(); v public.koorix_jobs; v_day date:=least(current_date,greatest(current_date-7,p_day));
begin
 if v_uid is null then raise exception 'not_authenticated'; end if;
 if p_pages not between 1 and 300 then raise exception 'invalid_pages'; end if;
 perform pg_advisory_xact_lock(hashtextextended(p_job_id::text,0));
 select * into v from public.koorix_jobs where job_id=p_job_id;
 if found then
  if v.user_id<>v_uid or v.pages<>p_pages then raise exception 'invalid_saved_job'; end if;
  if p_completed then update public.koorix_jobs set completed=true where job_id=p_job_id; end if;
  return;
 end if;
 insert into public.koorix_jobs(job_id,user_id,pages,day,completed) values(p_job_id,v_uid,p_pages,v_day,p_completed);
 insert into public.koorix_usage(user_id,day,jobs,pages) values(v_uid,v_day,1,p_pages)
 on conflict(user_id,day) do update set jobs=koorix_usage.jobs+1,pages=koorix_usage.pages+excluded.pages;
end $$;
create or replace function public.koorix_request_order(p_months integer) returns jsonb
language plpgsql security definer set search_path=public as $$
declare v_plan public.koorix_plans; v_order public.koorix_orders;
begin
 if auth.uid() is null then raise exception 'not_authenticated'; end if;
 select * into v_plan from public.koorix_plans where months=p_months;
 if not found then raise exception 'invalid_plan'; end if;
 perform pg_advisory_xact_lock(hashtextextended(auth.uid()::text,1));
 select * into v_order from public.koorix_orders where user_id=auth.uid() and status='pending' and months=p_months order by created_at desc limit 1;
 if not found then
  if (select count(*) from public.koorix_orders where user_id=auth.uid() and status='pending')>=4 then raise exception 'too_many_orders'; end if;
  insert into public.koorix_orders(user_id,months,price_iqd) values(auth.uid(),p_months,v_plan.price_iqd) returning * into v_order;
 end if;
 return jsonb_build_object('id',v_order.id,'months',v_order.months,'price_iqd',v_order.price_iqd,'status',v_order.status,'payment_charged',false);
end $$;
create or replace function public.koorix_my_orders() returns jsonb
language sql security definer set search_path=public as $$
 select coalesce(jsonb_agg(to_jsonb(t)),'[]'::jsonb) from
 (select id,months,price_iqd,status,created_at from public.koorix_orders where user_id=auth.uid() order by created_at desc limit 20) t;
$$;
-- Only a configured owner with a recent, mailbox-verified OTP/OAuth session.
-- A password signup with an autoconfirmed owner email is NOT an administrator.
create or replace function public.koorix_is_admin() returns boolean
language sql stable security definer set search_path=public as $$
 select auth.uid() is not null and exists(select 1 from public.koorix_admins where email=lower(auth.jwt()->>'email'))
 and exists(select 1 from jsonb_array_elements(coalesce(auth.jwt()->'amr','[]'::jsonb)) x
 where x->>'method' in ('otp','oauth') and (x->>'timestamp')::numeric>extract(epoch from now())-3600);
$$;
create or replace function public.koorix_admin_orders() returns jsonb
language plpgsql security definer set search_path=public as $$
begin
 if not public.koorix_is_admin() then raise exception 'not_authorized'; end if;
 return (select coalesce(jsonb_agg(to_jsonb(t)),'[]'::jsonb) from (select id,user_id,months,price_iqd,status,created_at from public.koorix_orders order by created_at desc limit 200) t);
end $$;
create or replace function public.koorix_admin_approve_order(p_order_id uuid) returns jsonb
language plpgsql security definer set search_path=public as $$
declare v public.koorix_orders; v_days integer; v_until timestamptz;
begin
 if not public.koorix_is_admin() then raise exception 'not_authorized'; end if;
 select * into v from public.koorix_orders where id=p_order_id for update;
 if not found then raise exception 'order_not_found'; end if;
 if v.status='approved' then return jsonb_build_object('ok',true,'already_approved',true); end if;
 if v.status<>'pending' then raise exception 'invalid_order'; end if;
 select days into v_days from public.koorix_plans where months=v.months;
 insert into public.koorix_profiles(id) values(v.user_id) on conflict do nothing;
 select greatest(coalesce(premium_until,now()),now()) into v_until from public.koorix_profiles where id=v.user_id for update;
 v_until:=v_until+make_interval(days=>v_days);
 update public.koorix_profiles set premium_until=v_until where id=v.user_id;
 update public.koorix_orders set status='approved',updated_at=now() where id=v.id;
 return jsonb_build_object('ok',true,'premium_until',v_until);
end $$;
create or replace function public.koorix_admin_create_code(p_months integer) returns jsonb
language plpgsql security definer set search_path=public as $$
declare v_days integer; v_code text:='KX-'||upper(replace(gen_random_uuid()::text,'-',''));
begin
 if not public.koorix_is_admin() then raise exception 'not_authorized'; end if;
 select days into v_days from public.koorix_plans where months=p_months;
 if not found then raise exception 'invalid_plan'; end if;
 insert into public.koorix_codes(code,days,note) values(v_code,v_days,'Owner-created '||p_months||' month plan');
 return jsonb_build_object('code',v_code,'days',v_days);
end $$;
-- User initiated deletion is scoped to Koorix; shared authentication identities
-- belonging to another app are not removed by this RPC.
create or replace function public.koorix_delete_my_data() returns jsonb
language plpgsql security definer set search_path=public as $$
declare v_uid uuid:=auth.uid();
begin
 if v_uid is null then raise exception 'not_authenticated'; end if;
 delete from public.koorix_codes where redeemed_by=v_uid;
 delete from public.koorix_orders where user_id=v_uid;
 delete from public.koorix_jobs where user_id=v_uid;
 delete from public.koorix_usage where user_id=v_uid;
 delete from public.koorix_profiles where id=v_uid;
 return jsonb_build_object('ok',true);
end $$;
revoke all on function public.koorix_status_v2(),public.koorix_authorize_job(uuid,integer),public.koorix_finish_job(uuid),public.koorix_sync_offline_job(uuid,integer,date,boolean),public.koorix_request_order(integer),public.koorix_my_orders(),public.koorix_is_admin(),public.koorix_admin_orders(),public.koorix_admin_approve_order(uuid),public.koorix_admin_create_code(integer),public.koorix_delete_my_data() from public,anon;
grant execute on function public.koorix_status_v2(),public.koorix_authorize_job(uuid,integer),public.koorix_finish_job(uuid),public.koorix_sync_offline_job(uuid,integer,date,boolean),public.koorix_request_order(integer),public.koorix_my_orders(),public.koorix_is_admin(),public.koorix_admin_orders(),public.koorix_admin_approve_order(uuid),public.koorix_admin_create_code(integer),public.koorix_delete_my_data() to authenticated,service_role;
