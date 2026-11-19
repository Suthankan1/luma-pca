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
    // Browser extensions may add root attributes before hydration (e.g. crxlauncher).
    // Limit suppression to this element; descendants still report hydration mismatches.
    <html lang="en" data-scroll-behavior="smooth" suppressHydrationWarning>
      <body>
        <SessionSync authenticated={authenticated} destination={destination} />
        {children}
      </body>
    </html>
  );
}
