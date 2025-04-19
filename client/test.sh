#!/bin/sh
./gradlew clean build
./gradlew shadowJar
java -jar build/libs/client-1.0-all.jar 1 10 2 http://localhost:8080 -c true -e 2