# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Development

```bash
# Build and run tests
mvn clean install

# Build without tests/linting
mvn clean install -DskipTests -Dktlint.skip

# Run linting only
mvn ktlint:check

# Run tests only
mvn test
```

`~/jmr/rerun.sh` — convenience script that builds, builds the Docker image, and runs it with `~/recordings` mounted and the flatten finalize script.

Kotlin compilation uses `-Werror`, so all warnings are errors.

## Architecture

WebSocket-based multi-track audio recorder. Jitsi Videobridge connects via WebSocket and sends JSON-encoded media events (Opus audio packets). The recorder captures per-participant audio tracks and writes them to Matroska (MKA) or JSON format.

**Data flow:**

```
Videobridge → WebSocket (/record/{meetingId})
    → RecordingSession
    → MediaJsonMkaRecorder (or MediaJsonJsonRecorder)
    → MkaRecorder (EBML/Matroska writer)
    → recording.mka
    → [optional] finalize script (e.g. flatten-mka.sh → recording-flat.wav)
```

**Key components:**

- `Main.kt` — Ktor server; WebSocket on `:8989/record`, HTTP `/metrics`
- `RecordingSession.kt` — Per-meeting session; delegates to recorder format, runs finalize script
- `MediaJsonMkaRecorder.kt` / `MediaJsonJsonRecorder.kt` — Format-specific recorders
- `MkaRecorder.kt` — Writes multi-track Opus audio in Matroska format using jebml
- `OpusPacket.kt` — Opus frame parsing
- `PacketLossConcealmentInserter.kt` — Fills audio gaps with Opus PLC; large gaps (>`max-gap-duration`) start a new track instead
- `RecorderMetrics.kt` — Prometheus metrics (session count, PLC ms, errors, etc.)

**Configuration** (`src/main/resources/reference.conf`):

```
jitsi-multitrack-recorder {
  recording {
    directory = "/tmp"
    format = "mka"               # "mka" or "json"
    max-gap-duration = 5 minutes
  }
  port = 8989
  log-finalize-output = true
  # finalize-script = /path/to/script.sh
}
```

Docker env vars: `JMR_DIRECTORY`, `JMR_FORMAT`, `JMR_FINALIZE_SCRIPT`, `JMR_FINALIZE_WEBHOOK`.

## Tests

Tests use Kotest (JUnit5). Test data lives in `src/test/resources/` as JSON files (`sample.json`, `sample-gap.json`, `sample-stereo.json`, etc.).

- `MkaRecorderTest.kt` — Integration test; reads sample JSON, records, verifies EBML output
- `OpusPacketTest.kt` — Unit tests for Opus TOC/frame parsing
- `PacketLossConcealmentTest.kt` — Tests PLC gap-filling logic
