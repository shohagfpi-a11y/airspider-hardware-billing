package com.hardwarebilling.repo;

import com.hardwarebilling.domain.Item;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ItemRepository extends JpaRepository<Item, Long> {
    Optional<Item> findByCodeIgnoreCase(String code);
    Optional<Item> findByBarcode(String barcode);

    /** Exact short code first, then code prefix, then partial name. Active items only (invoice autocomplete). */
    @Query("""
        select i from Item i
        where i.active = true
          and (lower(i.code) = lower(:q) or lower(i.code) = lower(:alt) or i.barcode = :q
               or lower(i.code) like lower(concat(:q, '%'))
               or lower(i.name) like lower(concat('%', :q, '%')))
        order by case when lower(i.code) = lower(:q) or lower(i.code) = lower(:alt) or i.barcode = :q then 0
                      when lower(i.code) like lower(concat(:q, '%')) then 1 else 2 end, i.name
        """)
    List<Item> searchActive(@Param("q") String q, @Param("alt") String alt, Pageable pageable);

    /** Catalog admin search: includes inactive items. */
    @Query("""
        select i from Item i
        where (:q = '' or lower(i.code) like lower(concat('%', :q, '%'))
               or lower(i.name) like lower(concat('%', :q, '%'))
               or i.barcode like concat('%', :q, '%'))
        order by i.code
        """)
    List<Item> searchAll(@Param("q") String q, Pageable pageable);
}
