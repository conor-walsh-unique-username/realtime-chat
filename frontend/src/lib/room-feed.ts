import { ApiError, errorMessage } from "./api.ts";
import { mergeMessages } from "./messages.ts";
import type { Message, RoomTransport } from "./types.ts";

export interface FeedState {
  membership: "checking" | "member" | "outside";
  connection: "idle" | "connecting" | "loading" | "live" | "reconnecting" | "offline" | "blocked";
  messages: Message[];
  nextBefore: number | null;
  historyLoaded: boolean;
  loadingOlder: boolean;
  sending: boolean;
  membershipBusy: boolean;
  error: string | null;
}
const INITIAL: FeedState = {
  membership: "checking", connection: "idle", messages: [], nextBefore: null,
  historyLoaded: false, loadingOlder: false, sending: false, membershipBusy: false, error: null,
};
type Schedule = (action: () => void, delay: number) => () => void;
const schedule: Schedule = (action, delay) => {
  const timer = setTimeout(action, delay);
  return () => clearTimeout(timer);
};

// This store owns one room. React subscribes to immutable snapshots of its state.
export class RoomFeed {
  private readonly roomId: number;
  private readonly userId: number;
  private readonly transport: RoomTransport;
  private readonly onSessionEnded: () => void;
  private readonly schedule: Schedule;
  private state = INITIAL;
  private readonly listeners = new Set<() => void>();
  private active = false;
  // A run is one start/stop lifecycle. Each reconnect gets its own connectionId within that run.
  // Async callbacks remember both numbers so replies from old work can be ignored.
  private run = 0;
  private connectionId = 0;
  private attempts = 0;
  private controller: AbortController | null = null;
  private socket: { close(): void } | null = null;
  private cancelRetry: (() => void) | null = null;

