package com.fundaxis.repository;

import com.fundaxis.entity.Loan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

@Repository
public interface LoanRepository extends JpaRepository<Loan, Long> {

    /** Parent-row lock serializes disbursement cap checks for a loan. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Loan l where l.id = :id")
    Optional<Loan> findByIdForUpdate(@Param("id") Long id);

    // Find a loan using its unique Loan Application ID
    Optional<Loan> findByLoanApplicationId(String loanApplicationId);

    // Check whether a Loan Application ID already exists
    boolean existsByLoanApplicationId(String loanApplicationId);

    // Find all loans for an employee, newest application first
    List<Loan> findByEmployeeEmployeeIdOrderByApplicationDateDesc(String employeeId);

    // Find loans by current loan status, newest application first
    List<Loan> findByLoanStatusOrderByApplicationDateDesc(Loan.LoanStatus loanStatus);

    // Find loans by eligibility status, newest application first
    List<Loan> findByEligibilityStatusOrderByApplicationDateDesc(Loan.EligibilityStatus eligibilityStatus);

    // Find loans by loan type, newest application first
    List<Loan> findByLoanTypeOrderByApplicationDateDesc(Loan.LoanType loanType);

    // Find an employee's loans by current loan status
    List<Loan> findByEmployeeEmployeeIdAndLoanStatusOrderByApplicationDateDesc(String employeeId, Loan.LoanStatus loanStatus);

    // Find an employee's loans by eligibility status
    List<Loan> findByEmployeeEmployeeIdAndEligibilityStatusOrderByApplicationDateDesc(String employeeId, Loan.EligibilityStatus eligibilityStatus);


}
