package com.monticker.api.quant.infrastructure

import com.monticker.api.quant.domain.RuleSetDocument
import org.springframework.data.mongodb.repository.MongoRepository
import java.util.Optional

interface RuleSetRepository : MongoRepository<RuleSetDocument, String> {
    fun findAllByUserId(userId: Long): List<RuleSetDocument>
    fun findByIdAndUserId(id: String, userId: Long): Optional<RuleSetDocument>
}
