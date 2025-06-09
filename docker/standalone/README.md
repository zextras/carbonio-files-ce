# Run files locally with Docker

Steps:
    1. `mvn clean install -DskipTests=true`
    2. `cd docker/standalone`
    3. `docker compose up --build`
    4. Browse files on `http://localhost:9000/`, backend accessible on `http://127.78.0.16:10000`
    5. Login using `test`/`assext`