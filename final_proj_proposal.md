# 1. Introduction

Building an application on AWS can be cost-effective—**if** we choose services wisely and anticipate usage patterns. This proposal outlines a general **low-cost** design approach, focusing on:

- **Pay-as-we-go** or low-tier resource usage when workloads are small or bursty.  
- **Simple, asynchronous processes** that can be batched, avoiding expensive always-on setups if immediate responses aren’t needed.  
- **Monitor & adapt** as usage grows to avoid unexpected cost spikes.

# 2. Proposed Architecture Components

## 2.1 Compute Layer

1. **AWS Lambda (Serverless)**  
   - Runs code only when triggered. This can be **very cheap** for sporadic workloads.  
   - Ideal if our traffic pattern is unpredictable, or if we have “batchable” tasks.

2. **EC2 Instances**  
   - Pay for instance uptime, regardless of usage.  
   - Can be cheaper than Lambda at higher, consistent traffic levels (e.g., thousands of requests per second).


## 2.2 Data Storage

1. **Amazon DynamoDB**  
   - Pay-per-request (on-demand) or provisioned capacity.  
   - Great for key-value or document-style data. Spiky traffic can remain cheap if read/write volume is low most of the time.

2. **RDS (or Aurora Serverless)**  
   - Use a small instance (e.g., db.t3.micro) for stable, moderate traffic with relational workloads.  
   - Aurora Serverless v2 provides an auto-scaling relational option if we need SQL but want usage-based pricing.

3. **Amazon S3**  
   - Very cheap for object storage.  
   - Ideal for static files, large text data, logs, or backups.

4. **MongoDB on a Small EC2**  
   - If we need document-based storage but want to avoid large management fees, a small T-class EC2 instance can be cheaper than a managed solution if our traffic is modest.

## 2.3 Messaging & Asynchronous Processing

- **Amazon SQS**  
  - Good for decoupling processes, letting we process messages in batches.  
  - Pay per request, typically very affordable if we batch calls. 

## 2.4 Caching (Optional)

- **Local Cache with HashMap**  
  - Only introduce caching if we have a high read volume that’s driving up database costs.

# 3. Roadmap and Step-by-Step Implementation

### **Step 1: Gather Usage Estimates**

1. **Identify request patterns**:
   - Estimated daily requests: e.g., 10,000 or 10 million?  
   - Peak concurrency: e.g., rarely more than 10 concurrent requests vs. hundreds at a time.
2. **Determine data size and throughput**:
   - Average request payload: e.g., 1KB or 1MB of text.  
   - Storage growth per month: e.g., 1GB or 100GB of new data?
3. **Prioritize response time**:
   - If users can accept delays of up to several seconds or minutes, serverless batch processing can be cheaper.  
   - If our application requires near-instant responses at scale, we may need always-on capacity.

### **Step 2: Compare Lambda vs. EC2 Breakpoints**

**Example Decision Guide**:
1. **Lambda**  
   - Great if our monthly request volume is relatively low (e.g., up to a few million requests per month).  
   - The formula for approximate cost is:  
     ```
     Monthly Lambda Cost ≈ (Number of Requests * Duration * Memory Config * Price per 1ms)
     ```
   - Also consider free tier usage for the first 1 million requests/month.

2. **EC2**  
   - Might be cheaper if we have consistently high usage.  
   - For instance, a **t3.micro** in us-east-1 can be just a few dollars/month on-demand and can handle moderate traffic if well-optimized.  
   - If we run many short-lived Lambda invocations that collectively add up to thousands of CPU hours, a single always-on EC2 instance might cost less.

**When to Switch**:
- If our calculated monthly Lambda cost exceeds the monthly cost of a suitable EC2 instance (including overhead for load balancing, etc.), consider switching to or supplementing with EC2.

### **Step 3: Choose a Data Store**

- **If purely key-value**: Start with DynamoDB on-demand.  
- **If relational**: A small RDS instance (db.t3.micro) or Aurora Serverless.  
- **If just storing text blobs**: S3 plus a minimal metadata table.  
- **Evaluate cost**:  
  - Compare estimated DynamoDB read/write costs vs. RDS instance + I/O.  
  - For extremely spiky usage, DynamoDB on-demand often wins.

### **Step 4: Build a Minimal MVP**

1. **Prototype** our core application with a small, cheap setup:  
   - A single Lambda function (or t3.micro instance), SQS, and a small data store.  
   - Store logs and metrics in CloudWatch.  
2. **Validate** functionality. Ensure the MVP addresses core user flows.

### **Step 5: Measure and Monitor**

1. **CloudWatch** for throughput metrics and cost usage.  
2. **Analyze** actual request patterns (peak concurrency, read/write volume to the DB).
3. **Refine** the architecture—e.g., if DB read traffic is high, investigate caching. If Lambda invocations exceed cost of an EC2 instance, migrate or combine solutions.

### **Step 6: Production Hardening**

1. **Auto-Scaling**  
   - Configure Lambda concurrency limits or EC2 auto-scaling groups to handle spikes gracefully.  
2. **Network & Security**  
   - Private subnets for DB, IAM roles to limit permissions, encryption at rest and in transit.
3. **Cost Alerting**  
   - Set CloudWatch budgets or AWS Budgets to notify us if monthly costs exceed thresholds.
# 4. Example Cost Evaluation Flow

1. **Estimates**:  
   - Monthly requests: ~2 million.  
   - Each request runs ~200ms of compute at 128MB memory usage in Lambda.  
2. **Lambda Cost**:  
   - Using AWS’s pricing calculator:  
     - 2 million invocations × 200ms = 400,000,000 ms.  
     - 400 million ms × (128MB allocated / 1024) × approx. \$0.00001667 = ~\$6.67/month (plus small request charge).  
3. **EC2 Alternative**:  
   - A single t3.micro might cost around \$7–\$9/month on-demand (depending on region), ignoring data transfer and EBS storage.  
   - If we can handle all 2 million monthly requests with minimal concurrency, an EC2 t3.micro might suffice.  
   - But if we experience bursts of concurrency, we may need more or bigger instances, increasing cost.

In this example, the costs are close—**Lambda** might be marginally cheaper if usage is spiky, while a **t3.micro** might be simpler if usage is evenly distributed. Real-world scenarios will vary, so it’s key to measure actual usage patterns.

# 5. Conclusion

To design an AWS application at the **lowest cost**:

1. **Start Small & Serverless**: Especially for sporadic workloads.  
2. **Monitor Usage**: Let real data guide decisions.  
3. **Migrate or Hybridize** If Needed: Combine serverless and always-on where it makes sense.  
4. **Optimize**: Use cost alerting, track spikes, and refine our choices over time.

By following the steps in the **roadmap**—especially **evaluating our traffic**, **comparing Lambda vs. EC2** costs at different throughput levels, and using **appropriate data stores**—we’ll systematically arrive at a minimal-cost design that suits our specific workloads and growth trajectory.