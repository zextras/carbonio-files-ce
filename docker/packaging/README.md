# Build packages locally with Docker

Here you can build Ubuntu 20/22/24 and RHEL 8/9 packages using a simple Docker container.

Steps:
    1. `mvn clean install -DskipTests=true`
    2. `./build.sh`
    3. You will find the artifacts under artifacts/