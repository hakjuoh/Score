package org.oagi.score.gateway.http.api.ai_management.catalog.repository.jooq;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class JooqAiDatabaseCatalogRepositoryTest {

    @Test
    void preservesANullTemperatureWhenNoProfileDefaultExists() {
        assertThat(JooqAiDatabaseCatalogRepository.decimal(null, null)).isNull();
        assertThat(JooqAiDatabaseCatalogRepository.decimal(null, 0.7)).isEqualTo(0.7);
        assertThat(JooqAiDatabaseCatalogRepository.decimal(
                BigDecimal.valueOf(0.3), 0.7)).isEqualTo(0.3);
    }
}
