package org.javaee7.batch.chunk.csv.database;

import static jakarta.batch.runtime.BatchRuntime.getJobOperator;
import static org.javaee7.util.BatchTestHelper.keepTestAlive;
import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.BatchStatus;
import jakarta.batch.runtime.JobExecution;
import jakarta.batch.runtime.Metric;
import jakarta.batch.runtime.StepExecution;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

import org.javaee7.util.BatchTestHelper;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.ArchivePaths;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The Batch specification provides a Chunk Oriented processing style. This style is defined by enclosing into a
 * transaction a set of reads, process and write operations via +jakarta.batch.api.chunk.ItemReader+,
 * +jakarta.batch.api.chunk.ItemProcessor+ and +jakarta.batch.api.chunk.ItemWriter+. Items are read one at a time, processed
 * and aggregated. The transaction is then committed when the defined +checkpoint-policy+ is triggered.
 *
 * include::myJob.xml[]
 *
 * A very simple job is defined in the +myJob.xml+ file. Just a single step with a reader, a processor and a writer.
 *
 * This job will read a file from the system in CSV format:
 * include::MyItemReader#open[]
 * include::MyItemReader#readItem[]
 *
 * Process the data by transforming it into a +Person+ object:
 * include::MyItemProcessor#processItem[]
 *
 * And finally write the data using JPA to a database:
 * include::MyItemWriter#writeItems[]
 *
 * @author Roberto Cortez
 */
@RunWith(Arquillian.class)
public class BatchCSVDatabaseTest {
    
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * We're just going to deploy the application as a +web archive+. Note the inclusion of the following files:
     *
     * [source,file]
     * ----
     * /META-INF/batch-jobs/myJob.xml
     * /META-INF/persistence.xml
     * /META-INF/create.sql
     * /META-INF/drop.sql
     * /META-INF/mydata.csv
     * ----
     *
     * * The +myJob.xml+ file is needed for running the batch definition.
     * * The +persistence.xml+ file is needed for JPA configuration, create schema, load-data and drop schema.
     * * The +create.sql+ file has the necessary database schema for the data.
     * * The +drop.sql+ file has the required commands to drop the database schema created.
     * * The +mydata.csv+ file has the data to load into the database.
     */
    @Deployment
    public static WebArchive createDeployment() {
        WebArchive war = ShrinkWrap.create(WebArchive.class)
            .addClass(BatchTestHelper.class)
            .addPackage("org.javaee7.batch.chunk.csv.database")
            .addAsResource("META-INF/batch-jobs/myJob.xml")
            .addAsResource("META-INF/persistence.xml")
            .addAsResource("META-INF/create.sql")
            .addAsResource("META-INF/drop.sql")
            .addAsResource("META-INF/mydata.csv");
        System.out.println(war.toString(true));
        return war;
    }

    /**
     * In the test, we're just going to invoke the batch execution and wait for completion. To validate the test
     * expected behaviour we need to query the +jakarta.batch.runtime.Metric+ object available in the step execution.
     *
     * The batch process itself will read and write 7 elements of type +Person+. Commits are executed after 3 elements
     * are read.
     *
     * @throws Exception an exception if the batch could not complete successfully.
     */
    @SuppressWarnings("unchecked")
    @Test
    public void testBatchCSVDatabase() throws Exception {
        
        JobOperator jobOperator = getJobOperator();
        Long executionId = jobOperator.start("myJob", new Properties());
        JobExecution jobExecution = jobOperator.getJobExecution(executionId);
        
        jobExecution = keepTestAlive(jobExecution);

        List<StepExecution> stepExecutions = jobOperator.getStepExecutions(executionId);
        for (StepExecution stepExecution : stepExecutions) {
            if (stepExecution.getStepName().equals("myStep")) {
                Map<Metric.MetricType, Long> metricsMap = BatchTestHelper.getMetricsMap(stepExecution.getMetrics());

                // <1> The read count should be 7 elements. Check +MyItemReader+.
                assertEquals(7L, metricsMap.get(Metric.MetricType.READ_COUNT).longValue());
                
                // <2> The write count should be the same 7 read elements.
                assertEquals(7L, metricsMap.get(Metric.MetricType.WRITE_COUNT).longValue());
                
                // <3> The commit count should be 4. Checkpoint is on every 3rd read, 4 commits for read elements.
                assertEquals(3L, metricsMap.get(Metric.MetricType.COMMIT_COUNT).longValue());
            }
        }

        Query query = entityManager.createNamedQuery("Person.findAll");
        List<Person> persons = query.getResultList();

        // <4> Confirm that the elements were actually persisted into the database.
        assertEquals(7L, persons.size());
        
        // <5> Job should be completed.
        assertEquals(jobExecution.getBatchStatus(), BatchStatus.COMPLETED);
    }
}
