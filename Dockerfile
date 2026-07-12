FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -q
COPY src/ src/
RUN ./mvnw clean package -DskipTests -q

FROM eclipse-temurin:21-jre
# Native Tesseract for chave-de-acesso OCR (Tess4J). Pulls eng traineddata
# into /usr/share/tesseract-ocr/*/tessdata, which TesseractOcrEngine probes.
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 10000
# Honor the platform-injected $PORT (Render/Fly/Railway set it; default 10000 keeps
# the self-hosted box + compose port-mapping unchanged). Shell form so $PORT expands.
ENTRYPOINT ["sh", "-c", "java -jar app.jar --server.port=${PORT:-10000}"]
