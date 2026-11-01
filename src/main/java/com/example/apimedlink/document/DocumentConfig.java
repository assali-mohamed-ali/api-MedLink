package com.example.apimedlink.document;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MLink-123/125: the document wiring. The settings are validated when this context starts, before
 * the cipher exists; the MinIO client is built without contacting the store (the bucket is checked
 * lazily on the first operation).
 */
@Configuration
@EnableConfigurationProperties(DocumentProperties.class)
public class DocumentConfig {

    @Bean
    DocumentCipher documentCipher(DocumentProperties properties) {
        properties.validate();
        return new AesDocumentCipher(properties.getEncryption());
    }

    @Bean
    DocumentStorage documentStorage(DocumentProperties properties) {
        return new MinioDocumentStorage(properties.getStorage());
    }

    @Bean
    TransactionTemplate documentTransactionTemplate(PlatformTransactionManager transactions) {
        return new TransactionTemplate(transactions);
    }
}
