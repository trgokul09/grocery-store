package com.grocery.hr;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface EmployeeProfileRepository extends JpaRepository<EmployeeProfile, Long> {
    Optional<EmployeeProfile> findByUserId(Long userId);
    Optional<EmployeeProfile> findByEmployeeCodeIgnoreCase(String employeeCode);
}
