-- SQL Editor as postgres; fixtures are temporary and fully rolled back.
begin;
do $$ begin
 if has_function_privilege('authenticated','public.social_chat_relay_v1(text,uuid,uuid,jsonb)','EXECUTE')
 or has_function_privilege('authenticated','public.chat_relay_cleanup()','EXECUTE')
 or has_function_privilege('anon','public.chat_relay_cleanup()','EXECUTE')
 then raise exception 'TEST_FAILED: privileged delivery helpers exposed';end if;
end $$;
select set_config('fitlog_test.a',gen_random_uuid()::text,true);
select set_config('fitlog_test.b',gen_random_uuid()::text,true);
select set_config('fitlog_test.message',gen_random_uuid()::text,true);
select set_config('fitlog_test.withdrawn',gen_random_uuid()::text,true);
insert into auth.users(id,email,aud,role)
select current_setting('fitlog_test.'||n)::uuid,current_setting('fitlog_test.'||n)||'@example.invalid','authenticated','authenticated'
from unnest(array['a','b']) n;
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
select public.friend_action(current_setting('fitlog_test.b')::uuid,'request');
select public.social_chat_relay('enable');
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
select public.friend_action(current_setting('fitlog_test.a')::uuid,'accept');
select public.social_chat_relay('enable');
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
select public.social_chat_relay('send',current_setting('fitlog_test.b')::uuid,current_setting('fitlog_test.message')::uuid,
 jsonb_build_object('body','long offline message','media_path',current_setting('fitlog_test.a')||'/chat/direct.jpg',
 'forwarded',jsonb_build_array(jsonb_build_object('sender','friend','body','forwarded picture',
 'media_path',current_setting('fitlog_test.a')||'/chat/forwarded.jpg'))));
select public.social_chat_relay('ack',p_id=>current_setting('fitlog_test.message')::uuid);
reset role;
-- Emulate a recipient staying offline for 90 days.
update public.social_messages set created_at=now()-interval '90 days'
where id=current_setting('fitlog_test.message')::uuid;
update public.chat_delivery_receipts set created_at=now()-interval '90 days',expires_at=now()-interval '83 days'
where id=current_setting('fitlog_test.message')::uuid;
insert into storage.objects(bucket_id,name,created_at)
select 'fitlog-media',current_setting('fitlog_test.a')||'/chat/'||name,now()-interval '90 days'
from unnest(array['direct.jpg','forwarded.jpg','orphan.jpg']) name;
-- Even an already queued GC candidate must stay protected while referenced.
insert into public.chat_media_gc(path) values(current_setting('fitlog_test.a')||'/chat/direct.jpg');
do $$ declare candidates jsonb;
begin
 candidates:=public.chat_relay_cleanup();
 if not exists(select 1 from public.social_messages where id=current_setting('fitlog_test.message')::uuid and delivery_expires_at is null)
 then raise exception 'TEST_FAILED: long-offline message missing or finite';end if;
 if not exists(select 1 from public.chat_delivery_receipts where id=current_setting('fitlog_test.message')::uuid)
 then raise exception 'TEST_FAILED: pending retry receipt expired';end if;
 if candidates ? (current_setting('fitlog_test.a')||'/chat/direct.jpg')
 or candidates ? (current_setting('fitlog_test.a')||'/chat/forwarded.jpg')
 then raise exception 'TEST_FAILED: pending attachment selected for deletion';end if;
 if not candidates ? (current_setting('fitlog_test.a')||'/chat/orphan.jpg')
 then raise exception 'TEST_FAILED: orphan cleanup stopped';end if;
end $$;
-- A retry after 90 days must reuse the existing envelope.
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
select public.social_chat_relay('send',current_setting('fitlog_test.b')::uuid,current_setting('fitlog_test.message')::uuid,
 '{"body":"retry must not replace the original"}');
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
do $$ declare inbox jsonb;
begin
 inbox:=public.social_chat_relay('pull');
 if jsonb_array_length(inbox)<>1 or inbox->0->>'body'<>'long offline message'
 or inbox->0->>'media_path'<>current_setting('fitlog_test.a')||'/chat/direct.jpg'
 or inbox->0->'forwarded'->0->>'media_path'<>current_setting('fitlog_test.a')||'/chat/forwarded.jpg'
 then raise exception 'TEST_FAILED: delayed content or attachments unavailable';end if;
end $$;
select public.social_chat_relay('ack',p_id=>current_setting('fitlog_test.message')::uuid);
reset role;
do $$ declare candidates jsonb;
begin
 if exists(select 1 from public.social_messages where id=current_setting('fitlog_test.message')::uuid)
 then raise exception 'TEST_FAILED: delivered content retained';end if;
 candidates:=public.chat_relay_cleanup();
 if not candidates ? (current_setting('fitlog_test.a')||'/chat/direct.jpg')
 or not candidates ? (current_setting('fitlog_test.a')||'/chat/forwarded.jpg')
 then raise exception 'TEST_FAILED: delivered attachment not collectible';end if;
end $$;
-- A withdrawal notice must also survive a long offline interval.
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
select public.social_chat_relay('send',current_setting('fitlog_test.b')::uuid,current_setting('fitlog_test.withdrawn')::uuid,
 '{"body":"withdraw this"}');
select public.social_chat_relay('ack',p_id=>current_setting('fitlog_test.withdrawn')::uuid);
select public.social_chat_relay('withdraw',p_id=>current_setting('fitlog_test.withdrawn')::uuid);
reset role;
update public.social_messages set created_at=now()-interval '90 days' where id=current_setting('fitlog_test.withdrawn')::uuid;
update public.chat_delivery_receipts set expires_at=now()-interval '83 days' where id=current_setting('fitlog_test.withdrawn')::uuid;
select public.chat_relay_cleanup();
do $$ begin
 if not exists(select 1 from public.social_messages where id=current_setting('fitlog_test.withdrawn')::uuid
 and withdrawn and delivery_expires_at is null)
 then raise exception 'TEST_FAILED: withdrawal notice expired';end if;
end $$;
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
do $$ begin
 if not coalesce((public.social_chat_relay('pull')->0->>'withdrawn')::boolean,false)
 then raise exception 'TEST_FAILED: delayed withdrawal missing';end if;
end $$;
select public.social_chat_relay('ack',p_id=>current_setting('fitlog_test.withdrawn')::uuid);
reset role;
do $$ begin
 if exists(select 1 from public.social_messages where id=current_setting('fitlog_test.withdrawn')::uuid)
 then raise exception 'TEST_FAILED: acknowledged withdrawal retained';end if;
end $$;
rollback;
select 'Persistent delivery assertions passed; fixtures rolled back' as result;
