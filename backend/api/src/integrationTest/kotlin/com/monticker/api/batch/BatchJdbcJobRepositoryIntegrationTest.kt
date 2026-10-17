package com.monticker.api.batch

import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.repeat.RepeatStatus
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import java.util.function.Supplier
import javax.sql.DataSource

/**
 * B4-5 — Spring Batch 6에서 [BatchConfig]가 **JDBC 저장소**를 쓰는지 실제 Postgres(Flyway V17·V103)로 본다.
 *
 * Batch 6의 `@EnableBatchProcessing`은 그것만으로는 메모리 저장소라, 빌드·기동이 다 되는데 잡 실행 이력만 조용히
 * BATCH_* 테이블에서 사라진다. 인스턴스 id가 V103의 새 시퀀스(구 BATCH_JOB_SEQ보다 1,000,000 앞)에서 나오는지도 본다.
 */
class BatchJdbcJobRepositoryIntegrationTest : PostgresIntegrationTest() {

    @Test
    fun `a job run is recorded in the BATCH tables with an id from the batch 6 sequence`() {
        val ctx = AnnotationConfigApplicationContext().apply {
            registerBean("dataSource", DataSource::class.java, Supplier { dataSource })
            registerBean("transactionManager", DataSourceTransactionManager::class.java, Supplier { DataSourceTransactionManager(dataSource) })
            register(BatchConfig::class.java)
            refresh()
        }
        ctx.use {
            val repo = it.getBean(JobRepository::class.java)
            val tx = it.getBean(DataSourceTransactionManager::class.java)
            val step = StepBuilder("b4-5-probe-step", repo).tasklet({ _, _ -> RepeatStatus.FINISHED }, tx).build()
            val job = JobBuilder("b4-5-probe", repo).start(step).build()

            val execution = it.getBean(JobOperator::class.java)
                .start(job, JobParametersBuilder().addLong("run", System.nanoTime()).toJobParameters())

            assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
            assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM batch_job_execution WHERE job_execution_id = ?", String::class.java, execution.id,
            )).isEqualTo("COMPLETED")
            assertThat(jdbcTemplate.queryForObject(
                "SELECT job_instance_id FROM batch_job_instance WHERE job_name = 'b4-5-probe'", Long::class.java,
            )).isGreaterThanOrEqualTo(1_000_000L)
        }
    }
}
