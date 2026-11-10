-- Execute in SQL Editor as postgres after 202611090001_post_workout.sql; all fixtures are rolled back.
begin;
select set_config('fitlog_test.a',gen_random_uuid()::text,true);
select set_config('fitlog_test.b',gen_random_uuid()::text,true);
select set_config('fitlog_test.post',gen_random_uuid()::text,true);
insert into auth.users(id,email,aud,role)
select current_setting('fitlog_test.'||n)::uuid,current_setting('fitlog_test.'||n)||'@example.invalid','authenticated','authenticated'
from unnest(array['a','b']) n;
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
select public.friend_action(current_setting('fitlog_test.b')::uuid,'request');
select public.social_feed('publish',current_setting('fitlog_test.post')::uuid,'推日 · 已完成');
select public.social_post_workout(current_setting('fitlog_test.post')::uuid,'{"v":1,"label":"推日","exercises":[]}'::jsonb);
-- The same card again is accepted; a different one is not.
select public.social_post_workout(current_setting('fitlog_test.post')::uuid,'{"v":1,"label":"推日","exercises":[]}'::jsonb);
do $$ begin
 begin
  perform public.social_post_workout(current_setting('fitlog_test.post')::uuid,'{"v":1,"label":"改过","exercises":[]}'::jsonb);
  raise exception 'TEST_FAILED: a card was replaced';
 exception when raise_exception then if sqlerrm like 'TEST_FAILED%' then raise; end if; end;
end $$;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
select public.friend_action(current_setting('fitlog_test.a')::uuid,'accept');
do $$ begin
 begin
  perform public.social_post_workout(current_setting('fitlog_test.post')::uuid,'{"v":1,"label":"冒充","exercises":[]}'::jsonb);
  raise exception 'TEST_FAILED: a friend attached a card to someone else''s post';
 exception when raise_exception then if sqlerrm like 'TEST_FAILED%' then raise; end if; end;
 if (public.social_feed('list',p_peer=>current_setting('fitlog_test.a')::uuid)->0->'workout'->>'label') is distinct from '推日'
 then raise exception 'TEST_FAILED: the feed does not carry the card';end if;
end $$;
rollback;
select 'Post workout assertions passed; fixtures rolled back' as result;
