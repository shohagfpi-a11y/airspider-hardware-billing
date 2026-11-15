package com.hardwarebilling.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Concurrency-safe counters. A single UPDATE ... RETURNING takes the row lock, so two concurrent
 * callers can never receive the same number. Counters only move forward: numbers are never reused.
 */
@Service
public class NumberingService {
    private final JdbcClient jdbc;

    public NumberingService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long next(String name) {
        return jdbc.sql("update sequence_counter set next_value = next_value + 1 where name = :n returning next_value - 1")
                .param("n", name).query(Long.class).single();
    }

    @Transactional(readOnly = true)
    public long peek(String name) {
        return jdbc.sql("select next_value from sequence_counter where name = :n").param("n", name).query(Long.class).single();
    }

    /** Raises (never lowers) a counter, e.g. to continue numbering from a previous system. */
    @Transactional
    public void raiseTo(String name, long value) {
        long current = jdbc.sql("select next_value from sequence_counter where name = :n for update")
                .param("n", name).query(Long.class).single();
        if (value < current) {
            throw new BusinessException("The next number cannot be lower than " + current + " (numbers are never reused)");
        }
        jdbc.sql("update sequence_counter set next_value = :v where name = :n").param("v", value).param("n", name).update();
    }
}
