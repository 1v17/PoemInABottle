# Architectural Trade-offs for Cloud-Native Applications: A Case Study of "Poem In A Bottle"

## Executive Summary

This report examines the architectural trade-offs between serverless and traditional server-based deployments for low-data-flow applications in AWS. Using the "Poem In A Bottle" collaborative poetry application as a case study, we analyze **cost efficiency**, **operational overhead**, **scalability characteristics**, and **performance implications** of two distinct implementation approaches. The findings demonstrate that while serverless architectures offer compelling advantages for low-traffic applications with variable workloads, traditional server-based architectures remain competitive for stable, predictable workloads.

## 1. Introduction

Cloud-native application development presents organizations with multiple architectural approaches, each with distinct advantages and limitations. This report focuses on a practical comparison between traditional server-based architecture and serverless architecture within the AWS ecosystem.

"Poem In A Bottle" is a collaborative poetry application where users contribute lines to community poems. This application represents a typical low-to-moderate data flow service with variable traffic patterns, making it an ideal candidate for comparing architectural approaches.

We assume this application will expect 400,000 requests per day, where `POST` request and `GET` requests contribute half and half. The peak hour should be 5 hours in the evening and at night, when people get back home from work. Within the 5 hours the 2/3 of total requests (266,666) are sending to the server.

The primary objectives of this report are to:

- Evaluate the cost implications of each architectural approach
- Assess operational complexity and maintenance requirements
- Analyze scalability characteristics under different load patterns
- Measure performance characteristics across both architectures
- Provide evidence-based recommendations for similar applications

## 2. Architectural Approaches

We offer two distinct architecture implementations, while the poem aggregation strategy remains the same. The service will group lines by themes and limit a random number (from 3 to 14) of lines per poem.

The two implementations are:

### 2.1 EC2 + RabbitMQ + RDS MySQL (Traditional)

This architecture follows a more conventional pattern:

- **API Server**: Written in Go Gin, handles both `GET` and `POST` requests
  - `GET` requests directly query the MySQL database
  - `POST` requests are sent to RabbitMQ queue
- **Message Queue**: RabbitMQ for asynchronous processing
- **Consumer**: Aggregates sentences into poems and writes them to MySQL database
- **Database**: RDS MySQL stores the completed poems
- **Deployment**: API, RabbitMQ, and consumer can all be hosted on the same EC2 instance for cost efficiency, or separated for scalability

### 2.2 Lambda + SQS + DynamoDB (Serverless)

This architecture embraces a fully serverless approach:

- **API Handler**: Java-based AWS Lambda, handles both `GET` and `POST` requests
  - `GET` requests directly query DynamoDB, aggregate sentences into poems, and delete used sentences
  - `POST` requests send sentences to SQS queue
- **Message Queue**: Amazon SQS for asynchronous message processing
- **Consumer**: Java-based AWS Lambda, processes messages when triggered by SQS and writes sentences to DynamoDB
- **Database**: DynamoDB stores individual sentences by theme and timestamps before they're used in poems
- **Deployment**: All components are managed services with no server maintenance

## 3. Cost Analysis

To understand the financial implications of each approach, we analyzed AWS cost estimates at three different traffic levels:

| Daily Requests | Lambda+SQS+DynamoDB (Monthly) | 1 EC2+RabbitMQ+RDS (Monthly) | 3 EC2+RabbitMQ+RDS (Monthly) |
| -------------- | ----------------------------: | ---------------------------: | ---------------------------: |
| 30,000         |                         $2.79 |                       $32.67 |                       $39.97 |
| 300,000        |                        $27.70 |                       $32.67 |                       $39.97 |
| 400,000        |                        $39.84 |                       $32.67 |                       $39.97 |

| **N (Number of Requests)** | Items    | Sub-Item                 | Monthly Cost in USD |
| -------------------------- | -------- | ------------------------ | ------------------: |
|                            | Lambda   | Lambda w/ Functional URL |                1.04 |
|                            |          | Lambda for SQS to DB     |                0.52 |
|                            | SQS      |                          |                0.23 |
|                            | DynamoDB | DB for Sentences         |                 0.5 |
|                            |          | DB for Poems             |                 0.5 |
| **30,000**                 | **Sum**  |                          |            **2.79** |
|                            | Lambda   | Lambda w/ Functional URL |               12.11 |
|                            |          | Lambda for SQS to DB     |                5.29 |
|                            | SQS      |                          |                2.28 |
|                            | DynamoDB | DB for Sentences         |                4.01 |
|                            |          | DB for Poems             |                4.01 |
| **300,000**                | **Sum**  |                          |            **27.7** |
|                            | Lambda   | Lambda w/ Functional URL |               15.72 |
|                            |          | Lambda for SQS to DB     |                6.85 |
|                            | SQS      |                          |                5.93 |
|                            | DynamoDB | DB for Sentences         |                5.19 |
|                            |          | DB for Poems             |                5.19 |
| **390,000**                | **Sum**  |                          |           **38.88** |
|                            | Lambda   | Lambda w/ Functional URL |               16.13 |
|                            |          | Lambda for SQS to DB     |                7.01 |
|                            | SQS      |                          |                6.08 |
|                            | DynamoDB | DB for Sentences         |                5.31 |
|                            |          | DB for Poems             |                5.31 |
| **400,000**                | **Sum**  |                          |           **39.84** |

> Note: The EC2-based solution would likely require scaling beyond a single t2.micro instance at higher traffic level.

![lambda-price-prediction](/graphs/lambda-price-prediction.png)

