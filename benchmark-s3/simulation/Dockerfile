FROM collaborativestatemachines/cirrina:unstable AS cirrina

FROM gradle:8.14-jdk21 AS build
COPY --from=cirrina /opt/cirrina/lib /opt/cirrina/lib
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN gradle jar --no-daemon

FROM eclipse-temurin:25-jre
COPY --from=cirrina /opt/cirrina/lib /opt/cirrina/lib
COPY --from=build /app/build/libs/*.jar /opt/cirrina/lib/simulation.jar
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "-cp", "/opt/cirrina/lib/*", "at.ac.uibk.dps.cirrina.execution.object.SimulationKt"]
