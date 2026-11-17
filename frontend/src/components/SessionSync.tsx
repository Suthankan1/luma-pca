"use client";

import { useEffect, useRef } from "react";
import { usePathname } from "next/navigation";
import { isGuestPath } from "@/lib/session-policy";

interface SessionSyncProps {
  authenticated: boolean;
  destination: string | null;
}

export const SESSION_CHANNEL = "luma_session_sync";
export const SESSION_STORAGE_KEY = "luma_session_event";

/**
 * Coordinates authentication state across browser tabs.
 * When a user signs in in one tab:
 * 1. That tab broadcasts a LOGIN event via BroadcastChannel and localStorage.
 * 2. All other open tabs on guest/sign-in pages immediately detect the event and redirect
 *    to the overview page (/staff or /student).
 * 3. On focus or visibility change, background tabs verify their session state with /api/session/status.
 */
export function SessionSync({ authenticated, destination }: SessionSyncProps) {
  const pathname = usePathname();
  const announcedRef = useRef(false);

  // Announce current state across tabs when mounted
  useEffect(() => {
    if (announcedRef.current) return;
    announcedRef.current = true;

    if (authenticated && destination) {
      const msg = { type: "LOGIN", destination, at: Date.now() };
      try {
        if (typeof window.BroadcastChannel !== "undefined") {
          const bc = new BroadcastChannel(SESSION_CHANNEL);
          bc.postMessage(msg);
          bc.close();
        }
        localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(msg));
      } catch {}
    } else if (!authenticated && typeof window !== "undefined" && window.location.search.includes("signedOut=1")) {
      const msg = { type: "LOGOUT", at: Date.now() };
      try {
        if (typeof window.BroadcastChannel !== "undefined") {
          const bc = new BroadcastChannel(SESSION_CHANNEL);
          bc.postMessage(msg);
          bc.close();
        }
        localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(msg));
      } catch {}
    }
  }, [authenticated, destination]);

  // Listen for session changes from other tabs
  useEffect(() => {
    let bc: BroadcastChannel | null = null;

    const handleEvent = (eventData: { type?: string; destination?: string; at?: number } | null) => {
      if (!eventData || typeof eventData !== "object") return;

      if (eventData.type === "LOGIN") {
        if (isGuestPath(pathname)) {
          const target = eventData.destination || destination || "/staff";
          window.location.replace(target);
        }
      } else if (eventData.type === "LOGOUT") {
        if (!isGuestPath(pathname)) {
          window.location.replace("/sign-in?signedOut=1");
        }
      }
    };

    if (typeof window.BroadcastChannel !== "undefined") {
      try {
        bc = new BroadcastChannel(SESSION_CHANNEL);
        bc.onmessage = (event) => {
          handleEvent(event.data);
        };
      } catch {}
    }

    const onStorage = (e: StorageEvent) => {
      if (e.key === SESSION_STORAGE_KEY && e.newValue) {
        try {
          handleEvent(JSON.parse(e.newValue));
        } catch {}
      }
    };
    window.addEventListener("storage", onStorage);

    // When the tab becomes visible or gains focus, verify session status if on a guest page
    const onVisibilityOrFocus = async () => {
      if (document.visibilityState === "visible" && isGuestPath(pathname)) {
        try {
          const res = await fetch("/api/session/status", { cache: "no-store" });
          if (res.ok) {
            const data = await res.json();
            if (data.authenticated && data.destination) {
              window.location.replace(data.destination);
            }
          }
        } catch {}
      }
    };

    document.addEventListener("visibilitychange", onVisibilityOrFocus);
    window.addEventListener("focus", onVisibilityOrFocus);

    return () => {
      if (bc) bc.close();
      window.removeEventListener("storage", onStorage);
      document.removeEventListener("visibilitychange", onVisibilityOrFocus);
      window.removeEventListener("focus", onVisibilityOrFocus);
    };
  }, [pathname, destination]);

  return null;
}
