import test from "node:test";
import assert from "node:assert/strict";
import { RoomFeed } from "../src/lib/room-feed.ts";
import { ApiError } from "../src/lib/api.ts";
import type { HistoryPage, LiveEvents, Message, RoomTransport, User } from "../src/lib/types.ts";

const message = (id: number, roomId = 7): Message => ({ id, roomId, senderId: 1, senderUsername: "alice", content: `Message ${id}`, createdAt: "2026-10-19T12:00:00Z" });
const page = (ids: number[], nextBefore: number | null = null): HistoryPage => ({ items: ids.map(id => message(id)), hasMore: nextBefore !== null, nextBefore });
const tick = () => new Promise<void>(resolve => setImmediate(resolve));
// Let the test decide when a request finishes, so overlapping events happen in a known order.
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}
// Save the socket callbacks so tests can deliver ready/message/close events by hand.
class FakeTransport implements RoomTransport {
  user: User = { id: 1, username: "alice", createdAt: "2026-10-19T12:00:00Z" };
  member = true;
  events: LiveEvents[] = [];
  historyCalls: (number | undefined)[] = [];
  historyResult: (before?: number) => Promise<HistoryPage> = async () => page([1]);
  sendResult: (content: string) => Promise<Message> = async () => message(2);
  meError: unknown = null;
  closedCount = 0;
  async me(): Promise<User> { if (this.meError) throw this.meError; return this.user; }
  async membership(roomId: number) { return { roomId, member: this.member }; }
  async history(_id: number, before?: number) { this.historyCalls.push(before); return this.historyResult(before); }
  async join() { this.member = true; }
  async leave() { this.member = false; }
  async send(_id: number, content: string) { return this.sendResult(content); }
  connect(_id: number, events: LiveEvents) { this.events.push(events); return { close: () => { this.closedCount++; } }; }
}
function setup() {
  const transport = new FakeTransport();
  // Capture retries instead of waiting on real timers; a test can run the next job immediately.
  const jobs: { action(): void; cancelled: boolean; delay: number }[] = [];
  let expired = 0;
  const feed = new RoomFeed(7, 1, transport, () => { expired++; }, (action, delay) => {
    const job = { action, cancelled: false, delay };
    jobs.push(job);
    return () => { job.cancelled = true; };
  });
  return { feed, transport, jobs, expired: () => expired };
}
async function connect(value: ReturnType<typeof setup>) {
  value.feed.start();
  await tick();
  value.transport.events.at(-1)!.ready();
  await tick();
}

test("a live arrival during the initial HTTP request survives history loading", async () => {
  const value = setup();
  const pending = deferred<HistoryPage>();
  value.transport.historyResult = () => pending.promise;
  value.feed.start(); await tick();
  const events = value.transport.events[0];
  events.ready();
  events.message(message(3));
  pending.resolve(page([2, 1])); await tick();
  assert.deepEqual(value.feed.getSnapshot().messages.map(item => item.id), [1, 2, 3]);
  assert.equal(value.feed.getSnapshot().connection, "live");
  value.feed.stop();
});

test("the same POST result and live echo appear once", async () => {
  const value = setup(); await connect(value);
  value.transport.sendResult = async () => {
    value.transport.events[0].message(message(2));
    return message(2);
  };
  assert.equal(await value.feed.send("Hello"), true);
  assert.deepEqual(value.feed.getSnapshot().messages.map(item => item.id), [1, 2]);
  value.feed.stop();
});

test("stopping a room ignores pending history and late socket events", async () => {
  const value = setup();
  const pending = deferred<HistoryPage>();
  value.transport.historyResult = () => pending.promise;
  value.feed.start(); await tick();
  const old = value.transport.events[0]; old.ready();
  value.feed.stop();
  old.message(message(99)); old.closed(1006);
  pending.resolve(page([1])); await tick();
  assert.deepEqual(value.feed.getSnapshot().messages, []);
  assert.equal(value.jobs.length, 0);
  assert.equal(value.transport.closedCount, 1);
});

test("older history preserves concurrent new arrivals and advances its cursor", async () => {
  const value = setup();
  value.transport.historyResult = async () => page([5, 4], 4);
  await connect(value);
  const pending = deferred<HistoryPage>();
  value.transport.historyResult = () => pending.promise;
  const loading = value.feed.loadOlder();
  value.transport.events[0].message(message(6));
  pending.resolve(page([3, 2])); await loading;
  assert.deepEqual(value.feed.getSnapshot().messages.map(item => item.id), [2, 3, 4, 5, 6]);
  assert.equal(value.transport.historyCalls.at(-1), 4);
  assert.equal(value.feed.getSnapshot().nextBefore, null);
  value.feed.stop();
});

