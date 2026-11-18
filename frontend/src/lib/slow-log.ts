/**
 * Backend calls slower than LUMA_SLOW_API_MS (default 800 ms) go to the server log (Vercel function logs), to find what
 * makes a page slow. Only the method, the path with IDs masked, the status and the time: never tokens, bodies or queries.
 */
export function slowCallLine(method: string, path: string, status: number, ms: number, thresholdMs = Number(process.env.LUMA_SLOW_API_MS ?? 800)) {
  if (!(ms >= thresholdMs)) return null;
  const shape = path.split("?")[0].replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi, ":id");
  return `[luma] slow api ${method} ${shape} ${status} ${Math.round(ms)} ms`;
}

export function logIfSlow(method: string, path: string, status: number, ms: number) {
  const line = slowCallLine(method, path, status, ms);
  if (line) console.warn(line);
}