### 3.1 Cost Implications

The serverless architecture demonstrates a clear cost advantage at low traffic levels, costing only 8.5% of the traditional architecture ($2.79 vs $32.67 monthly). This is due to the pay-per-use model that eliminates costs when the application is idle.

As traffic increases, the cost gap narrows. At medium traffic levels, the serverless approach costs 84.8% of the traditional architecture. At high traffic levels, the serverless approach becomes 19% more expensive.

This demonstrates the classic serverless pricing inflection point: serverless is most cost-effective for applications with low-to-medium traffic and variable workloads, while traditional architectures become more economical at high, steady traffic levels.

## 4. Operational Complexity

### 4.1 Deployment and Maintenance

**Traditional Architecture:**

- Requires server provisioning, OS updates, and security patches
- Needs configuration of networking, load balancing, and scaling policies
- RabbitMQ requires installation, configuration, and maintenance
- MySQL requires schema management, backups, and potential scaling

**Serverless Architecture:**

- No server management or OS maintenance
- Infrastructure defined as code with minimal configuration
- AWS handles security updates, scaling, and high availability
- Focus on application code rather than infrastructure

The serverless approach significantly reduces operational overhead, especially for teams without dedicated DevOps resources. The README demonstrates this difference clearly—the serverless deployment consists primarily of configuring managed services through the AWS console or CLI, while the traditional approach would require significantly more setup steps not fully detailed in the documentation.

## 5. Scalability Analysis

### 5.1 Scaling Characteristics

**Traditional Architecture:**

- Requires manual configuration of auto-scaling groups
- Scaling is less granular (entire EC2 instances)
- Scaling takes minutes rather than seconds
- Requires careful capacity planning
- MySQL scaling involves read replicas or vertical scaling

**Serverless Architecture:**

- Automatic scaling from zero to thousands of concurrent requests
- Pay only for actual compute resources used
- Fine-grained resource allocation
- No need to predict capacity requirements
- DynamoDB scales automatically with on-demand capacity

The serverless approach clearly offers superior scalability for applications with variable or unpredictable workloads. However, our cost analysis reveals that this flexibility comes at a premium for consistently high traffic levels.

## 6. Performance Considerations

### 6.1 Load Testing Methodology

We conducted comparative load testing using a custom client to simulate our expected daily load of 400,000 requests. We chose the this amount of requests because it has been identified as cost inflection point between serverless and traditional architectures. The test used 20 thread groups with 2-second intervals between each group to distribute load across the following deployment configurations:

- **Traditional architecture (distributed)**: Publisher, Consumer, and RabbitMQ on separate EC2 instances
- **Traditional architecture (consolidated)**: All services on a single EC2 instance
- **Serverless architecture**: Lambda functions with SQS

### 6.2 Traditional Architecture Performance

The traditional architecture demonstrated robust performance under load:

| Deployment Configuration | Throughput (req/sec) | P99 Latency (ms) | Success Rate |
| ------------------------ | -------------------: | ---------------: | -----------: |
| Three-server EC2         |               431.50 |            2,780 |         100% |
| Single-server EC2        |               380.23 |            3,010 |         100% |

The consolidated deployment shows expected performance degradation compared to the distributed setup, as resources are shared among all services. However, both configurations handled the full request volume successfully, presenting a clear trade-off between cost efficiency and performance.

### 6.3 Serverless Architecture Performance
The serverless architecture presented unique testing challenges. Our attempt to conduct equivalent load testing against the Lambda-based implementation was unsuccessful as AWS temporarily restricted our account. This restriction occurred because Lambda costs are calculated based on request volume within a short timeframe. Consequently, when our test compressed an entire day's worth of requests (400,000) into approximately 20 minutes, this concentrated load pattern exceeded the budget limits of our AWS learner lab account (50 dollars).

While we couldn't obtain complete performance metrics for the serverless implementation under the full load, preliminary testing with individual requests showed that the Lambda architecture processed single requests efficiently.

### 6.4 Performance Implications
These results highlight important considerations for architecture selection:

- **Burst capacity handling**: Traditional architectures provide predictable performance under consistent load but may struggle with unexpected traffic spikes without pre-provisioned capacity.

- **Cost predictability vs. performance**: The consolidated traditional deployment offers a middle ground between cost and performance, with only a 12% throughput reduction compared to the distributed deployment.

- **Testing challenges for serverless**: Serverless architectures require different testing approaches that account for their consumption-based pricing model and potential rate limiting.

- **Scale considerations**: For applications with steady, predictable workloads, traditional architectures can be sized appropriately to provide consistent performance, while serverless architectures may be better suited for highly variable workloads.

The performance testing reinforces our cost analysis findings—traditional architectures become more cost-effective at higher, consistent traffic levels, but serverless architectures offer flexibility for variable workloads without the need to pre-provision capacity.

## 7. Conclusions and Recommendations

### 7.1 Conclusions

### 7.2 Recommendations

For "Poem In A Bottle", it's recommended to implement the serverless architecture due to its cost advantages and alignment with low traffic patterns. This approach is particularly well-suited for this specific use case and will minimize expenses during periods of low activity.

For similar applications, serverless is the optimal choice for new projects with unpredictable or growing workloads, while traditional architecture remains better suited for applications requiring consistent performance or specialized runtime environments. Complex applications with mixed workload characteristics often benefit from hybrid approaches that leverage the strengths of both serverless and traditional infrastructures, allowing you to optimize for both cost efficiency and performance requirements.
