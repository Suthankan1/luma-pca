import { staffApi } from "./staff-api";
import type { ClassEngagement, OnlineObservation, ProvisionalAttendance } from "./staff-forms";
import type { ClassRun } from "./webinars";
import { liveNumbers, type LiveNumbers } from "./staff-home";

export const MAX_LIVE_CLASSES = 3;

export type LiveClassSnapshot = {
  sessionId: string;
  numbers: LiveNumbers | null;
  history: OnlineObservation[] | null;
  sampledAt: number;
  ended: boolean;
};

export type LiveSessionSnapshot = { attendance: ProvisionalAttendance; run?: ClassRun | null; engagement?: ClassEngagement };

export async function readLiveAttendance(ids: string[]): Promise<LiveClassSnapshot[]> {
  return Promise.all(ids.map(async sessionId => {
    const result = await staffApi<ProvisionalAttendance>(
      `/api/v1/class-sessions/${sessionId}/attendance?includeOnlineHistory=true`, undefined, 25000);
    const sampledAt = Date.now();
    if (!result.ok) return { sessionId, numbers: null, history: null, sampledAt, ended: result.status === 404 };
    const history = result.data.onlineHistory ?? null;
    return { sessionId, numbers: liveNumbers(result.data.students, history?.at(-1)?.online), history, sampledAt,
      ended: result.data.webinarEnded || Date.parse(result.data.classEndsAt) <= sampledAt };
  }));
}
