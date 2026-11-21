/**
 * Decorative landing illustration: a teacher at the whiteboard while students take turns raising a hand, and each
 * answer ticks a row on a small attendance register. Pure SVG + CSS keyframes (see `.classroom-scene` in
 * globals.css); one 10.5 s loop, frozen into a still frame under prefers-reduced-motion. No real people or data.
 */
const students = [
  { x: 120, shirt: "#1b65a2", hair: "#2b2f45", say: "Present, sir!", style: "short" },
  { x: 262, shirt: "#22b5e2", hair: "#3d2a22", say: "Present!", style: "ponytail" },
  { x: 398, shirt: "#5b6fd0", hair: "#1f2433", say: "Here, sir!", style: "bun" },
] as const;

const registerRows = [56, 44, 50];
const skin = "#e3b18c";
const skinShade = "#cf9a74";
const projectile = "M218 194 Q280 64 342 194";

export function ClassroomScene() {
  return <div className="classroom-scene" aria-hidden="true">
    <svg viewBox="0 0 560 420" role="presentation" focusable="false">
      <defs>
        <linearGradient id="cs-wall" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#f8fbfe" /><stop offset="1" stopColor="#e5eff6" />
        </linearGradient>
        <linearGradient id="cs-floor" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#dbe7f0" /><stop offset="1" stopColor="#cddbe6" />
        </linearGradient>
        <filter id="cs-soft" x="-20%" y="-20%" width="140%" height="150%">
          <feDropShadow dx="0" dy="8" stdDeviation="9" floodColor="#2c5677" floodOpacity=".13" />
        </filter>
      </defs>

      {/* Room */}
      <rect width="560" height="420" fill="url(#cs-wall)" />
      <rect y="300" width="560" height="120" fill="url(#cs-floor)" />
      <line x1="0" y1="300" x2="560" y2="300" stroke="#c4d4e1" strokeWidth="2" />

      {/* Whiteboard */}
      <g filter="url(#cs-soft)">
        <rect x="34" y="34" width="334" height="180" rx="10" fill="#fff" stroke="#c5d4e0" strokeWidth="4" />
      </g>
      <rect x="62" y="214" width="278" height="7" rx="3.5" fill="#b9c9d6" />
      <rect x="92" y="208" width="22" height="6" rx="3" fill="#22b5e2" />
      <rect x="120" y="208" width="22" height="6" rx="3" fill="#22206a" />
      <text x="56" y="62" className="cs-label">KINEMATICS</text>
      <text x="56" y="104" className="cs-formula" fill="#22206a">v = u + at</text>
      <rect x="52" y="82" width="120" height="30" fill="#fff" className="cs-cover cs-cover-1" />
      <text x="56" y="146" className="cs-formula" fill="#1b65a2">s = ut + ½at²</text>
      <rect x="52" y="120" width="158" height="34" fill="#fff" className="cs-cover cs-cover-2" />
      <path d="M214 198 V96 M214 198 H352" stroke="#a9bccb" strokeWidth="2" fill="none" strokeLinecap="round" />
      <path d={projectile} pathLength={1} className="cs-trajectory" stroke="#22b5e2" strokeWidth="3" fill="none"
        strokeLinecap="round" strokeDasharray="1" />
      <circle r="5.5" fill="#1b65a2" className="cs-ball">
        <animateMotion dur="2.4s" repeatCount="indefinite" path={projectile} keyPoints="0;1" keyTimes="0;1"
          calcMode="spline" keySplines=".35 0 .65 1" />
      </circle>

      {/* Attendance register */}
      <g className="cs-register">
        <g filter="url(#cs-soft)">
          <rect x="402" y="22" width="142" height="116" rx="14" fill="#ffffffdd" stroke="#fff" strokeWidth="1.5" />
        </g>
        <text x="416" y="44" className="cs-label cs-label-strong">ATTENDANCE</text>
        {registerRows.map((width, i) => {
          const y = 64 + 22 * i;
          return <g key={y}>
            <circle cx="422" cy={y} r="7" fill={students[i].shirt} />
            <rect x="436" y={y - 3} width={width} height="6" rx="3" fill="#d5e2ec" />
            <circle cx="524" cy={y} r="8" fill="none" stroke="#c9d8e4" strokeWidth="1.5" />
            <g transform={`translate(524 ${y})`}>
              <g className={`cs-tick cs-tick-${i + 1}`}>
                <circle r="8" fill="#1f9d63" />
                <path d="M-3.6 0 L-1 2.7 L3.8 -2.7" stroke="#fff" strokeWidth="2" fill="none" strokeLinecap="round"
                  strokeLinejoin="round" />
              </g>
            </g>
          </g>;
        })}
        <rect x="416" y="125" width="114" height="4" rx="2" fill="#e1eaf1" />
        <rect x="416" y="125" width="114" height="4" rx="2" fill="#22b5e2" className="cs-progress" />
      </g>

      {/* Teacher */}
      <ellipse cx="452" cy="337" rx="36" ry="5" fill="#1d3550" opacity=".12" />
      <g className="cs-teacher">
        <rect x="438" y="266" width="12" height="68" rx="5" fill="#1e2c67" />
        <rect x="455" y="266" width="12" height="68" rx="5" fill="#1e2c67" />
        <rect x="433" y="328" width="19" height="7" rx="3.5" fill="#141a3a" />
        <rect x="455" y="328" width="19" height="7" rx="3.5" fill="#141a3a" />
        <rect x="471" y="204" width="11" height="56" rx="5.5" fill="#22206a" />
        <rect x="466" y="240" width="20" height="26" rx="2.5" fill="#22b5e2" transform="rotate(-8 476 253)" />
        <circle cx="478" cy="260" r="6" fill={skin} />
        <rect x="447" y="186" width="10" height="14" rx="3" fill={skinShade} />
        <path d="M428 214 Q428 200 442 198 H462 Q476 200 476 214 L474 272 H430 Z" fill="#22206a" />
        <path d="M445 199 L452 217 L459 199 Z" fill="#e8f4fb" />
        <circle cx="452" cy="174" r="16" fill={skin} />
        <circle cx="463" cy="176" r="3.2" fill={skinShade} />
        <path d="M436 176 Q434 156 453 155 Q471 156 469 173 Q463 165 451 165 Q441 166 438 181 Z" fill="#2b2f45" />
        <circle cx="443" cy="175" r="1.7" fill="#2b2f45" />
        <path d="M440 182 Q443 185 447 183" stroke="#9b5f45" strokeWidth="1.4" fill="none" strokeLinecap="round" />
        <g transform="translate(432 208)">
          <g className="cs-teacher-arm">
            <path d="M0 0 L-36 -18" stroke="#22206a" strokeWidth="11" strokeLinecap="round" />
            <line x1="-41" y1="-21" x2="-112" y2="-56" stroke="#8a6a4a" strokeWidth="3" strokeLinecap="round" />
            <circle cx="-112" cy="-56" r="2.6" fill="#e05a47" />
            <circle cx="-40" cy="-20" r="6" fill={skin} />
          </g>
        </g>
      </g>

      {/* Students, seen from the back of the room */}
      {students.map((s, i) => <g key={s.x} transform={`translate(${s.x} 0)`} className={`cs-student cs-student-${i + 1}`}>
        <g transform="translate(22 346)">
          <g className="cs-arm">
            <rect x="-5" y="-62" width="10" height="62" rx="5" fill={s.shirt} />
            <circle cy="-64" r="7" fill={skin} />
          </g>
        </g>
        <g className="cs-sit">
          <path d="M-34 420 V366 Q-34 338 -12 336 H12 Q34 338 34 366 V420 Z" fill={s.shirt} />
          <rect x="-6" y="324" width="12" height="14" rx="3" fill={skinShade} />
          <circle cx="-17" cy="316" r="4" fill={skin} />
          <circle cx="17" cy="316" r="4" fill={skin} />
          <circle cy="313" r="17" fill={s.hair} />
          {s.style === "ponytail" && <path d="M-4 326 Q-6 346 4 352 Q8 338 4 326 Z" fill={s.hair} />}
          {s.style === "bun" && <circle cy="292" r="8" fill={s.hair} />}
        </g>
        <g transform="translate(12 268)">
          <g className="cs-bubble">
            <rect x={-(s.say.length * 6.3 + 22)} y="-30" width={s.say.length * 6.3 + 22} height="26" rx="13" fill="#fff"
              stroke="#d0dde8" />
            <path d="M-20 -5 L-2 5 L-30 -5 Z" fill="#fff" />
            <text x={-(s.say.length * 6.3 + 22) / 2} y="-13" className="cs-say">{s.say}</text>
          </g>
        </g>
      </g>)}
    </svg>
  </div>;
}
