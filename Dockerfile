# Use the Flink 1.20.1 base image with Java 17
FROM flink:1.20.1-scala_2.12-java17

# Copy your Flink application JAR into the Flink userlib directory
COPY build/libs/flink-lab-app.jar /opt/flink/usrlib/flink-lab-app.jar

# Copy the Avro schema file to a location accessible on the classpath
RUN mkdir -p /opt/flink/lib/avro
COPY src/main/resources/schema/user-action.avsc /opt/flink/lib/avro/UserAction.avsc