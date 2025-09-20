# AWS Lambda Scheduler

## Features

- Invokes AWS Lambda functions according to the specified schedule
- Allows creating AWS Lambda functions on the fly
- Recurrent jobs via Quartz-flavor of cron expressions
- One-time jobs (can be specified via cron expression like `0 0 16 25 12 ? 2025` — Dec 25, 2025, 16:00:00 or using
  `maxExecutions = 1`)
- Infinitely recurring jobs via `maxExecutions = null`
- Persists jobs between restarts with SQLite
- Keeps execution log
- Handles jobs skipped while the service was offline

## Overview

![Architecture diagram](https://github.com/tubignat/scheduler/blob/main/diagram.png?raw=true)

**Scheduler** is a generalized timing engine. It creates coroutines in memory that suspend until an execution time.
Scheduler persists
job descriptions and their state via the Persistence interface and is able to resume jobs after a restart.

**AWS Lambda Executor**. Handles AWS API, responsible for creating and invoking AWS Lambda functions.

**Persistence**. Storage abstraction. Currently, implemented via SQLite, can be extended to support other storages.

**Job**. A job configuration that can spawn a coroutine. Jobs accept Quartz-style cron expression, payload and two
options:
`maxExecutions` — defines how many times to execute the job in total and `skipped` — defines hwo to handle executions
that
were skipped during a service restart.

### Pros of this approach

**Flexible**. Can be easily extended to support other types of executors, not only AWS Lambdas.

**High precision**. Scheduling happens at the CPU level. Even if run against a database in a distributed
environment, the DB latency does not affect the precision.

**Easy to scale**. In a distributed environment, a simple sharding by jobId will unlock scaling to millions of active
jobs.

**Cost-efficient**. With relaxed consistency (i.e., if executions are not logged immediately, but flushed at a regular
interval) can be very cost-efficient with a large throughput of executions, since an execution doesn't rely on a DB
request.

### Limitations

**Memory-bound**. The solution loads all coroutines into memory, which can incur memory costs at a large scale. Can be
improved by only loading soon-to-execute jobs and offloading the rest.

### Missing features

**Deduplication**. If the service crashed or was shutdown ungracefully during the job execution, the job may be executed
again upon restart. Can be improved by a lookup request to AWS execution history when a job is recreated from the DB
with the status = RUNNING, because this status means the job might have already been started.

**Retries and error handling**. Currently, the job will be marked as errored even if the service is unable to reach
AWS API, even if it's a transient network connection error. The same will happen if the job itself encounters a
retriable
error. Ideally, the user should be able to define a retry policy based on the specifics of the invoked function.

**Track expected execution time**. Currently, the execution log only stores the actual execution time. Knowing the
expected time will unlock important metrics (for example, execution accuracy SLA). Also, expected execution can be used
as an idempotency key for the invoked function to allow for advanced deduplication strategies. 
