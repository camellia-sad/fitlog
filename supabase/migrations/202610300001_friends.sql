begin;

create schema if not exists fitlog_private;
revoke all on schema fitlog_private from public;
grant usage on schema fitlog_private to authenticated;

create table public.social_profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  fitlog_id text not null unique default replace(gen_random_uuid()::text, '-', ''),
  nickname text not null default 'FitLog 用户' check (char_length(nickname) between 1 and 40),
  avatar_path text,
  bio text not null default '' check (char_length(bio) <= 300),
  created_at timestamptz not null default now()
);

create table public.friendships (
  id uuid primary key default gen_random_uuid(),
  requester uuid not null references public.social_profiles(id) on delete cascade,
  recipient uuid not null references public.social_profiles(id) on delete cascade,
  status text not null default 'pending' check (status in ('pending', 'accepted')),
  created_at timestamptz not null default now(),
  accepted_at timestamptz,
  check (requester <> recipient)
);
create unique index friendships_pair on public.friendships
  (least(requester, recipient), greatest(requester, recipient));
create index friendships_requester on public.friendships(requester);
create index friendships_recipient on public.friendships(recipient);

create table public.friend_blocks (
  owner_id uuid not null references public.social_profiles(id) on delete cascade,
  peer_id uuid not null references public.social_profiles(id) on delete cascade,
  primary key (owner_id, peer_id),
  check (owner_id <> peer_id)
);
create table public.friend_notes (
  owner_id uuid not null references public.social_profiles(id) on delete cascade,
  peer_id uuid not null references public.social_profiles(id) on delete cascade,
  note text not null default '' check (char_length(note) <= 40),
  primary key (owner_id, peer_id),
  check (owner_id <> peer_id)
);
create table public.social_privacy (
  owner_id uuid primary key references public.social_profiles(id) on delete cascade,
  share_history boolean not null default false,
  share_live boolean not null default false
);

create function fitlog_private.bootstrap_user() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.social_profiles(id) values (new.id);
  insert into public.social_privacy(owner_id) values (new.id);
  return new;
end;
$$;
create trigger fitlog_social_signup after insert on auth.users
for each row execute function fitlog_private.bootstrap_user();
insert into public.social_profiles(id) select id from auth.users on conflict (id) do nothing;
insert into public.social_privacy(owner_id) select id from public.social_profiles on conflict do nothing;

