# Use the Flink 1.20.1 base image with Java 17
FROM flink:1.20.1-scala_2.12-java17

# Copy your Flink application JAR into the Flink userlib directory
COPY build/libs/flink-lab-app.jar /opt/flink/usrlib/flink-lab-app.jar