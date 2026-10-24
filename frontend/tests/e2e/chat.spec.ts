import { randomUUID } from "node:crypto";
import { expect, test, type Page } from "@playwright/test";

const backend = "http://localhost:8080";
const password = "LocalE2ePractice2026!";

async function registerAndSignIn(page: Page, username: string) {
  await page.goto("/");
  await page.getByRole("button", { name: "New here? Create an account", exact: true }).click();
  await page.getByLabel("Username", { exact: true }).fill(username);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Create account", exact: true }).click();
  await expect(page.getByRole("status")).toHaveText("Account created. Sign in with your new password.");
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByText(`@${username}`, { exact: true })).toBeVisible();
}

function panel(page: Page, roomName: string) {
  return page.getByRole("region", { name: roomName, exact: true });
}

async function selectRoom(page: Page, roomName: string) {
  await page.getByRole("button", { name: roomName, exact: true }).click();
  await expect(panel(page, roomName)).toBeVisible();
}

async function expectLive(page: Page, roomName: string) {
  await expect(panel(page, roomName).getByRole("status")).toHaveText("Live");
}

async function send(page: Page, roomName: string, content: string) {
  const room = panel(page, roomName);
  await room.getByLabel("Message", { exact: true }).fill(content);
  await room.getByRole("button", { name: "Send message", exact: true }).click();
  await expect(room.getByRole("list", { name: "Messages" }).getByText(content, { exact: true })).toHaveCount(1);
}

test("two users exchange live messages, reload history, leave, rejoin, and sign out", async ({ page: alice, context: aliceContext, browser, request }) => {
  // These records stay in the local database; unique names let this run again without collisions.
  const suffix = randomUUID().replaceAll("-", "").slice(0, 16);
  const aliceName = `e2e_a_${suffix}`;
  const bobName = `e2e_b_${suffix}`;
  const roomName = `e2e_room_${suffix}`;
  // Separate contexts have separate cookie jars, just like separate browser profiles.
  const bobContext = await browser.newContext({ baseURL: "http://localhost:3000" });
  const bob = await bobContext.newPage();
  const receivedLive = new Set<string>();
  // Watch actual incoming frames so a history refresh alone cannot make the live-delivery check pass.
  bob.on("websocket", socket => {
    socket.on("framereceived", ({ payload }) => {
      try {
        const event = JSON.parse(payload.toString());
        if (event.type === "message.created" && typeof event.message?.content === "string") {
          receivedLive.add(event.message.content);
        }
      } catch { /* Ignore frames outside this application's JSON event contract. */ }
    });
  });

  try {
    await test.step("backend is available and anonymous room access is rejected", async () => {
      const health = await request.get(`${backend}/api/health`, { timeout: 5_000 });
      expect(health.status()).toBe(200);
      expect(await health.json()).toMatchObject({ status: "UP" });
      expect((await request.get(`${backend}/api/rooms`)).status()).toBe(401);
    });

    await test.step("register and sign in through the real forms", async () => {
      await registerAndSignIn(alice, aliceName);
      await registerAndSignIn(bob, bobName);
    });

    let roomId: number;
    await test.step("create a room and enforce membership and CSRF", async () => {
      await alice.getByLabel("Start a room", { exact: true }).fill(roomName);
      const created = alice.waitForResponse(response => response.url() === `${backend}/api/rooms` && response.request().method() === "POST");
      await alice.getByRole("button", { name: "Create room", exact: true }).click();
      const response = await created;
      expect(response.status()).toBe(201);
      roomId = (await response.json()).id;
      await expectLive(alice, roomName);

      await bob.getByRole("button", { name: "Refresh", exact: true }).click();
      await selectRoom(bob, roomName);
      await expect(panel(bob, roomName).getByRole("button", { name: "Join room", exact: true })).toBeVisible();
      expect((await bobContext.request.get(`${backend}/api/rooms/${roomId}/messages`)).status()).toBe(403);
      // This request has a real login cookie but deliberately omits the CSRF header.
      const missingCsrf = await aliceContext.request.post(`${backend}/api/rooms/${roomId}/messages`, { data: { content: "Must not be saved" } });
      expect(missingCsrf.status()).toBe(403);
      expect(await missingCsrf.json()).toMatchObject({ code: "INVALID_CSRF" });
      await panel(bob, roomName).getByRole("button", { name: "Join room", exact: true }).click();
      await expectLive(bob, roomName);
    });

    const first = `Hello from Alice ${suffix}`;
    const reply = `<b>Plain text from Bob</b> ${suffix}`;
    await test.step("receive a real WebSocket frame and show each message once", async () => {
      await send(alice, roomName, first);
      await expect(panel(bob, roomName).getByRole("list", { name: "Messages" }).getByText(first, { exact: true })).toHaveCount(1);
      await expect.poll(() => receivedLive.has(first)).toBe(true);
      await send(bob, roomName, reply);
      await expect(panel(alice, roomName).getByRole("list", { name: "Messages" }).getByText(reply, { exact: true })).toHaveCount(1);
    });

    await test.step("reload restores the login and saved message history", async () => {
      await alice.reload();
      await expect(alice.getByText(`@${aliceName}`, { exact: true })).toBeVisible();
      await selectRoom(alice, roomName);
      await expectLive(alice, roomName);
      const messages = panel(alice, roomName).getByRole("list", { name: "Messages" });
      await expect(messages.getByRole("listitem")).toHaveCount(2);
      await expect(messages.getByText(first, { exact: true })).toBeVisible();
      await expect(messages.getByText(reply, { exact: true })).toBeVisible();
    });

    await test.step("leaving clears history; rejoining catches up", async () => {
      const room = panel(bob, roomName);
      await room.getByRole("button", { name: "Leave room", exact: true }).click();
      await expect(room.getByRole("button", { name: "Join room", exact: true })).toBeVisible();
      await expect(room.getByRole("list", { name: "Messages" })).toHaveCount(0);
      await expect(room.getByRole("button", { name: "Send message", exact: true })).toBeDisabled();
      expect((await bobContext.request.get(`${backend}/api/rooms/${roomId!}/messages`)).status()).toBe(403);
      const missed = `Saved while Bob was outside ${suffix}`;
      await send(alice, roomName, missed);
      await expect(room.getByText(missed, { exact: true })).toHaveCount(0);
      await room.getByRole("button", { name: "Join room", exact: true }).click();
      await expectLive(bob, roomName);
      await expect(room.getByRole("list", { name: "Messages" }).getByText(missed, { exact: true })).toHaveCount(1);
    });

    await test.step("logout revokes Bob's session while Alice remains signed in", async () => {
      await bob.getByRole("button", { name: "Sign out", exact: true }).click();
      await expect(bob.getByRole("button", { name: "Sign in", exact: true })).toBeVisible();
      expect((await bobContext.request.get(`${backend}/api/auth/me`)).status()).toBe(401);
      const stillAlice = await aliceContext.request.get(`${backend}/api/auth/me`);
      expect(stillAlice.status()).toBe(200);
      expect(await stillAlice.json()).toMatchObject({ username: aliceName });
      await send(alice, roomName, `Alice is still connected ${suffix}`);
      await alice.getByRole("button", { name: "Sign out", exact: true }).click();
      await expect(alice.getByRole("button", { name: "Sign in", exact: true })).toBeVisible();
    });
  } finally {
    await bobContext.close();
  }
});
