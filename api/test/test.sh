curl -X POST "http://localhost:8080/sentence" -d '{"content": "I am a sentence.", "author": 123}'
echo "\n"
curl -X POST "http://localhost:8080/sentence" -d '{"content": "I am another sentence.", "author": 123}'
echo "\n"

curl "http://localhost:8080/poem"
echo "\n"

curl -X POST "http://localhost:8080/sentence" -d '{"content": "I am a third sentence.", "author": 123}'
echo "\n"

curl -X POST "http://localhost:8080/sentence" -d '{"content": "I am a fourth sentence.", "author": 123}'
echo "\n"

curl "http://localhost:8080/poem"
echo "\n"
