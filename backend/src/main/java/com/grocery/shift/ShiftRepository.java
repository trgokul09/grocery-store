package com.grocery.shift;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ShiftRepository extends JpaRepository<Shift, Long> {
    Optional<Shift> findByUserIdAndStatus(Long userId, ShiftStatus status);
    List<Shift> findByStatus(ShiftStatus status);
    Page<Shift> findByUserId(Long userId, Pageable p);
}
