"use client";

import { useEffect, useRef, useState, type ComponentProps, type FormEvent } from "react";

const quotes = [
  { text: "Every class is a chance to ask a question and build on what you learned last time." },
  { text: "Little strokes fell great oaks.", author: "Benjamin Franklin" },
  { text: "Showing up regularly gives you more chances to practise, get feedback, and improve." },
  { text: "Constant dropping wears away stones.", author: "Benjamin Franklin" },
];

function nextQuote(current: number) {
  return (current + 1 + Math.floor(Math.random() * (quotes.length - 1))) % quotes.length;
}

function StudentSignInTip({ initialQuote }: { initialQuote: number }) {
  const [index, setIndex] = useState(initialQuote);
  const quote = quotes[index];

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setIndex(nextQuote(index));
    }, 5000);
    return () => window.clearTimeout(timer);
  }, [index]);

  return <div className="student-sign-in-tip">
    <p className="student-sign-in-status" role="status">Signing in…</p>
    <figure key={index}>
      <blockquote>{quote.text}</blockquote>
      {quote.author && <figcaption>{quote.author}</figcaption>}
    </figure>
  </div>;
}

export function StudentSignInForm({ children, ...props }: ComponentProps<"form">) {
  const [pendingQuote, setPendingQuote] = useState<number | null>(null);
  const [failed, setFailed] = useState(false);
  const submitting = useRef(false);

  useEffect(() => {
    const restore = (event: PageTransitionEvent) => {
      if (!event.persisted) return;
      submitting.current = false;
      setPendingQuote(null);
    };
    window.addEventListener("pageshow", restore);
    return () => window.removeEventListener("pageshow", restore);
  }, []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting.current) return;
    submitting.current = true;
    setFailed(false);
    setPendingQuote(Math.floor(Math.random() * quotes.length));
    const form = event.currentTarget;
    const fields = new URLSearchParams();
    for (const [key, value] of new FormData(form)) {
      if (typeof value === "string") fields.append(key, value);
    }
    try {
      const response = await fetch(form.action, {
        method: "POST",
        headers: { Accept: "application/json" },
        body: fields,
        redirect: "error",
        signal: AbortSignal.timeout(40000),
      });
      if (!response.ok) throw new Error("Sign-in request failed");
      const { destination } = await response.json();
      if (typeof destination !== "string" ||
          (destination !== "/student" && destination !== "/sign-in" && !destination.startsWith("/sign-in?"))) {
        throw new Error("Invalid sign-in destination");
      }
      window.location.assign(destination);
    } catch {
      submitting.current = false;
      setPendingQuote(null);
      setFailed(true);
    }
  }

  return <form {...props} data-sign-in-pending={pendingQuote !== null} onSubmit={submit}>
    {children}
    {failed && <p className="session-error" role="alert">Couldn’t reach the sign-in service. Please try again.</p>}
    {pendingQuote !== null && <StudentSignInTip initialQuote={pendingQuote} />}
  </form>;
}