create function fitlog_private.blocked(a uuid, b uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists(select 1 from public.friend_blocks
    where (owner_id = a and peer_id = b) or (owner_id = b and peer_id = a));
$$;
create function fitlog_private.are_friends(a uuid, b uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select a is not null and b is not null and not fitlog_private.blocked(a,b)
    and exists(select 1 from public.friendships where status = 'accepted'
      and ((requester=a and recipient=b) or (requester=b and recipient=a)));
$$;
revoke all on all functions in schema fitlog_private from public;
grant execute on function fitlog_private.blocked(uuid,uuid),
  fitlog_private.are_friends(uuid,uuid) to authenticated;

alter table public.social_profiles enable row level security;
alter table public.friendships enable row level security;
alter table public.friend_blocks enable row level security;
alter table public.friend_notes enable row level security;
alter table public.social_privacy enable row level security;
revoke all on public.social_profiles, public.friendships, public.friend_blocks,
  public.friend_notes, public.social_privacy from anon, authenticated;
grant select on public.social_profiles, public.friendships, public.friend_blocks,
  public.friend_notes, public.social_privacy to authenticated;
grant update(nickname,avatar_path,bio) on public.social_profiles to authenticated;
grant update(share_history,share_live) on public.social_privacy to authenticated;
grant insert,update,delete on public.friend_notes to authenticated;

create policy profile_read on public.social_profiles for select to authenticated
  using (id=auth.uid() or fitlog_private.are_friends(auth.uid(),id));
create policy profile_edit on public.social_profiles for update to authenticated
  using (id=auth.uid()) with check (id=auth.uid());
create policy friendship_read on public.friendships for select to authenticated
  using ((auth.uid()=requester or auth.uid()=recipient)
    and not fitlog_private.blocked(requester,recipient));
create policy block_read on public.friend_blocks for select to authenticated using(owner_id=auth.uid());
create policy note_access on public.friend_notes for all to authenticated
  using (owner_id=auth.uid()) with check(owner_id=auth.uid() and fitlog_private.are_friends(owner_id,peer_id));
create policy privacy_read on public.social_privacy for select to authenticated using(owner_id=auth.uid());
create policy privacy_edit on public.social_privacy for update to authenticated
  using(owner_id=auth.uid()) with check(owner_id=auth.uid());

-- Exact ID lookup exposes only the minimal identity needed to add a friend.
create function public.find_fitlog_user(p_fitlog_id text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare result jsonb;
begin
  if auth.uid() is null then raise exception '请先登录'; end if;
  select jsonb_build_object('id',id,'fitlog_id',fitlog_id,'nickname',nickname) into result
    from public.social_profiles where fitlog_id=lower(trim(p_fitlog_id))
      and id<>auth.uid() and not fitlog_private.blocked(auth.uid(),id);
  return result;
end;
$$;

create function public.friend_action(p_peer uuid, p_action text) returns void
language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid(); row_value public.friendships;
begin
  if me is null or p_peer is null or me=p_peer then raise exception '无效的好友操作'; end if;
  -- Serialize requests, accepts, blocks and removals for the same pair.
  perform pg_advisory_xact_lock(hashtextextended(
    least(me,p_peer)::text || greatest(me,p_peer)::text,0));
  if p_action='unblock' then
    delete from public.friend_blocks where owner_id=me and peer_id=p_peer;
    return;
  end if;
  if p_action='block' then
    insert into public.friend_blocks values(me,p_peer) on conflict do nothing;
    delete from public.friendships where (requester=me and recipient=p_peer) or (requester=p_peer and recipient=me);
    delete from public.friend_notes where (owner_id=me and peer_id=p_peer) or (owner_id=p_peer and peer_id=me);
    return;
  end if;
  if fitlog_private.blocked(me,p_peer) then raise exception '暂时无法操作该好友'; end if;
  select * into row_value from public.friendships
    where (requester=me and recipient=p_peer) or (requester=p_peer and recipient=me) for update;
  case p_action
    when 'request' then
      if row_value.id is not null then raise exception '已有好友关系或待处理申请'; end if;
      insert into public.friendships(requester,recipient) values(me,p_peer);
    when 'accept' then
      if row_value.id is null or row_value.recipient<>me or row_value.status<>'pending' then
        raise exception '没有可接受的好友申请';
      end if;
      update public.friendships set status='accepted',accepted_at=now() where id=row_value.id;
    when 'reject' then
      if row_value.id is null or row_value.recipient<>me or row_value.status<>'pending' then
        raise exception '没有可拒绝的好友申请';
      end if;
      delete from public.friendships where id=row_value.id;
    when 'cancel' then
      if row_value.id is null or row_value.requester<>me or row_value.status<>'pending' then
        raise exception '没有可取消的好友申请';
      end if;
      delete from public.friendships where id=row_value.id;
    when 'remove' then
      delete from public.friendships where id=row_value.id;
      delete from public.friend_notes where (owner_id=me and peer_id=p_peer) or (owner_id=p_peer and peer_id=me);
    else raise exception '未知的好友操作';
  end case;
end;
$$;

create function public.list_fitlog_friends() returns jsonb
language plpgsql security definer set search_path = '' as $$
declare me uuid := auth.uid();
begin
  if me is null then raise exception '请先登录'; end if;
  return coalesce((select jsonb_agg(jsonb_build_object(
    'id',p.id,'fitlog_id',p.fitlog_id,'nickname',p.nickname,'avatar_path',p.avatar_path,
    'bio',p.bio,'note',coalesce(n.note,''),'status',f.status,'incoming',f.recipient=me
  ) order by f.created_at desc) from public.friendships f
    join public.social_profiles p on p.id=case when f.requester=me then f.recipient else f.requester end
    left join public.friend_notes n on n.owner_id=me and n.peer_id=p.id
    where (f.requester=me or f.recipient=me) and not fitlog_private.blocked(me,p.id)), '[]'::jsonb);
end;
$$;

revoke all on function public.find_fitlog_user(text), public.friend_action(uuid,text),
  public.list_fitlog_friends() from public, anon;
grant execute on function public.find_fitlog_user(text), public.friend_action(uuid,text),
  public.list_fitlog_friends() to authenticated;

commit;
