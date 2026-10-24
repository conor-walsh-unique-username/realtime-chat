"use client";

import { useState } from "react";
import { api, errorMessage } from "@/lib/api";
import type { User } from "@/lib/types";

export function AuthForm({ onSignedIn }: { onSignedIn(user: User): void }) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    // Handle this in React so the browser does not navigate away with a normal form submission.
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      if (mode === "register") {
        await api.register(username, password);
        // Registration saves the account; the separate login step creates the signed-in session.
        setMode("login");
        setPassword("");
        setNotice("Account created. Sign in with your new password.");
      } else {
        const user = await api.login(username, password);
        setPassword("");
        onSignedIn(user);
      }
    } catch (error) { setError(errorMessage(error)); }
    finally { setBusy(false); }
  }

  return <main className="auth-shell">
    <div className="auth-intro"><span className="eyebrow">REALTIME CHAT</span>
      <h1>A place for the conversation.</h1>
      <p>Find a room, join in, and pick up where you left off.</p>
      <div className="feature-line">Public rooms · Saved history · Live messages</div>
    </div>
    <section className="auth-card" aria-labelledby="auth-title">
      <h2 id="auth-title">{mode === "login" ? "Welcome back" : "Create your account"}</h2>
      <p className="muted">{mode === "login" ? "Sign in to your rooms." : "Choose a name people will recognize."}</p>
      <form onSubmit={submit}>
        <label htmlFor="username">Username</label>
        <input id="username" value={username} onChange={event => setUsername(event.target.value)} required
          minLength={3} maxLength={30} pattern="[A-Za-z0-9_]{3,30}" autoComplete="username" autoCapitalize="none" spellCheck={false} disabled={busy} />
        {mode === "register" && <small>3–30 letters, numbers, or underscores.</small>}
        <label htmlFor="password">Password</label>
        <input id="password" type="password" value={password} onChange={event => setPassword(event.target.value)} required
          minLength={mode === "register" ? 12 : undefined} maxLength={128}
          autoComplete={mode === "login" ? "current-password" : "new-password"} disabled={busy} />
        {mode === "register" && <small>Use 12–128 characters.</small>}
        {error && <p className="alert" role="alert">{error}</p>}
        {notice && <p className="notice" role="status">{notice}</p>}
        <button className="primary full" disabled={busy}>{busy ? "Please wait…" : mode === "login" ? "Sign in" : "Create account"}</button>
      </form>
      <button className="text-button" disabled={busy} onClick={() => {
        setMode(mode === "login" ? "register" : "login"); setError(null); setNotice(null);
      }}>{mode === "login" ? "New here? Create an account" : "Already registered? Sign in"}</button>
    </section>
  </main>;
}
