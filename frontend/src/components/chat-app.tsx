"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError, errorMessage } from "@/lib/api";
import type { Room, RoomPage, User } from "@/lib/types";
import { AuthForm } from "./auth-form";
import { RoomPanel } from "./room-panel";

export function ChatApp() {
  // undefined = still checking, null = signed out, User = ready to show the workspace.
  const [user, setUser] = useState<User | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  // Ignore an old session check if signing in/out or a newer check has overtaken it.
  const version = useRef(0);
  const invalidate = useCallback(() => { version.current++; }, []);
  const signedIn = useCallback((account: User) => {
    version.current++;
    setError(null);
    setUser(account);
  }, []);
  const sessionEnded = useCallback(() => {
    version.current++;
    setError(null);
    setUser(null);
  }, []);
  const restore = useCallback(async () => {
    const request = ++version.current;
    try {
      const account = await api.me();
      if (request !== version.current) return;
      setError(null);
      setUser(previous => previous?.id === account.id ? previous : account);
    } catch (error) {
      if (request !== version.current) return;
      if (error instanceof ApiError && error.status === 401) { setUser(null); setError(null); }
      else setError(errorMessage(error));
    }
  }, []);
  useEffect(() => {
    let active = true;
    // Let Strict Mode's initial cleanup cancel the first startup request.
    queueMicrotask(() => { if (active) void restore(); });
    // Another tab can change the shared cookie; recheck identity when returning here.
    window.addEventListener("focus", restore);
    return () => { active = false; invalidate(); window.removeEventListener("focus", restore); };
  }, [restore, invalidate]);

  if (user === undefined) return <main className="center-card"><h1>Realtime Chat</h1>
    <p role="status">{error || "Checking your session…"}</p>
    {error && <button onClick={() => void restore()}>Try again</button>}
  </main>;
  if (user === null) return <AuthForm onSignedIn={signedIn} />;
  // Remount room state when a different account takes over the shared browser session.
  return <Workspace key={user.id} user={user} onSessionEnded={sessionEnded} />;
}

function Workspace({ user, onSessionEnded }: { user: User; onSessionEnded(): void }) {
  const [page, setPage] = useState(0);
  const [revision, setRevision] = useState(0);
  const [result, setResult] = useState<{ page: number; data: RoomPage | null; error: string | null }>({ page: -1, data: null, error: null });
  const [selected, setSelected] = useState<Room | null>(null);
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const loading = result.page !== page;

  useEffect(() => {
    const controller = new AbortController();
    api.rooms(page, controller.signal).then(data => {
      if (!controller.signal.aborted) setResult({ page, data, error: null });
    }).catch(error => {
      if (controller.signal.aborted) return;
      if (error instanceof ApiError && error.status === 401) onSessionEnded();
      else setResult({ page, data: null, error: errorMessage(error) });
    });
    // A quick page change should not let the previous page's response overwrite the new one.
    return () => controller.abort();
  }, [page, revision, onSessionEnded]);

  async function createRoom(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || !name.trim()) return;
    setBusy(true); setError(null);
    try {
      const room = await api.createRoom(name);
      // New rooms go on the first page. revision refreshes that page even if we are already on it.
      setSelected(room); setName(""); setPage(0); setRevision(value => value + 1);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) onSessionEnded();
      else setError(errorMessage(error));
    } finally { setBusy(false); }
  }
  async function logout() {
    setLoggingOut(true); setError(null);
    try { await api.logout(); onSessionEnded(); }
    catch (error) {
      if (error instanceof ApiError && error.status === 401) onSessionEnded();
      else setError(errorMessage(error));
    } finally { setLoggingOut(false); }
  }

  return <main className="app-shell">
    <header className="app-header"><div><span className="brand-mark" aria-hidden="true">#</span><strong>Realtime Chat</strong></div>
      <div className="account"><span>@{user.username}</span><button onClick={() => void logout()} disabled={loggingOut}>{loggingOut ? "Signing out…" : "Sign out"}</button></div>
    </header>
    {error && <p className="alert" role="alert">{error}</p>}
    <div className="workspace">
      <aside className="sidebar" aria-label="Public rooms">
        <div className="sidebar-heading"><div><span className="eyebrow">DISCOVER</span><h2>Public rooms</h2></div>
          <button className="compact" onClick={() => setRevision(value => value + 1)} disabled={loading}>Refresh</button></div>
        <div className="room-list">
          {loading ? <p className="muted" role="status">Loading rooms…</p> : result.error ? <p className="alert" role="alert">{result.error}</p> : result.data?.items.length === 0 ? <p className="muted">No rooms yet. Start the first one.</p> :
            result.data?.items.map(room => <button key={room.id} className={`room-choice ${selected?.id === room.id ? "selected" : ""}`}
              aria-pressed={selected?.id === room.id} onClick={() => setSelected(room)} title={room.name}>
              <span aria-hidden="true">#</span><span>{room.name}</span>
            </button>)}
        </div>
        <div className="pager"><button disabled={loading || page === 0} onClick={() => setPage(value => value - 1)}>Previous</button>
          <small>Page {page + 1}{result.data ? ` of ${Math.max(1, result.data.totalPages)}` : ""}</small>
          <button disabled={loading || !result.data || page + 1 >= result.data.totalPages} onClick={() => setPage(value => value + 1)}>Next</button></div>
        <form className="create-room" onSubmit={createRoom}><label htmlFor="room-name">Start a room</label>
          <input id="room-name" placeholder="e.g. Java study group" value={name} onChange={event => setName(event.target.value)} required maxLength={80} disabled={busy} />
          <button className="primary full" disabled={busy || !name.trim()}>{busy ? "Creating…" : "Create room"}</button>
        </form>
      </aside>
      {/* A different key remounts the panel, cleaning up the old feed and its draft. */}
      {selected ? <RoomPanel key={selected.id} room={selected} user={user} onSessionEnded={onSessionEnded} /> :
        <section className="empty-room"><div className="empty-mark" aria-hidden="true">#</div><h2>Find your next conversation</h2><p>Select a public room or create one to get started.</p></section>}
    </div>
  </main>;
}
