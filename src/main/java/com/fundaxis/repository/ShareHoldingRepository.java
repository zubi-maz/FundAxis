package com.fundaxis.repository;

import com.fundaxis.entity.ShareHolding;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

///========== ShareHolding Repository ==========///
// Data-access layer for ShareHolding.
// Extends JpaRepository for basic CRUD and adds
// custom lookup / lock methods used by the service layer.

@Repository
public interface ShareHoldingRepository extends JpaRepository<ShareHolding, Long> {

    ///========== Find by Official Employee ID ==========///

    // Returns every share holding linked to the given official Employee ID
    // (e.g. "EMP-001"). Used when showing an employee's portfolio.
    List<ShareHolding> findByEmployeeEmployeeId(String employeeId);

    ///========== Duplicate Check ==========///

    // True if the employee already has a holding for the given category.
    // Used before creating a new holding to enforce the unique constraint.
    boolean existsByEmployeeIdAndShareCategory(Long employeeId, ShareHolding.ShareCategory shareCategory);

    ///========== Pessimistic Lock ==========///

    // Loads the holding with a PESSIMISTIC_WRITE lock.
    // Prevents concurrent quantity adjustments from overwriting each other.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ShareHolding> findWithLockById(Long id);


    ///========== Overridden Queries ==========///

    // Overrides default findById to eagerly fetch the associated employee entity
    @Override
    @EntityGraph(attributePaths = "employee")
    Optional<ShareHolding> findById(Long id);
    

    @EntityGraph(attributePaths = "employee")
    @org.springframework.data.jpa.repository.Query("select h from ShareHolding h")
    List<ShareHolding> findAllWithEmployee();






    Optional<ShareHolding> findByEmployeeEmployeeIdAndShareCategory(String employeeId, ShareHolding.ShareCategory category);

    @org.springframework.data.jpa.repository.Query("select count(h),coalesce(sum(h.shareQuantity),0) from ShareHolding h")
    List<Object[]> holdingTotals();
}