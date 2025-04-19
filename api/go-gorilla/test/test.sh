#!/bin/bash

# curl "http://localhost:8080/poem"

themes=("love" "death" "nature" "beauty" "")

for i in {1..16}
do
  # Pick a random theme
  theme=${themes[$((RANDOM % 5))]}
#   echo $theme
  
  curl -X POST "http://localhost:8080/sentence" \
       -H "Content-Type: application/json" \
       -d '{"content":"test sentence'"$i"'","author":1,"theme":"'"$theme"'"}'
done