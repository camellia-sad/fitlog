-- SQL Editor, postgres role. Temporary users/content are rolled back; no email is sent.
begin;
select set_config('fitlog_test.a',gen_random_uuid()::text,true);
select set_config('fitlog_test.b',gen_random_uuid()::text,true);
select set_config('fitlog_test.c',gen_random_uuid()::text,true);
select set_config('fitlog_test.m',gen_random_uuid()::text,true);
select set_config('fitlog_test.q',gen_random_uuid()::text,true);
select set_config('fitlog_test.p',gen_random_uuid()::text,true);
insert into auth.users(id,email,aud,role)
select current_setting('fitlog_test.'||n)::uuid,current_setting('fitlog_test.'||n)||'@example.invalid','authenticated','authenticated'
from unnest(array['a','b','c']) n;
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
select public.friend_action(current_setting('fitlog_test.b')::uuid,'request');
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
select public.friend_action(current_setting('fitlog_test.a')::uuid,'accept');
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
select public.social_chat('send',current_setting('fitlog_test.b')::uuid,current_setting('fitlog_test.m')::uuid,'original');
select public.social_chat('send',current_setting('fitlog_test.b')::uuid,current_setting('fitlog_test.m')::uuid,'original');
do $$
begin
 if jsonb_array_length(public.social_chat('list',current_setting('fitlog_test.b')::uuid))<>1 then
  raise exception 'TEST_FAILED: send retry duplicated'; end if;
end $$;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
select public.social_chat('delete',current_setting('fitlog_test.a')::uuid,current_setting('fitlog_test.m')::uuid);
do $$
begin
 if jsonb_array_length(public.social_chat('list',current_setting('fitlog_test.a')::uuid))<>0 then
  raise exception 'TEST_FAILED: own deletion reappeared'; end if;
 begin
  perform public.social_chat('withdraw',current_setting('fitlog_test.a')::uuid,current_setting('fitlog_test.m')::uuid);
  raise exception 'TEST_FAILED: receiver withdrew sender message';
 exception when others then if sqlerrm like 'TEST_FAILED:%' then raise; end if; end;
 begin
  perform count(*) from public.social_messages;
  raise exception 'TEST_FAILED: direct table readable';
 exception when insufficient_privilege then null; end;
end $$;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
do $$
begin
 if jsonb_array_length(public.social_chat('list',current_setting('fitlog_test.b')::uuid))<>1 then
  raise exception 'TEST_FAILED: own deletion affected recipient'; end if;
end $$;
select public.social_chat('send',current_setting('fitlog_test.b')::uuid,current_setting('fitlog_test.q')::uuid,'reply',
 p_reply=>current_setting('fitlog_test.m')::uuid);
select public.social_chat('withdraw',current_setting('fitlog_test.b')::uuid,current_setting('fitlog_test.m')::uuid);
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
do $$
declare rows jsonb;
begin
 rows:=public.social_chat('list',current_setting('fitlog_test.a')::uuid);
 if rows->0->>'reply_preview'<>'[原消息不可用]' then raise exception 'TEST_FAILED: withdrawn quote leaked'; end if;
 if jsonb_array_length(public.social_chat_context(current_setting('fitlog_test.a')::uuid,current_setting('fitlog_test.q')::uuid))<>1
 then raise exception 'TEST_FAILED: quote context'; end if;
end $$;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.c'),true);
do $$
begin
 if jsonb_array_length(public.social_chat('list',current_setting('fitlog_test.a')::uuid))<>0 then raise exception 'TEST_FAILED: third party chat'; end if;
 begin
  perform public.social_chat_context(current_setting('fitlog_test.a')::uuid,current_setting('fitlog_test.q')::uuid);
  raise exception 'TEST_FAILED: third party quote context';
 exception when others then if sqlerrm like 'TEST_FAILED:%' then raise; end if; end;
end $$;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
select public.social_feed('publish',current_setting('fitlog_test.p')::uuid,'post');
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
do $$
declare p uuid:=current_setting('fitlog_test.p')::uuid; comment_id uuid:=gen_random_uuid();
begin
 if jsonb_array_length(public.social_feed('list'))<>1 then raise exception 'TEST_FAILED: friend feed'; end if;
 perform public.social_feed('like',p);
 perform public.social_feed('like',p);
 perform public.social_feed('comment',p,'comment',p_comment_id=>comment_id);
 perform public.social_feed('comment',p,'comment',p_comment_id=>comment_id);
 if (public.social_feed('list')->0->>'like_count')::int<>1 or jsonb_array_length(public.social_feed('comments',p))<>1
 then raise exception 'TEST_FAILED: likes or comments retry duplicated'; end if;
 begin
  perform public.social_feed('delete',p); raise exception 'TEST_FAILED: deleted other post';
 exception when others then if sqlerrm like 'TEST_FAILED:%' then raise; end if; end;
end $$;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.c'),true);
do $$
begin
 if jsonb_array_length(public.social_feed('list'))<>0 then raise exception 'TEST_FAILED: stranger feed'; end if;
 begin
  perform public.social_feed('like',current_setting('fitlog_test.p')::uuid);
  raise exception 'TEST_FAILED: stranger likes';
 exception when others then if sqlerrm like 'TEST_FAILED:%' then raise; end if; end;
end $$;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
update public.social_privacy set share_live=true,share_history=true where owner_id=auth.uid();
select public.social_training('heartbeat',p_device=>gen_random_uuid(),p_status=>'training',p_snapshot=>'{"exercise":"test","session_id":"test"}');
select public.social_training('upload',p_local_id=>'local-test',p_snapshot=>'{"started_at":"2026-10-30T00:00:00Z","label":"test"}');
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
do $$
declare a uuid:=current_setting('fitlog_test.a')::uuid;
begin
 if public.social_person(a)->>'status'<>'training' then raise exception 'TEST_FAILED: live sharing'; end if;
 if jsonb_array_length(public.social_training('history',p_peer=>a))<>1 then raise exception 'TEST_FAILED: history sharing'; end if;
end $$;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
update public.social_privacy set share_live=false,share_history=false where owner_id=auth.uid();
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
do $$
declare a uuid:=current_setting('fitlog_test.a')::uuid;
begin
 if public.social_person(a)->>'status'<>'online' or public.social_person(a)->'live'<>'{}'::jsonb
 then raise exception 'TEST_FAILED: closing live sharing left detail visible'; end if;
 if jsonb_array_length(public.social_training('history',p_peer=>a))<>0 then raise exception 'TEST_FAILED: closing history sharing'; end if;
 perform public.friend_action(a,'block');
 if jsonb_array_length(public.social_feed('list'))<>0 then raise exception 'TEST_FAILED: blocked feed'; end if;
 begin
  perform public.social_person(a);raise exception 'TEST_FAILED: blocked profile';
 exception when others then if sqlerrm like 'TEST_FAILED:%' then raise; end if; end;
end $$;
rollback;
select 'Social permission assertions passed; fixtures rolled back' as result;
