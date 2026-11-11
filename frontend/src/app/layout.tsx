import type { Metadata } from "next";
import "./globals.css";
export const metadata: Metadata = {
  title: "LUMA | Physics Cube Academy",
  description: "Physics Cube Academy classroom presence platform — bootstrap preview",
};
export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en"><body>{children}</body></html>;
}
