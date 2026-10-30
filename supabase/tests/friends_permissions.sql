-- Run in Supabase SQL Editor as postgres. All fixtures are rolled back.
begin;
select set_config('fitlog_test.a', gen_random_uuid()::text, true);
select set_config('fitlog_test.b', gen_random_uuid()::text, true);
insert into auth.users(id,email,aud,role) values
 (current_setting('fitlog_test.a')::uuid, current_setting('fitlog_test.a')||'@example.invalid','authenticated','authenticated'),
 (current_setting('fitlog_test.b')::uuid, current_setting('fitlog_test.b')||'@example.invalid','authenticated','authenticated');
set local role authenticated;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
do $$
declare a uuid:=current_setting('fitlog_test.a')::uuid;
        b uuid:=current_setting('fitlog_test.b')::uuid;
begin
  if (select count(*) from public.social_profiles where id in(a,b))<>1 then
    raise exception 'TEST_FAILED: non-friend profile leaked';
  end if;
  if exists(select 1 from public.social_privacy where share_history or share_live) then
    raise exception 'TEST_FAILED: sharing enabled by default';
  end if;
  perform public.friend_action(b,'request');
  begin
    perform public.friend_action(b,'accept');
    raise exception 'TEST_FAILED: sender accepted own request';
  exception when others then
    if sqlerrm like 'TEST_FAILED:%' then raise; end if;
  end;
  begin
    update public.social_profiles set id=b where id=a;
    raise exception 'TEST_FAILED: profile identity mutable';
  exception when insufficient_privilege then null;
  end;
end;
$$;
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.b'),true);
select public.friend_action(current_setting('fitlog_test.a')::uuid,'accept');
insert into public.friend_notes(owner_id,peer_id,note) values
 (current_setting('fitlog_test.b')::uuid,current_setting('fitlog_test.a')::uuid,'private note');
select set_config('request.jwt.claim.sub',current_setting('fitlog_test.a'),true);
do $$
declare a uuid:=current_setting('fitlog_test.a')::uuid;
        b uuid:=current_setting('fitlog_test.b')::uuid;
begin
  if not fitlog_private.are_friends(a,b) then raise exception 'TEST_FAILED: acceptance failed'; end if;
  if exists(select 1 from public.friend_notes where owner_id=b) then raise exception 'TEST_FAILED: remark leaked'; end if;
  if jsonb_array_length(public.list_fitlog_friends())<>1 then raise exception 'TEST_FAILED: friend list'; end if;
  perform public.friend_action(b,'block');
  if fitlog_private.are_friends(a,b) then raise exception 'TEST_FAILED: block failed'; end if;
  if jsonb_array_length(public.list_fitlog_friends())<>0 then raise exception 'TEST_FAILED: blocked friend visible'; end if;
end;
$$;
rollback;
select 'Friend permission assertions passed; fixtures rolled back' as result;
