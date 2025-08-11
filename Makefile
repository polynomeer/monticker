dev-api-price:
	docker-compose up -d
	./gradlew :app-api-price:bootRun
