# Transformer
We're creating a new project called `transformer` that will be a scala library centered around loading data from disparate sources (similar to how apache Spark can read many file types and from many locations like S3 and GCS) into memory so we can run SQL based transformations and output data to disparate data sources. We will try to depend on the java/scala standard library as much as possible. Our aim is highly parallelize computations for high performance ETL jobs. Memory and CPU constraints are intended to be solved by the user of the library by provisioning more hardware; ETL jobs built with this library are standalone JVM apps written in java or scala. We'll create an example for users using bazel to compile a simple scala app using the library which can be run by using a local `java` installation since the output will be a bazel deploy jar.

Here's what you need to do:
- Create the project scaffolding using bazel + scala using ~/grid-game as a reference for how to do so
- Create separate modules for reading data, transforming data, and writing data
- Initially, we'll focus on reading a folder of csv or parquet files using similar semantics as apache spark (DO NOT DEPEND ON APACHE SPARK; we are using as inspiration)
- the most challenging part of the project is the need to parse SQL statements and turn them into an execution plan that can be highly parallelized on a single node for fast transformations before persisting the output.
    - think spark.sql... but not using spark as a dependency



The following is a pseudocode example of how we'd like to use this library which should guide how to structure the code and modules:
```scala
import com.transformer.{DataJob, InputFilePath, OutputFilePath, SQLTask, TemporalVariables}
val datajob = DataJob.new(
    temporalVariables: Option[TemporalVariables(executionTime=...)] # allows users to define what the executionTime is of the job. Then, in the SQL we allow for jinja style expressions for determining date strings e.g. {{ today }} returns 20260101 if today is 2026/01/01 05:30:21 UTC (all times are in UTC), {{ yesterday }} would be 20251231,  {{ current_hour }} would be 5, {{ current_minute }} would be 30, etc for all typical date attributes we'd want to use in a SQL string or the OutputFilePath str to parameterize e.g. a day=20260101 partition by using day={{ today }}. Allow users to add or subtract from each date variable which should return the transformed value in the same units as would be expected e.g. {{ today - 5 }} would be 5 days ago compared to 20260101 {{ today + 1 }} would be tomorrow compared to 20260101, {{ current_hour - 13 }} would be 13 hours ago, and so on (be exhaustive here)
    inputs=[
        InputFilePath("local/*.csv", options=..., viewName="table1") # we'll want options which can be extended for atypical CSV files. Should initially allow for setting options: `inferSchema: boolean` for whether schema + columns should be inferred (all types are string in this case). if `inferSchema` is false we'll need to specify columns names and data types. Use internal library specific data types that are defined and imported by the user e.g. DataType.STRING, DataType.INT, etc.
        InputFilePath("gs://test-bucket/*.parquet", cache=True, viewName="table2") # if s3:// or gs:// we'll want to download the files locally if cache bool is set to true (should default to true). Otherwise download the files into the cache after first clearing out the existing files
    ]
    sql=[
        SQLTask(
           sqlString or sqlFile for the actual SQL  which references view names from the InputFilePaths to form a result set which is the output
           outputFile: Option[OutputFilePath] if None then no output otherwise persist locally. If output starts with s3:// or gs:// copy local output to   
           validations: sqlString or sqlFile that allows for defining a DBT style SQL check on the output of the task at the end of the task's execution. If the result set has more than 0 rows then the validation is a failure and the task should fail while returning details about what's in memory to ease debugging the failure. If a validation is a failure, we should persist the output result set even if it isn't persisted typically. 
        )
    ]
    validationResultsOutput: Option[OutputFilePath] allow user to override where we save validation results + 
)
# runs the job, either succeeds and outputs are persisted or fails someway through due to invalid SQL, validation failures, or local resources being exceeded (OOM, etc)
datajob.Run
```
