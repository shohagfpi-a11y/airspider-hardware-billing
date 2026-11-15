package com.hardwarebilling.repo;

import com.hardwarebilling.domain.Customer;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    @Query("""
        select c from Customer c
        where (:q = '' or lower(c.name) like lower(concat('%', :q, '%'))
               or lower(c.code) like lower(concat('%', :q, '%'))
               or lower(coalesce(c.mobile,'')) like lower(concat('%', :q, '%'))
               or lower(coalesce(c.taxNumber,'')) like lower(concat('%', :q, '%')))
        order by c.name
        """)
    List<Customer> search(@Param("q") String q, Pageable pageable);

    List<Customer> findByActiveTrueOrderByName();
}
