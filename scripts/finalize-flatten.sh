#!/bin/bash

set -e
set -x 

MEETING_ID=$1
DIR=$2
FORMAT=$3


echo "Running $0 for $MEETING_ID, $DIR, $FORMAT" 

if [[ "$FORMAT" == "MKA" ]] ;then
  /scripts/flatten-mka.sh "${DIR}/recording.mka" "${DIR}/recording-flat.wav"
fi

if [[ "$JMR_FINALIZE_WEBHOOK" != "" ]] ;then
  curl -s -o /dev/null "$JMR_FINALIZE_WEBHOOK?meetingId=$MEETING_ID"
fi
