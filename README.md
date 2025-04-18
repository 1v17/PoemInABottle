# Poem In A Bottle
## Description
Poem In A Bottle is a distributed system where users contribute poetic lines, which are dynamically combined into collaborative poems. Users can submit lines to thematic "bottles," ensuring coherence in style and theme.

The backend, built with Go and DynamoDB / MySQL, is hosted on AWS and incorporates RabbitMQ for message queuing, AWS ELB for orchestration, and Redis for caching. An ELB efficiently distributes traffic across multiple instances, ensuring scalability and resilience. Poem In A Bottle transforms fragmented thoughts into shared poetic expressions, fostering creativity through decentralized collaboration. The user will get a random completed poem from the server. 
 
## Architecture
The project is built with the following technologies:
1. **Load Balancing**:

    AWS ELB (Elastic Load Balancer): Distribute incoming requests across multiple Go Gin instances running on EC2 or inside a Kubernetes cluster. (adjust the policies) 
 
2. **API and Worker Services**:

   - **Go Gin / Gorilla Microservices**: Split your application into different services: 
   - **API Gateway**: Handles user requests (submitting lines, fetching poems, etc.). 
   - **Poem Aggregator**: Gathers and assembles poems from submitted lines.

3. **Message Queue for Asynchronous Processing**:

   - **RabbitMQ**: Since poem formation is an async task, use RabbitMQ for event-driven architecture: 
        - When a user submits a line (with or without a theme), publish it to a queue. 
        - A consumer service listens to this topic and processes lines. 
 
4. **Database and Caching**:

   - **DynamoDB / MySQL**: Store user-submitted lines, completed poems, and metadata. 
   - **Redis**: Recently finished poems. (evaluate with and without cache)
 
5. **Kubernetes for Scalability**:

   - **EKS (Elastic Kubernetes Service)**: Deploy and scale your microservices dynamically. Kafka and Redis can also be deployed within the cluster. 
 
6. **Poem Generation Strategy**:

   - **Rule-based**: Group by themes and limit X lines per poem. X could be a random number.

## Client
- Java 11 or higher
- Gradle
  
### Building the Project

To build the project, navigate to the `client` directory and run the following commands:

```sh
./gradlew clean build
./gradlew shadowJar
```
The clean build command will clean the previous build artifacts and build the project. The shadowJar command will create a fat JAR file that includes all dependencies.

### Running the Load Test
To run the load test, use the following command:
```sh
java -jar build/libs/client-1.0-all.jar <threadGroupSize> <numThreadGroups> <delay> <IPAddr> [-c <useCircuitBreaker>] [-e <executorTimeoutMin>]
```


### Parameters
threadGroupSize: The number of threads in each thread group.
numThreadGroups: The number of thread groups.
delay: The delay between the start of each thread group in seconds.
IPAddr: The IP address of the server to test.
-c useCircuitBreaker (optional): Whether to use the circuit breaker feature (default is false).
-e executorTimeoutMin (optional): The executor timeout in minutes (default is 30).

### Example
```sh
java -jar build/libs/client-1.0-all.jar 10 20 2 http://localhost:8080 -c true -e 45
```

This command will start the load test with 10 threads per group, 20 thread groups, a 2-second delay between each group, targeting the server at http://localhost:8080, using the circuit breaker feature, and setting the executor timeout to 45 minutes.

### Results
The load test results, including response times and throughput, will be written to a CSV file in the results directory. The file name will follow the pattern response_time_size-<threadGroupSize>_<numThreadGroups>_groups.csv.

## Declaimer
The test data is:

1. [William Shakespeare's sonnets](/resources/154_Sonnets_Shakespeare.txt), a collection of 154 poems written in the late 16th century during the English Renaissance. Each sonnet consists of 14 lines, with a rhyme scheme of love, beauty, time, and mortality. We collected and cleaned the data from [Project Gutenberg](https://www.gutenberg.org/ebooks/1041), a digital library of free eBooks. The sonnets are in the public domain, and we are using them for educational purposes.

## To-Dos
By 2025/4/7:
- [x] Prepare testing data (lines, poems, etc.) - JH
- [x] Client
- [ ] API Services - JH
    - [x] POST /sentence
    - [x] POST /sentence/:theme
    - [x] GET /poem
    - [x] GET /poem/:theme
    - [ ] AWS Lambda for serverless functions
- [ ] Poem Aggregator Service
- [ ] GitHub Actions for CI/CD - JH

By 2025/4/17:
- [ ] LB
- [ ] Database
- [ ] Message Queuing - JH SQS done

By 2025/4/24:
- [ ] Testing and report
