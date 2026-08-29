package com.relyon.economizai.repository;

import com.relyon.economizai.model.LlmDisagreement;
import com.relyon.economizai.model.Product;
import com.relyon.economizai.model.enums.ProductCategory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V64__llm_layers.sql creates llm_disagreements WITHOUT an updated_at column,
 * but {@link LlmDisagreement} extends {@link com.relyon.economizai.model.BaseEntity},
 * which requires one on every select. Simulates the real migration's table shape
 * (Hibernate's test-only create-drop schema would otherwise mask the gap) and
 * proves the admin LLM report query blows up with a SQLGrammarException.
 */
@DataJpaTest
@ActiveProfiles("test")
class LlmDisagreementRepositoryTest {

    @Autowired private LlmDisagreementRepository disagreementRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void findOpenDisagreements_failsBecauseMigrationNeverAddedUpdatedAtColumn() {
        var product = productRepository.save(Product.builder()
                .normalizedName("Arroz Tio Joao")
                .category(ProductCategory.GROCERIES)
                .build());
        disagreementRepository.save(LlmDisagreement.builder()
                .product(product)
                .field("category")
                .build());
        entityManager.flush();

        entityManager.createNativeQuery("ALTER TABLE llm_disagreements DROP COLUMN updated_at").executeUpdate();
        entityManager.clear();

        assertThrows(InvalidDataAccessResourceUsageException.class,
                () -> disagreementRepository.findTop100ByResolvedAtIsNullOrderByCreatedAtDesc());
    }
}
