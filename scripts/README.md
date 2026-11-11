# Project scripts
Run `python3 scripts/verify_structure.py` after extracting the archive. Use `docker compose up -d postgres` for the local database. Backend runs `mvn -f backend/pom.xml test` and `mvn -f backend/pom.xml spring-boot:run`; frontend runs `npm --prefix frontend install`, `lint`, `typecheck` and `build`.

`scripts/auth-stack-smoke.sh` runs the real stack end to end (throwaway PostgreSQL 17 in Docker, the built backend jar, the Next.js dev server) and checks activation, browser sign-in, refresh rotation, replay revocation and sign-out. It needs Docker, JDK 21, Node, a built backend (`mvn -f backend/pom.xml package`) and installed frontend dependencies. It is not part of CI yet.
