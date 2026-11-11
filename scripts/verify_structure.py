#!/usr/bin/env python3
"""Fail if the ZIP's foundational documentation/code contract is incomplete."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
required = [
  "README.md", "AGENTS.md", "CLAUDE.md", "frontend/package.json",
  "frontend/src/app/page.tsx", "frontend/src/app/staff/page.tsx",
  "frontend/src/app/student/page.tsx", "backend/pom.xml",
  "backend/src/main/java/com/physicscube/luma/LumaApplication.java",
  "backend/src/main/java/com/physicscube/luma/shared/config/SecurityConfig.java",
  "backend/src/main/resources/db/migration/V1__academy_course_baseline.sql",
  "docker-compose.yml", ".github/workflows/backend-ci.yml",
  ".github/workflows/frontend-ci.yml", "docs/product/PRD.md",
  "docs/architecture/full-production-blueprint.md", "docs/research/zoom-feasibility-full.md",
  "docs/security/JWT-AUTH.md", "docs/integration/ZOOM-RULES.md",
  "docs/ai/START-HERE.md", "docs/ai/checkpoints/PROGRESS.md",
  "docs/delivery/IMPLEMENTATION-PLAN.md", "docs/diagrams/domain-erd.mmd",
  "references/legacy-frontend-demo/app.js", "frontend/public/physics-cube-academy-logo.jpg"
]
missing = [x for x in required if not (root/x).is_file()]
if missing:
    print("Missing required files:", *missing, sep="\n- ")
    raise SystemExit(1)
print(f"Structure OK: {len(required)} required files present.")
