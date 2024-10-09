set -e
set -x

mvn install -DskipTests -Dktlint.skip
docker build -t jitsi-multitrack-recorder:latest . && docker run -p 8989:8989 jitsi-multitrack-recorder:latest
