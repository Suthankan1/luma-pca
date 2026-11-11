# Project scripts
Run `python3 scripts/verify_structure.py` after extracting the archive. Use `docker compose up -d postgres` for the local database. Backend runs `mvn -f backend/pom.xml test` and `mvn -f backend/pom.xml spring-boot:run`; frontend runs `npm --prefix frontend install`, `lint`, `typecheck` and `build`.
