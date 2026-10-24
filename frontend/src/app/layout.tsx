import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = { title: "Realtime Chat", description: "Public rooms, saved history, and live conversations." };

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en"><body>{children}</body></html>;
}