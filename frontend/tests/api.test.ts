import test from "node:test";
import assert from "node:assert/strict";
import { api, roomTransport } from "../src/lib/api.ts";

test("login refreshes CSRF before authenticated writes and all requests include cookies", async () => {
  // Replace fetch with a tiny fake server, then restore it so later tests get a clean environment.
  const previous = globalThis.fetch;
  let tokenCount = 0;
  const writes: { path: string; token: string | null; body: string }[] = [];
  globalThis.fetch = async (input, options) => {
    const path = new URL(String(input)).pathname;
    assert.equal(options?.credentials, "include");
    if (path.endsWith("/csrf")) {
      tokenCount++;
      return Response.json({ headerName: "X-CSRF-TOKEN", token: `token-${tokenCount}` });
    }
    if (options?.method !== "GET") writes.push({ path, token: new Headers(options?.headers).get("X-CSRF-TOKEN"), body: String(options?.body || "") });
    if (path.endsWith("/login") || path.endsWith("/logout")) return new Response(null, { status: 204 });
    if (path.endsWith("/me")) return Response.json({ id: 1, username: "alice", createdAt: "2026-10-19T12:00:00Z" });
    if (path.endsWith("/messages")) return Response.json({ id: 1, roomId: 7, senderId: 1, senderUsername: "alice", content: "Hi", createdAt: "2026-10-19T12:00:00Z" }, { status: 201 });
    throw new Error(`Unexpected path ${path}`);
  };
  try {
    await api.login("alice", "LocalChatDemo2026!");
    await roomTransport.send(7, "Hi");
    await api.logout();
    assert.equal(tokenCount, 2);
    assert.equal(writes[0].token, "token-1");
    assert.equal(new URLSearchParams(writes[0].body).get("username"), "alice");
    assert.equal(writes[1].token, "token-2");
    assert.deepEqual(JSON.parse(writes[1].body), { content: "Hi" });
    assert.equal(writes[2].token, "token-2");
  } finally { globalThis.fetch = previous; }
});

test("a rejected CSRF token is refreshed on the next attempt without retrying the POST automatically", async () => {
  const previous = globalThis.fetch;
  let tokens = 0;
  let writes = 0;
  globalThis.fetch = async (input, options) => {
    if (String(input).endsWith("/csrf")) {
      tokens++;
      return Response.json({ headerName: "X-CSRF-TOKEN", token: `csrf-${tokens}` });
    }
    writes++;
    if (writes === 1) return Response.json({ code: "INVALID_CSRF", detail: "Fetch a token" }, { status: 403 });
    assert.equal(new Headers(options?.headers).get("X-CSRF-TOKEN"), "csrf-2");
    return Response.json({ id: 2, roomId: 7, senderId: 1, senderUsername: "alice", content: "Hi", createdAt: "2026-10-19T12:00:00Z" }, { status: 201 });
  };
  try {
    await assert.rejects(roomTransport.send(7, "Hi"), /Your session changed/);
    assert.equal(writes, 1);
    assert.equal(tokens, 1);
    await roomTransport.send(7, "Hi");
    assert.equal(writes, 2);
    assert.equal(tokens, 2);
  } finally { globalThis.fetch = previous; }
});
