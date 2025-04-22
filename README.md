# Poem In A Bottle
## Description
Poem In A Bottle is a distributed system where users contribute poetic lines, which are dynamically combined into collaborative poems. Users can submit lines to thematic "bottles," ensuring coherence in style and theme.

The backend, built with Go / Java and DynamoDB / MySQL, is hosted on AWS and incorporates RabbitMQ /SQS for message queuing. Poem In A Bottle transforms fragmented thoughts into shared poetic expressions, fostering creativity through decentralized collaboration. The user will get a random completed poem from the server. 
 
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

## Lambda
This guide covers the setup and deployment process for the AWS Lambda functions used in the Poem In A Bottle application.

### Architecture Overview
The following AWS components are involved in the architecture:

- **AWS Lambda**:

   1. `piab-lambda` ([PostSentenceGetPoemHandler](javaLambda/lambda/src/main/java/PostSentenceGetPoemHandler.java)):
      - Handles HTTP endpoints for posting sentences and getting poems
      - Sends sentence data to SQS and queries DynamoDB for sentences to form poems
      - Uses function URL for direct invocation
   2. `piab-sqs-db` ([SentenceConsumer](javaLambda/lambda/src/main/java/SentenceConsumer.java)):
      - Processes messages from SQS
      - Stores processed sentences in DynamoDB
      - Uses SQS trigger for automatic invocation

- **Amazon SQS**: `sentences.fifo`

   Handles asynchronous message processing.

- **Amazon DynamoDB**: `sentences`

   Stores sentences by theme and timestamp.

### Setup Instructions
1. Create Amazon SQS

   1. Navigate to SQS:

   - From the AWS Management Console, click on the search bar at the top
   - Type "SQS" and select it from the results

   2. Create a new queue:

   - Click the Create queue button
   - Select FIFO queue type
   - Enter "sentences.fifo" for the Queue name
   > Note that the ".fifo" suffix is mandatory for FIFO queues

   3. Configure the queue:

   - Under FIFO queue settings:
      - Check `Content-based deduplication`
      > Note: we have a custom deduplication strategy by using author and timestamp to avoid duplicates, but by checking this option, SQS will automatically deduplicate messages based on their content
      - Check `High throughput FIFO queue`
   -  Leave every settings at their defaults, create the queue, and note the URL for later use

2. Build the Lambda Functions

   1. Navigate to the project directory and build the Lambda packages:
      
      - Build the common part:
      ```sh
      cd javaLambda/common/

      mvn clean package
      ```

      - Build the Lambda functions:
      ```sh
      cd ../lambda/

      mvn install:install-file -DgroupId=cs6650-final-project -DartifactId=Poem-In-A-Bottle-common -Dversion=1.0 -Dpackaging=jar -Dfile=../common/target/Poem-In-A-Bottle-common-1.0-SNAPSHOT.jar

      mvn clean package
      ```
      > Note: The `install:install-file` command is used to install the common JAR file into the local Maven repository, allowing the Lambda functions to reference it.

      This creates a JAR file at `target/Poem-In-A-Bottle-1.0-SNAPSHOT.jar`

   2. Create a lambda :
      - Go to the AWS Lambda console and click on the `Create function` button
      - Select `Author from scratch`
      - Enter the function name `piab-lambda`, `piab-sqs-db`
      - Select the runtime as `Java 21`
      - Select `use an existing role` - `LabRole` in `change default exectuion role`
      - Click on `Create function`

   3. Upload the JAR file to the Lambda
      - In the function code section, select `Upload from` and choose `.zip or .jar file`
      - Upload the JAR file created earlier
      - Set the handler to `PostSentenceGetPoemHandler::handleRequest` for `piab-lambda` and `SentenceConsumer::handleRequest` for `piab-sqs-db`
      - Click on `Save`

   4. Set environment variables
      - In the configuration tab, click on `Environment variables`
      - Add the following environment variables:
         - `SQS_URL`: The URL of the SQS queue you created earlier
      - Click on `Save`

   5. Test the Lambda function
      - Click on the `Test` button
      - Create a new test event with the template `AWS API Gateway Http API`:
      - Change `"rawPath": "/path/to/resource"` to `"rawPath": "/sentence"`,
         ```
            "http": {
                        "method": "POST",
                        "path": "/path/to/resource",
                        ...
                     },
         ```
         to
         ```
            "http": {
                        "method": "POST",
                        "path": "/sentence",
                        ...
                     },
         ```
         and 
         ```
            "body": "Hello from client!",
         ```
         to
         ```
            "body": "{ \"content\": \"This is a test sentence\", \"theme\": \"love\" }",
         ```
      - Click on `Test` again to run the test
      - Check the logs for any errors or issues

   6. Set the Lambda function URL for `piab-lambda`
      - In the configuration tab, click on `Function URL`
      - Click on `Create function URL`
      - Select `AWS_IAM` for the authentication type
      - Click on `Save`
      - Note the function URL for later use

### Testing the Lambda function
To test the Lambda function, you can use the following command:

- `POST /sentence`:
   ```sh
   curl -X POST <function_url>/sentence \
   -H "Content-Type: application/json" \
   -d '{"content": "This is a test sentence", "theme": "love"}'
   ```

- `GET /poem`:
   ```sh
   curl <function_url>/poem
   ```

## Declaimer
The test data is:

1. [William Shakespeare's sonnets](/resources/154_Sonnets_Shakespeare.txt), a collection of 154 poems written in the late 16th century during the English Renaissance. Each sonnet consists of 14 lines, with a rhyme scheme of love, beauty, time, and mortality. We collected and cleaned the data from [Project Gutenberg](https://www.gutenberg.org/ebooks/1041), a digital library of free eBooks. The sonnets are in the public domain, and we are using them for educational purposes.

## To-Dos
By 2025/4/7:
- [x] Prepare testing data (lines, poems, etc.) - JH
- [x] Client
- [x] API Services - JH
    - [x] POST /sentence
    - [x] POST /sentence/:theme
    - [x] GET /poem
    - [x] GET /poem/:theme
    - [x] AWS Lambda for serverless functions
- [x] Poem Aggregator Service

By 2025/4/17:
- [ ] LB
- [x] Database
- [x] Message Queuing - JH SQS done

By 2025/4/24:
- [ ] Testing and report
