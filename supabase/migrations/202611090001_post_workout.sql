-- A post shared from a finished workout carries its training card as JSON beside the plain-text body, which older
-- apps keep showing. The card is attached by its author right after publishing, once, and is read through the
-- existing feed (it selects p.*). Run after 202611080002_profile_banner.sql.
begin;

alter table public.social_posts add column if not exists workout jsonb;
alter table public.social_posts drop constraint if exists social_post_workout_shape;
alter table public.social_posts add constraint social_post_workout_shape
 check(workout is null or (jsonb_typeof(workout)='object' and pg_column_size(workout)<=32768));

create or replace function public.social_post_workout(p_id uuid,p_workout jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare me uuid:=auth.uid();
begin
 if me is null then raise exception '请先登录'; end if;
 if p_workout is null or jsonb_typeof(p_workout)<>'object' or pg_column_size(p_workout)>32768
  or jsonb_typeof(p_workout->'exercises') is distinct from 'array' then raise exception '训练卡内容无效'; end if;
 -- Only one's own, still visible post; repeating the same card is harmless, replacing it is not allowed.
 update public.social_posts set workout=p_workout
  where id=p_id and owner_id=me and not deleted and (workout is null or workout=p_workout);
 if not found then raise exception '动态不存在或已带训练卡'; end if;
 return '{}';
end $$;
revoke all on function public.social_post_workout(uuid,jsonb) from public,anon;
grant execute on function public.social_post_workout(uuid,jsonb) to authenticated;

commit;
