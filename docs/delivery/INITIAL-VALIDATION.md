# Validation record for initial ZIP — 11 November 2026

## Confirmed in current authoring environment
- GitHub connector `get_repo` confirmed `Suthankan1/luma-pca` exists, belongs to user and had repository `size: 0` (empty) at inspection. No remote commits or files were changed by ZIP creation.
- Python structure script passed: 25 required files present.
- `backend/pom.xml` XML parsed successfully.
- Generated JSON and YAML files parsed successfully with Python.
- Java 21 `javac` successfully compiled `ConnectionInterval` and `AttendanceDurationCalculator`.
- A locally compiled Java smoke runner verified overlapping/duplicate intervals produce **39 minutes**, not double-counted.
- Full architecture/research source files and the supplied logo/legacy HTML demo were copied into the ZIP.

## Not run / cannot claim
- Maven `test`/Boot application startup not run: `mvn` is not installed in the authoring environment and external dependency DNS was unavailable.
- Next.js `npm install`, ESLint/typecheck/build and browser UI run not executed: npm registry DNS was unavailable.
- Docker Compose PostgreSQL and Flyway execution not run: Docker command is unavailable here.
- Testcontainers/Zoom API/AWS staging/production validation not performed.

## First maintainer validation commands
```bash
python3 scripts/verify_structure.py
docker compose up -d postgres
mvn -f backend/pom.xml test
mvn -f backend/pom.xml spring-boot:run   # second terminal
curl http://localhost:8080/api/v1/system/status
curl http://localhost:8080/actuator/health/readiness
npm --prefix frontend install
npm --prefix frontend run lint
npm --prefix frontend run typecheck
npm --prefix frontend run build
npm --prefix frontend run dev           # second terminal
```
Open `http://localhost:3000` and test desktop/mobile previews. Record any environment-specific defects and update `docs/ai/checkpoints/PROGRESS.md` before starting 001A auth.
