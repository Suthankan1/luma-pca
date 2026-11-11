.PHONY: up down backend frontend test-backend lint-frontend build-frontend verify
up:
	docker compose up -d postgres

down:
	docker compose down
backend:
	cd backend && mvn spring-boot:run
frontend:
	cd frontend && npm run dev
test-backend:
	mvn -f backend/pom.xml test
lint-frontend:
	npm --prefix frontend run lint
build-frontend:
	npm --prefix frontend run build
verify:
	python3 scripts/verify_structure.py
