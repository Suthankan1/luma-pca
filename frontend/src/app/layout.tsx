import type { Metadata } from "next";
import "./globals.css";
import { SessionSync } from "@/components/SessionSync";

export const metadata: Metadata = {
  title: "LUMA | Physics Cube Academy",
  description: "Physics Cube Academy staff and student portals",
  icons: { icon: { url: "/physics-cube-academy-logo.jpg", type: "image/jpeg" } },
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    // Browser extensions may add root attributes before hydration (e.g. crxlauncher).
    // Limit suppression to this element; descendants still report hydration mismatches.
    <html lang="en" data-scroll-behavior="smooth" suppressHydrationWarning>
      <body>
        <SessionSync />
        {children}
      </body>
    </html>
  );
}