  constructor(roomId: number, userId: number, transport: RoomTransport, onSessionEnded: () => void, later: Schedule = schedule) {
    this.roomId = roomId;
    this.userId = userId;
    this.transport = transport;
    this.onSessionEnded = onSessionEnded;
    this.schedule = later;
  }
  // React needs the same snapshot object until something changes, or it keeps rendering.
  getSnapshot = (): FeedState => this.state;
  getServerSnapshot = (): FeedState => INITIAL;
  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };
  private put(patch: Partial<FeedState>): void {
    // Replace the snapshot instead of mutating the one React may already be displaying.
    this.state = { ...this.state, ...patch };
    this.listeners.forEach(listener => listener());
  }
  private current(run: number, connection?: number): boolean {
    return this.active && this.run === run && (connection === undefined || this.connectionId === connection);
  }
  private detach(): void {
    // Invalidate callbacks before closing; late events belong to the old connection.
    this.connectionId++;
    this.cancelRetry?.();
    this.cancelRetry = null;
    this.controller?.abort();
    this.controller = null;
    const socket = this.socket;
    this.socket = null;
    socket?.close();
  }
  start(): void {
    this.detach();
    this.active = true;
    this.run++;
    this.attempts = 0;
    this.put(INITIAL);
    void this.open();
  }
  stop(): void {
    this.active = false;
    this.run++;
    this.detach();
  }
  retryNow(): void {
    if (!this.active) return;
    this.detach();
    this.attempts = 0;
    void this.open();
  }
  private revoke(message: string | null): void {
    this.run++;
    this.detach();
    // Clear cached messages as well as closing the connection when access is lost.
    this.put({ ...INITIAL, membership: "outside", connection: "blocked", error: message });
  }
  private accessFailure(error: unknown): boolean {
    if (!(error instanceof ApiError)) return false;
    if (error.status === 401) {
      this.revoke("Your session ended. Sign in again.");
      this.onSessionEnded();
      return true;
    }
    // INVALID_CSRF also uses 403, but does not mean the user has lost room membership.
    if (error.status === 404 || error.code === "ROOM_MEMBERSHIP_REQUIRED" || error.code === "FORBIDDEN") {
      this.revoke(error.message);
      return true;
    }
    return false;
  }
  private retry(message: string): void {
    this.detach();
    this.attempts++;
    // Stop after six scheduled retries; the user can still use the manual retry button.
    if (this.attempts > 6) {
      this.put({ connection: "offline", loadingOlder: false, error: message });
      return;
    }
    this.put({ connection: "reconnecting", loadingOlder: false, error: message });
    // Wait 1s, 2s, 4s, then 8s between attempts so a down server is not hit constantly.
    this.cancelRetry = this.schedule(() => {
      this.cancelRetry = null;
      void this.open();
    }, Math.min(1000 * 2 ** (this.attempts - 1), 8000));
  }
  private async open(): Promise<void> {
    if (!this.active) return;
    const run = this.run;
    const connection = ++this.connectionId;
    const controller = new AbortController();
    this.controller = controller;
    this.put({ connection: this.attempts ? "reconnecting" : "connecting" });
    try {
      const account = await this.transport.me(controller.signal);
      if (!this.current(run, connection)) return;
      // Tabs share cookies. Another tab might have signed in as someone else since our last check.
      if (account.id !== this.userId) {
        this.revoke("The signed-in account changed. Sign in again.");
        this.onSessionEnded();
        return;
      }
      const membership = await this.transport.membership(this.roomId, controller.signal);
      if (!this.current(run, connection)) return;
      if (!membership.member) { this.revoke(null); return; }
      this.put({ membership: "member" });
      let ready = false;
      this.socket = this.transport.connect(this.roomId, {
        ready: () => {
          if (!this.current(run, connection) || ready) return;
          ready = true;
          // Listen first, then read history, so messages arriving during the read are still caught.
          void this.synchronize(run, connection, controller.signal);
        },
        message: message => {
          if (this.current(run, connection)) this.put({ messages: mergeMessages(this.state.messages, [message], this.roomId) });
        },
        closed: code => {
          if (!this.current(run, connection)) return;
          // A policy close may mean membership or login changed in another tab.
          if (code === 1008) this.put({ messages: [], historyLoaded: false, nextBefore: null });
          this.retry(code === 1008 ? "Checking room access…" : "Connection interrupted. Reconnecting…");
        },
      });
    } catch (error) {
      if (this.current(run, connection) && !this.accessFailure(error)) this.retry(errorMessage(error));
    }
  }
  private async synchronize(run: number, connection: number, signal: AbortSignal): Promise<void> {
    // On reconnect, fill back to the oldest message already shown, even if that takes several pages.
    const boundary = this.state.messages[0]?.id;
    // Keep the user's older-history position while refreshing the messages they have loaded.
    const preserveOlderCursor = this.state.historyLoaded && boundary !== undefined;
    let before: number | undefined;
    this.put({ connection: "loading" });
    try {
      do {
        const page = await this.transport.history(this.roomId, before, signal);
        if (!this.current(run, connection)) return;
        // Merge with the current snapshot, including live arrivals during this request.
        this.put({ messages: mergeMessages(this.state.messages, page.items, this.roomId) });
        if (!preserveOlderCursor) {
          this.put({ nextBefore: page.hasMore ? page.nextBefore : null });
          break;
        }
        // Refresh through the oldest loaded ID so an isolated POST echo cannot hide a gap.
        if (!page.hasMore || page.items.some(message => message.id <= boundary)) break;
        // A broken/repeated cursor must not trap recovery in an endless request loop.
        if (page.nextBefore === null || (before !== undefined && page.nextBefore >= before)) {
          throw new Error("History cursor did not advance.");
        }
        before = page.nextBefore;
      } while (true);
      if (this.current(run, connection)) {
        this.attempts = 0;
        this.put({ historyLoaded: true, connection: "live", error: null });
      }
    } catch (error) {
      if (this.current(run, connection) && !this.accessFailure(error)) this.retry(errorMessage(error));
    }
  }
  async loadOlder(): Promise<void> {
    const before = this.state.nextBefore;
    if (before === null || this.state.loadingOlder || this.state.connection !== "live") return;
    const run = this.run;
    const connection = this.connectionId;
    this.put({ loadingOlder: true, error: null });
    try {
      const page = await this.transport.history(this.roomId, before, this.controller?.signal);
      if (this.current(run, connection)) this.put({
        messages: mergeMessages(this.state.messages, page.items, this.roomId),
        nextBefore: page.hasMore ? page.nextBefore : null,
      });
    } catch (error) {
      if (this.current(run, connection) && !this.accessFailure(error)) this.put({ error: errorMessage(error) });
    } finally {
      if (this.current(run, connection)) this.put({ loadingOlder: false });
    }
  }
  async send(content: string): Promise<boolean> {
    if (this.state.sending || this.state.membershipBusy || this.state.connection !== "live" || !content.trim()) return false;
    const run = this.run;
    this.put({ sending: true, error: null });
    try {
      const message = await this.transport.send(this.roomId, content);
      // A send can finish during a reconnect, so only the room lifecycle has to match here.
      if (!this.current(run)) return false;
      this.put({ messages: mergeMessages(this.state.messages, [message], this.roomId) });
      return true;
    } catch (error) {
      if (this.current(run) && !this.accessFailure(error)) this.put({
        error: error instanceof ApiError ? error.message : "Could not confirm sending. Reconnect and check history before trying again.",
      });
      // Never automatically retry a POST: the server may already have saved it.
      return false;
    } finally {
      if (this.current(run)) this.put({ sending: false });
    }
  }
  async join(): Promise<void> {
    if (this.state.membershipBusy) return;
    const run = this.run;
    this.put({ membershipBusy: true, error: null });
    try {
      await this.transport.join(this.roomId);
      // Start fresh so membership, history, and the socket are all checked again after joining.
      if (this.current(run)) this.start();
    } catch (error) {
      if (this.current(run) && !this.accessFailure(error)) this.put({ error: errorMessage(error) });
    } finally {
      if (this.current(run)) this.put({ membershipBusy: false });
    }
  }
  async leave(): Promise<void> {
    if (this.state.membershipBusy || this.state.sending) return;
    const run = this.run;
    this.put({ membershipBusy: true, error: null });
    try {
      await this.transport.leave(this.roomId);
      if (this.current(run)) this.revoke(null);
    } catch (error) {
      if (this.current(run) && !this.accessFailure(error)) {
        // The leave may have worked despite a lost response. Hide history while checking access.
        this.put({ messages: [], nextBefore: null, historyLoaded: false });
        this.retry("Could not confirm leaving. Checking room access…");
      }
    } finally {
      if (this.current(run)) this.put({ membershipBusy: false });
    }
  }
}
