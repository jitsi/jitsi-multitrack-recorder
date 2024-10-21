set -e
set -x

mkdir -p ~/recordings
mvn install -DskipTests -Dktlint.skip
docker build -t jitsi-multitrack-recorder:latest . && docker run -p 8989:8989 -v ~/recordings:/data jitsi-multitrack-recorder:latest
