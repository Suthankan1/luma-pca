"use client";

import { useEffect } from "react";
import { usePathname } from "next/navigation";
import { isGuestPath } from "@/lib/session-policy";

const SESSION_STORAGE_KEY = "luma_session_event";

/** Notify other protected tabs after the server has completed sign-out. */
export function SessionSync() {
  const pathname = usePathname();

  useEffect(() => {
    const url = new URL(window.location.href);
    if (pathname === "/" && url.searchParams.get("signedOut") === "1") {
      url.searchParams.delete("signedOut");
      window.history.replaceState(window.history.state, "", url);
      try {
        localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify({ type: "LOGOUT", at: Date.now() }));
      } catch {}
    }

    const onStorage = (event: StorageEvent) => {
      if (event.key !== SESSION_STORAGE_KEY || !event.newValue || isGuestPath(pathname)) return;
      try {
        if (JSON.parse(event.newValue)?.type === "LOGOUT") window.location.replace("/");
      } catch {}
    };
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, [pathname]);

  return null;
}
