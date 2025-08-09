./gradlew clean shadowJar
docker build -t flink-lab-app:latest .
#helm uninstall flink-lab-app
#helm install flink-lab-app ./flink-lab-chart

