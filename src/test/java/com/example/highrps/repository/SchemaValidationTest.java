package com.example.highrps.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.highrps.common.SQLContainerConfig;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate"})
@Import(SQLContainerConfig.class)
class SchemaValidationTest {

    @Autowired
    private DataSource dataSource;

    /** Verifies that the configured data source acquires connections lazily. */
    @Test
    void contextLoads() {
        assertThat(dataSource).isInstanceOf(LazyConnectionDataSourceProxy.class);
    }
}
