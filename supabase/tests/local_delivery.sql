-- SQL Editor as postgres; temporary fixtures are rolled back.
begin;
select set_config('fitlog_test.a',gen_random_uuid()::text,true);
select set_config('fitlog_test.b',gen_random_uuid()::text,true);
select set_config('fitlog_test.c',gen_random_uuid()::text,true);
select set_config('fitlog_test.m',gen_random_uuid()::text,true);
insert into auth.users(id,email,aud,role)
select current_setting('fitlog_test.'||n)::uuid,current_setting('fitlog_test.'||n)||'@example.invalid','authenticated','authenticated'
from unnest(array['a','b','c']) n;
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
select public.friend_action(current_setting('fitlog_test.b')::uuid,'request');
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
select public.friend_action(current_setting('fitlog_test.a')::uuid,'accept');
-- Old history is preserved until both participants download it.
select public.social_chat('send',current_setting('fitlog_test.a')::uuid,current_setting('fitlog_test.m')::uuid,'legacy history');
select public.social_chat_relay('enable');
select public.social_chat_relay('ack',p_id=>current_setting('fitlog_test.m')::uuid);
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
select public.social_chat_relay('enable');
do $$ begin
 if public.social_chat_relay('pull')->0->>'body'<>'legacy history' then raise exception 'TEST_FAILED: old history missing';end if;
end $$;
select public.social_chat_relay('ack',p_id=>current_setting('fitlog_test.m')::uuid);
reset role;
do $$ begin
 if exists(select 1 from public.social_messages where id=current_setting('fitlog_test.m')::uuid) then raise exception 'TEST_FAILED: content retained after both ACKs';end if;
end $$;
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
select set_config('fitlog_test.new',gen_random_uuid()::text,true);
select public.social_chat_relay('send',current_setting('fitlog_test.b')::uuid,current_setting('fitlog_test.new')::uuid,'{"body":"new local message"}');
select public.social_chat_relay('ack',p_id=>current_setting('fitlog_test.new')::uuid);
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.c'),true);
select public.social_chat_relay('ack',p_id=>current_setting('fitlog_test.new')::uuid);
do $$ begin
 if jsonb_array_length(public.social_chat_relay('pull'))<>0 then raise exception 'TEST_FAILED: stranger read inbox';end if;
 begin
  perform public.social_chat_relay('send',current_setting('fitlog_test.b')::uuid,gen_random_uuid(),'{"body":"stranger"}');
  raise exception 'TEST_FAILED: stranger send allowed';
 exception when others then if sqlerrm like 'TEST_FAILED:%' then raise;end if;end;
end $$;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
do $$ begin
 if jsonb_array_length(public.social_chat_relay('pull'))<>1 then raise exception 'TEST_FAILED: delivery missing';end if;
end $$;
select public.social_chat_relay('ack',p_id=>current_setting('fitlog_test.new')::uuid);
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
-- An ACK-lost retry must not redeliver content.
select public.social_chat_relay('send',current_setting('fitlog_test.b')::uuid,current_setting('fitlog_test.new')::uuid,'{"body":"new local message"}');
select public.social_chat_relay('withdraw',p_id=>current_setting('fitlog_test.new')::uuid);
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
do $$ begin
 if not (public.social_chat_relay('pull')->0->>'withdrawn')::boolean then raise exception 'TEST_FAILED: withdrawal missing';end if;
end $$;
select public.social_chat_relay('ack',p_id=>current_setting('fitlog_test.new')::uuid);
-- Backups are private by owner and update bucket is never client-writable.
reset role;
do $$ begin
 if not exists(select 1 from storage.buckets where id='fitlog-backups' and not public)
 then raise exception 'TEST_FAILED: backup bucket missing or public';end if;
end $$;
set local role authenticated;
do $$ begin
 begin
  insert into storage.objects(bucket_id,name) values('fitlog-backups',current_setting('fitlog_test.a')||'/bad.zip');
  raise exception 'TEST_FAILED: other account backup write';
 exception when insufficient_privilege then null;end;
 begin
  insert into storage.objects(bucket_id,name) values('fitlog-updates','latest.json');
  raise exception 'TEST_FAILED: client update publication';
 exception when insufficient_privilege then null;end;
end $$;
reset role;
select set_config('request.jwt.claim.sub','',true);
-- A legacy participant who deleted their copy must not keep the other participant's content in the cloud.
reset role;
select set_config('fitlog_test.hidden',gen_random_uuid()::text,true);
insert into public.social_messages(id,sender,recipient,body) values(current_setting('fitlog_test.hidden')::uuid,
 current_setting('fitlog_test.a')::uuid,current_setting('fitlog_test.b')::uuid,'discarded legacy');
insert into public.social_message_hides values(current_setting('fitlog_test.b')::uuid,current_setting('fitlog_test.hidden')::uuid);
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
select public.social_chat_relay('ack',p_id=>current_setting('fitlog_test.hidden')::uuid);
reset role;
do $$ begin
 if exists(select 1 from public.social_messages where id=current_setting('fitlog_test.hidden')::uuid)
 then raise exception 'TEST_FAILED: hidden legacy content retained';end if;
end $$;
-- Cleanup must never remove undelivered messages, even if a legacy deadline is present.
insert into public.social_messages(id,sender,recipient,body,delivery_expires_at)
 values(gen_random_uuid(),current_setting('fitlog_test.a')::uuid,current_setting('fitlog_test.b')::uuid,'expired',now()-interval '1 second');
select public.chat_relay_cleanup();
do $$ begin
 if not exists(select 1 from public.social_messages where body='expired') then raise exception 'TEST_FAILED: undelivered content removed';end if;
end $$;
rollback;
select 'Local delivery and backup permission assertions passed; fixtures rolled back' as result;
