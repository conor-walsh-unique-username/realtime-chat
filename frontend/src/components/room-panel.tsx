"use client";

import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { roomTransport } from "@/lib/api";
import { RoomFeed } from "@/lib/room-feed";
import type { Room, User } from "@/lib/types";

const labels = { idle: "Checking access", connecting: "Connecting", loading: "Loading messages", live: "Live", reconnecting: "Reconnecting", offline: "Offline", blocked: "Not connected" };

export function RoomPanel({ room, user, onSessionEnded }: { room: Room; user: User; onSessionEnded(): void }) {
  // Keep the feed across renders; React subscribes to it rather than owning the socket directly.
  const feed = useMemo(() => new RoomFeed(room.id, user.id, roomTransport, onSessionEnded), [room.id, user.id, onSessionEnded]);
  const state = useSyncExternalStore(feed.subscribe, feed.getSnapshot, feed.getServerSnapshot);
  const [draft, setDraft] = useState("");
  const list = useRef<HTMLDivElement>(null);
  const nearBottom = useRef(true);
  const latest = state.messages.at(-1);
  const latestId = latest?.id;
  const latestSender = latest?.senderId;
  useEffect(() => { feed.start(); return () => feed.stop(); }, [feed]);
  useEffect(() => {
    // Follow new messages near the bottom or after our own send; let someone reading above stay put.
    if (list.current && (nearBottom.current || latestSender === user.id)) list.current.scrollTop = list.current.scrollHeight;
  }, [latestId, latestSender, user.id]);

  async function send(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const content = draft;
    if (await feed.send(content)) {
      // Preserve anything the user typed while the POST was in flight.
      setDraft(current => current === content ? "" : current);
    }
  }
  async function older() {
    const previousHeight = list.current?.scrollHeight || 0;
    const previousTop = list.current?.scrollTop || 0;
    await feed.loadOlder();
    requestAnimationFrame(() => {
      // Keep the visible messages in place when older rows are prepended.
      if (list.current) list.current.scrollTop = previousTop + list.current.scrollHeight - previousHeight;
    });
  }

  return <section className="room-panel" aria-labelledby="room-title">
    <header className="room-heading"><div><h1 id="room-title"><span aria-hidden="true"># </span>{room.name}</h1>
      <span className={`connection ${state.connection === "live" ? "connected" : ""}`} role="status">{labels[state.connection]}</span></div>
      {state.membership === "member" && <button onClick={() => void feed.leave()} disabled={state.membershipBusy || state.sending}>Leave room</button>}
    </header>
    <div className="conversation" ref={list} onScroll={() => {
      const element = list.current;
      if (element) nearBottom.current = element.scrollHeight - element.scrollTop - element.clientHeight < 120;
    }}>
      {state.error && <p className="alert" role="alert">{state.error}</p>}
      {state.membership === "outside" ? <div className="join-card"><h2>Join the conversation</h2><p>Join this room to read its history and send messages.</p>
        <button className="primary" onClick={() => void feed.join()} disabled={state.membershipBusy}>{state.membershipBusy ? "Joining…" : "Join room"}</button></div> : <>
        {state.connection !== "live" && <div className="connection-note"><p>{state.membership === "checking" ? "Checking your room access…" : labels[state.connection] + "…"}</p>
          <button onClick={() => feed.retryNow()} disabled={state.membershipBusy || state.sending}>Retry connection</button></div>}
        {state.nextBefore !== null && <button className="older" disabled={state.loadingOlder || state.connection !== "live"} onClick={() => void older()}>{state.loadingOlder ? "Loading…" : "Load older messages"}</button>}
        {state.historyLoaded && state.messages.length === 0 && <div className="join-card"><h2>You’re first here</h2><p>Send a message to start the conversation.</p></div>}
        <ol className="messages" aria-label="Messages" aria-live="polite" aria-relevant="additions text">
          {state.messages.map(message => <li key={message.id} className={`message ${message.senderId === user.id ? "mine" : ""}`}>
            <div className="message-meta"><strong>{message.senderId === user.id ? "You" : message.senderUsername}</strong>
              <time dateTime={message.createdAt} title={new Date(message.createdAt).toLocaleString()}>{new Date(message.createdAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</time></div>
            <p>{message.content}</p>
          </li>)}
        </ol>
      </>}
    </div>
    <form className="composer" onSubmit={send}>
      <label className="sr-only" htmlFor="message-draft">Message</label>
      <textarea id="message-draft" rows={2} maxLength={2000} placeholder={state.membership === "outside" ? "Join the room to send messages" : "Write a message…"}
        value={draft} onChange={event => setDraft(event.target.value)} disabled={state.membership !== "member" || !state.historyLoaded}
        onKeyDown={event => {
          // Composing can mean choosing a character with an input method; Enter should finish that first.
          if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
            event.preventDefault();
            if (!state.sending) event.currentTarget.closest("form")?.requestSubmit();
          }
        }} />
      <div className="composer-footer"><small>Enter to send · Shift+Enter for a new line · {draft.length}/2000</small>
        <button className="primary" disabled={state.sending || state.membershipBusy || state.connection !== "live" || !draft.trim()}>{state.sending ? "Sending…" : "Send message"}</button></div>
    </form>
  </section>;
}
