-- Execute in SQL Editor as postgres after 202611080002_profile_banner.sql; all fixtures are rolled back.
begin;
select set_config('fitlog_test.a',gen_random_uuid()::text,true);
select set_config('fitlog_test.b',gen_random_uuid()::text,true);
select set_config('fitlog_test.c',gen_random_uuid()::text,true);
insert into auth.users(id,email,aud,role)
select current_setting('fitlog_test.'||n)::uuid,current_setting('fitlog_test.'||n)||'@example.invalid','authenticated','authenticated'
from unnest(array['a','b','c']) n;
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
select public.friend_action(current_setting('fitlog_test.b')::uuid,'request');
update public.social_profiles set banner=current_setting('fitlog_test.a')||'/first.jpg' where id=current_setting('fitlog_test.a')::uuid;
do $$ begin
 begin
  update public.social_profiles set banner=current_setting('fitlog_test.b')||'/theirs.jpg' where id=current_setting('fitlog_test.a')::uuid;
  raise exception 'TEST_FAILED: a banner may point at another user''s image';
 exception when check_violation then null; end;
 begin
  update public.social_profiles set banner='beach' where id=current_setting('fitlog_test.a')::uuid;
  raise exception 'TEST_FAILED: an unknown scene was accepted';
 exception when check_violation then null; end;
end $$;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
select public.friend_action(current_setting('fitlog_test.a')::uuid,'accept');
do $$ begin
 if (public.social_person(current_setting('fitlog_test.a')::uuid)->>'banner') is distinct from current_setting('fitlog_test.a')||'/first.jpg'
 then raise exception 'TEST_FAILED: a friend does not receive the banner';end if;
 if not fitlog_private.media_visible(current_setting('fitlog_test.a')||'/first.jpg',current_setting('fitlog_test.b')::uuid)
 then raise exception 'TEST_FAILED: a friend cannot read the banner image';end if;
 if fitlog_private.media_visible(current_setting('fitlog_test.a')||'/first.jpg',current_setting('fitlog_test.c')::uuid)
 then raise exception 'TEST_FAILED: a stranger can read the banner image';end if;
end $$;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
update public.social_profiles set banner='gym' where id=current_setting('fitlog_test.a')::uuid;
reset role;
do $$ begin
 if not exists(select 1 from public.chat_media_gc where path=current_setting('fitlog_test.a')||'/first.jpg')
 then raise exception 'TEST_FAILED: the replaced banner image was not queued for deletion';end if;
end $$;
rollback;
select 'Profile banner assertions passed; fixtures rolled back' as result;