test("reconnect fills several pages even if a newer isolated POST result was already received", async () => {
  const value = setup();
  value.transport.historyResult = async () => page([3, 2], 2);
  await connect(value);
  const pendingSend = deferred<Message>();
  value.transport.sendResult = () => pendingSend.promise;
  const sending = value.feed.send("A message in flight");
  value.transport.events[0].closed(1006);
  pendingSend.resolve(message(9)); await sending;
  value.transport.historyResult = async before => before === undefined ? page([9, 8], 8)
    : before === 8 ? page([7, 6], 6) : before === 6 ? page([5, 4], 4) : page([3, 2], 2);
  value.jobs.find(job => !job.cancelled)!.action(); await tick();
  value.transport.events.at(-1)!.ready(); await tick();
  assert.deepEqual(value.feed.getSnapshot().messages.map(item => item.id), [2, 3, 4, 5, 6, 7, 8, 9]);
  assert.equal(value.feed.getSnapshot().nextBefore, 2);
  assert.equal(value.feed.getSnapshot().connection, "live");
  value.feed.stop();
});

test("a policy close clears cached history and rechecks membership", async () => {
  const value = setup(); await connect(value);
  value.transport.member = false;
  value.transport.events[0].closed(1008);
  assert.deepEqual(value.feed.getSnapshot().messages, []);
  value.jobs.find(job => !job.cancelled)!.action(); await tick();
  assert.equal(value.feed.getSnapshot().membership, "outside");
  assert.equal(value.transport.events.length, 1);
  value.feed.stop();
});

test("an ended session returns the app to sign-in", async () => {
  const value = setup();
  value.transport.meError = new ApiError(401, "AUTH_REQUIRED", "Sign in");
  value.feed.start(); await tick();
  assert.equal(value.expired(), 1);
  assert.equal(value.feed.getSnapshot().connection, "blocked");
  assert.deepEqual(value.feed.getSnapshot().messages, []);
  value.feed.stop();
});

test("switching the cookie to another account cannot silently reuse this feed", async () => {
  const value = setup();
  value.transport.user = { ...value.transport.user, id: 2, username: "bob" };
  value.feed.start(); await tick();
  assert.equal(value.expired(), 1);
  assert.equal(value.transport.events.length, 0);
  value.feed.stop();
});

test("leaving clears history, closes the socket, and can be followed by rejoining", async () => {
  const value = setup(); await connect(value);
  await value.feed.leave();
  assert.equal(value.feed.getSnapshot().membership, "outside");
  assert.deepEqual(value.feed.getSnapshot().messages, []);
  await value.feed.join(); await tick();
  value.transport.events.at(-1)!.ready(); await tick();
  assert.equal(value.feed.getSnapshot().connection, "live");
  value.feed.stop();
});

test("a rejected CSRF token does not remove valid membership", async () => {
  const value = setup(); await connect(value);
  value.transport.sendResult = async () => { throw new ApiError(403, "INVALID_CSRF", "Fetch a new token"); };
  assert.equal(await value.feed.send("Hello"), false);
  assert.equal(value.feed.getSnapshot().membership, "member");
  assert.equal(value.feed.getSnapshot().sending, false);
  assert.equal(value.feed.getSnapshot().error, "Fetch a new token");
  value.feed.stop();
});

test("an uncertain POST failure is reported without automatically sending again", async () => {
  const value = setup(); await connect(value);
  let attempts = 0;
  value.transport.sendResult = async () => { attempts++; throw new Error("Connection dropped after write"); };
  assert.equal(await value.feed.send("Hello"), false);
  assert.equal(attempts, 1);
  assert.equal(value.jobs.length, 0);
  assert.match(value.feed.getSnapshot().error || "", /check history/);
  value.feed.stop();
});

test("a pending leave blocks new sends before the server confirms it", async () => {
  const value = setup(); await connect(value);
  const pending = deferred<void>();
  value.transport.leave = async () => { await pending.promise; value.transport.member = false; };
  const leaving = value.feed.leave();
  let sends = 0;
  value.transport.sendResult = async () => { sends++; return message(2); };
  assert.equal(await value.feed.send("Too late"), false);
  assert.equal(sends, 0);
  pending.resolve(); await leaving;
  assert.equal(value.feed.getSnapshot().membership, "outside");
  value.feed.stop();
});
