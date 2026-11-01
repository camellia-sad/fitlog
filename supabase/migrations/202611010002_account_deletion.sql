begin;
alter table public.social_profiles add column deletion_started_at timestamptz;
-- Freeze authenticated writes during deletion, including stale JWTs after hard deletion.
create function fitlog_private.active_account_write() returns trigger
language plpgsql security definer set search_path='' as $$
declare actor uuid:=auth.uid();
begin
 if actor is not null then
  perform 1 from public.social_profiles where id=actor and deletion_started_at is null for share;
  if not found then raise exception '账号正在注销或已注销';end if;
 end if;
 return new;
end $$;
revoke all on function fitlog_private.active_account_write() from public,anon,authenticated;
do $$ declare name text;
begin
 foreach name in array array['social_profiles','friendships','friend_blocks','friend_notes','social_privacy',
 'social_messages','social_message_hides','social_read_marks','social_posts','social_comments','social_likes',
 'social_live','social_training_history','chat_local_ack','chat_delivery_receipts'] loop
  execute format('create trigger active_account_write before insert or update on public.%I for each row execute function fitlog_private.active_account_write()',name);
 end loop;
end $$;
create trigger fitlog_active_account_write before insert or update on storage.objects
for each row execute function fitlog_private.active_account_write();

create or replace function fitlog_private.are_friends(a uuid,b uuid) returns boolean
language sql stable security definer set search_path='' as $$
 select a is not null and b is not null and not fitlog_private.blocked(a,b)
 and exists(select 1 from public.social_profiles where id=a and deletion_started_at is null)
 and exists(select 1 from public.social_profiles where id=b and deletion_started_at is null)
 and exists(select 1 from public.friendships where status='accepted'
 and ((requester=a and recipient=b) or (requester=b and recipient=a)));
$$;
create function public.account_delete_begin(p_owner uuid) returns void
language plpgsql security definer set search_path='' as $$
begin
 update public.social_profiles set deletion_started_at=coalesce(deletion_started_at,now()) where id=p_owner;
 if not found then raise exception '账号不存在';end if;
end $$;
-- Return only the deleting account's objects. File deletion must use Storage API, not SQL.
create function public.account_delete_assets(p_owner uuid) returns jsonb
language plpgsql security definer set search_path='' as $$
begin
 if not exists(select 1 from public.social_profiles where id=p_owner and deletion_started_at is not null)
 then raise exception '账号未进入注销流程';end if;
 return coalesce((select jsonb_agg(to_jsonb(x)) from (
 select bucket_id,name from storage.objects where bucket_id in('fitlog-media','fitlog-backups')
 and name like p_owner::text||'/%' order by bucket_id,name limit 100
 ) x),'[]');
end $$;
create function public.account_delete_finish(p_owner uuid) returns void
language plpgsql security definer set search_path='' as $$
begin
 if exists(select 1 from auth.users where id=p_owner) then raise exception '身份尚未删除';end if;
 delete from public.chat_media_gc where path like p_owner::text||'/%';
end $$;
revoke all on function public.account_delete_begin(uuid),public.account_delete_assets(uuid),
 public.account_delete_finish(uuid) from public,anon,authenticated;
grant execute on function public.account_delete_begin(uuid),public.account_delete_assets(uuid),
 public.account_delete_finish(uuid) to service_role;
commit;
