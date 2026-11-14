package org.example.block5.repository;

import org.example.block5.data.EmailData;
import org.example.block5.data.EmailStatus;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmailRepository extends ElasticsearchRepository<EmailData, String> {

    List<EmailData> findAllByStatus(EmailStatus status);

}
