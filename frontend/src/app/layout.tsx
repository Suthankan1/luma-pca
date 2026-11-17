import type { Metadata } from "next";
import "./globals.css";
import { currentSession } from "@/lib/auth-server";
import { portalPath } from "@/lib/session-policy";
import { SessionSync } from "@/components/SessionSync";

export const metadata: Metadata = {
  title: "LUMA | Physics Cube Academy",
  description: "Physics Cube Academy staff and student portals",
};

export default async function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  const { identity } = await currentSession();
  const authenticated = Boolean(identity);
  const destination = identity ? portalPath(identity) : null;

  return (
    <html lang="en" data-scroll-behavior="smooth">
      <body>
        <SessionSync authenticated={authenticated} destination={destination} />
        {children}
      </body>
    </html>
  );
}
