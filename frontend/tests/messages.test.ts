import test from "node:test";
import assert from "node:assert/strict";
import { mergeMessages } from "../src/lib/messages.ts";
import type { Message } from "../src/lib/types.ts";

const message = (id: number, roomId = 7): Message => ({ id, roomId, senderId: 1, senderUsername: "alice", content: `Message ${id}`, createdAt: "2026-10-19T12:00:00Z" });

test("history and a live/REST echo produce one message per ID in stable ID order", () => {
  const result = mergeMessages([message(3), message(1)], [message(2), message(3)], 7);
  assert.deepEqual(result.map(value => value.id), [1, 2, 3]);
});
test("a reply for another room cannot enter the selected room's list", () => {
  assert.deepEqual(mergeMessages([message(1)], [message(2, 9)], 7).map(value => value.id), [1]);
});
test("merging leaves the previous snapshot unchanged", () => {
  // React may still be reading the old array, so merging must build a new one.
  const previous = [message(2)];
  mergeMessages(previous, [message(1)], 7);
  assert.deepEqual(previous.map(value => value.id), [2]);
});
