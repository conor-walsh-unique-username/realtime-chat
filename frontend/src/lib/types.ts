// These match the backend's response DTOs. JSON timestamps arrive as strings, not Date objects.
export interface User { id: number; username: string; createdAt: string }
export interface Room { id: number; name: string; createdAt: string }
export interface RoomPage { items: Room[]; page: number; size: number; totalItems: number; totalPages: number }
export interface Membership { roomId: number; member: boolean }
export interface Message {
  id: number;
  roomId: number;
  senderId: number;
  senderUsername: string;
  content: string;
  createdAt: string;
}
// nextBefore goes back to the server as an exclusive ID cursor when loading older messages.
export interface HistoryPage { items: Message[]; hasMore: boolean; nextBefore: number | null }
export interface LiveEvents {
  ready(): void;
  message(message: Message): void;
  closed(code: number): void;
}
// A small transport boundary lets the feed tests control HTTP and socket timing.
export interface RoomTransport {
  me(signal?: AbortSignal): Promise<User>;
  membership(roomId: number, signal?: AbortSignal): Promise<Membership>;
  history(roomId: number, before?: number, signal?: AbortSignal): Promise<HistoryPage>;
  join(roomId: number): Promise<void>;
  leave(roomId: number): Promise<void>;
  send(roomId: number, content: string): Promise<Message>;
  connect(roomId: number, events: LiveEvents): { close(): void };
}
