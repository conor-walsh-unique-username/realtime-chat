import type { HistoryPage, LiveEvents, Membership, Message, Room, RoomPage, RoomTransport, User } from "./types.ts";

const API = "http://localhost:8080";
type Csrf = { headerName: string; token: string };
let csrf: Csrf | null = null;
// Share an in-progress token fetch if a few actions ask for one at the same time.
let pendingCsrf: Promise<Csrf> | null = null;

// Keep the server's status/code so callers can tell an ended login from a network failure.
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

async function token(): Promise<Csrf> {
  if (csrf) return csrf;
  if (!pendingCsrf) {
    pendingCsrf = fetch(`${API}/api/auth/csrf`, { credentials: "include", cache: "no-store" })
      .then(async response => {
        if (!response.ok) throw new Error("Could not initialize the session. Check the backend.");
        csrf = await response.json() as Csrf;
        return csrf;
      }).finally(() => { pendingCsrf = null; });
  }
  return pendingCsrf;
}

async function request<T>(method: string, path: string, body?: object, signal?: AbortSignal, form = false): Promise<T> {
  const headers: Record<string, string> = {};
  // Even login and registration need a CSRF token before they can change server state.
  if (method !== "GET") {
    const value = await token();
    headers[value.headerName] = value.token;
  }
  if (body) headers["Content-Type"] = form ? "application/x-www-form-urlencoded" : "application/json";
  // The API is on another port. "include" lets the browser send and accept its session cookie.
  const response = await fetch(API + path, {
    method, headers, signal, credentials: "include", cache: "no-store",
    body: body ? (form ? new URLSearchParams(body as Record<string, string>) : JSON.stringify(body)) : undefined,
  });
  // Login, logout, join and leave can return 204 with no JSON body to parse.
  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!response.ok) {
    // Clear a stale token for the next action. Repeating this write automatically could duplicate it.
    if (response.status === 401 || data?.code === "INVALID_CSRF") csrf = null;
    const fields = data?.fieldErrors as Record<string, string> | undefined;
    const message = data?.code === "INVALID_CSRF" ? "Your session changed. Please try again."
      : fields ? Object.values(fields).join(" ") : data?.detail || "The request failed.";
    throw new ApiError(response.status, data?.code || "REQUEST_FAILED", message);
  }
  return data as T;
}

export function errorMessage(error: unknown): string {
  return error instanceof ApiError ? error.message : "Cannot reach the chat server. Please try again.";
}

export const api = {
  me: (signal?: AbortSignal) => request<User>("GET", "/api/auth/me", undefined, signal),
  register: (username: string, password: string) => request<User>("POST", "/api/auth/register", { username, password }),
  async login(username: string, password: string): Promise<User> {
    await request<void>("POST", "/api/auth/login", { username, password }, undefined, true);
    // Login rotates CSRF; never reuse the pre-login token for authenticated writes.
    csrf = null;
    await token();
    return api.me();
  },
  async logout(): Promise<void> {
    await request<void>("POST", "/api/auth/logout");
    csrf = null;
  },
  rooms: (page: number, signal?: AbortSignal) => request<RoomPage>("GET", `/api/rooms?page=${page}&size=20`, undefined, signal),
  createRoom: (name: string) => request<Room>("POST", "/api/rooms", { name }),
};

export const roomTransport: RoomTransport = {
  me: api.me,
  membership: (id, signal) => request<Membership>("GET", `/api/rooms/${id}/members/me`, undefined, signal),
  history: (id, before, signal) => request<HistoryPage>("GET", `/api/rooms/${id}/messages?size=50${before === undefined ? "" : `&before=${before}`}`, undefined, signal),
  join: id => request<void>("POST", `/api/rooms/${id}/members/me`),
  leave: id => request<void>("DELETE", `/api/rooms/${id}/members/me`),
  send: (id, content) => request<Message>("POST", `/api/rooms/${id}/messages`, { content }),
  connect(id: number, events: LiveEvents) {
    // Cookies are attached by the browser; the socket URL contains no credentials.
    const socket = new WebSocket(`ws://localhost:8080/ws/rooms/${id}`);
    // A connection is usable only after the server's ready event, not just a browser open event.
    const timeout = setTimeout(() => socket.close(4000, "Ready timeout"), 15000);
    socket.onmessage = event => {
      try {
        const data = JSON.parse(event.data);
        if (data.type === "ready" && data.roomId === id) { clearTimeout(timeout); events.ready(); }
        else if (data.type === "message.created" && data.message?.roomId === id) events.message(data.message as Message);
      } catch { socket.close(4002, "Invalid server event"); }
    };
    socket.onclose = event => { clearTimeout(timeout); events.closed(event.code); };
    return { close: () => { clearTimeout(timeout); socket.close(); } };
  },
};
