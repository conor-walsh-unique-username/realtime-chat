import type { Message } from "./types.ts";

export function mergeMessages(current: Message[], incoming: Message[], roomId: number): Message[] {
  // REST responses, history and live echoes can describe the same saved ID.
  const byId = new Map<number, Message>();
  for (const message of [...current, ...incoming]) {
    if (message.roomId === roomId) byId.set(message.id, message);
  }
  // History arrives newest first; the conversation reads from oldest to newest.
  return [...byId.values()].sort((a, b) => a.id - b.id);
}
