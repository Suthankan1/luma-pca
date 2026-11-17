import type { Metadata } from "next";
import "./globals.css";
export const metadata: Metadata = {
  title: "LUMA | Physics Cube Academy",
  description: "Physics Cube Academy staff and student portals",
};
export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en" data-scroll-behavior="smooth"><body>{children}</body></html>;
}
