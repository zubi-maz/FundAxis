package com.fundaxis.service;

import com.fundaxis.dto.BulkMonthlyGenerationResponse;
import com.fundaxis.dto.MonthlyContributionGenerationResponse;
import com.fundaxis.entity.Contribution;
import com.fundaxis.entity.Employee;
import com.fundaxis.entity.ShareHolding;
import com.fundaxis.exception.EmployeeNotFoundException;
import com.fundaxis.repository.EmployeeRepository;
import com.fundaxis.repository.MonthlyContributionGenerationRepository;
import com.fundaxis.repository.ShareHoldingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates a single monthly contribution (due) per employee.
 * This DOES NOT record a payment or mark a due as PAID.
 * Historical rate and quantity calculation delegates to the tested Phase 2 ShareService.
 */
@Service
public class MonthlyContributionGenerationService {
    private final EmployeeRepository employeeRepository;
    private final ShareHoldingRepository shareHoldingRepository;
    private final MonthlyContributionGenerationRepository contributionRepository;
    private final ShareService shareService;

    public MonthlyContributionGenerationService(EmployeeRepository employeeRepository,
                                                ShareHoldingRepository shareHoldingRepository,
                                                MonthlyContributionGenerationRepository contributionRepository,
                                                ShareService shareService) {
        this.employeeRepository = employeeRepository;
        this.shareHoldingRepository = shareHoldingRepository;
        this.contributionRepository = contributionRepository;
        this.shareService = shareService;
    }

    @Transactional
    public MonthlyContributionGenerationResponse generateOne(String employeeId, LocalDate month) {
        if (employeeId == null || employeeId.isBlank())
            throw new IllegalArgumentException("Employee ID is required");
        LocalDate normalizedMonth = normalize(month);
        Employee employee = employeeRepository.findByEmployeeId(employeeId.trim())
                .orElseThrow(() -> new EmployeeNotFoundException("Employee not found: " + employeeId));
        return generateForEmployee(employee, normalizedMonth);
    }

    /**
     * Atomic batch: either all new due rows commit or all roll back.
     * Existing rows (PAID/PENDING/CANCELED) are NEVER modified or regenerated.
     */
    @Transactional
    public BulkMonthlyGenerationResponse generateAll(LocalDate month) {
        LocalDate normalizedMonth = normalize(month);
        // Candidates are employees with at least one share holding. All holdings are fetched once.
        Map<Long, Employee> candidates = new LinkedHashMap<>();
        for (ShareHolding holding : shareHoldingRepository.findAllWithEmployee()) {
            Employee employee = holding.getEmployee();
            candidates.putIfAbsent(employee.getId(), employee);
        }
        List<Employee> ordered = candidates.values().stream()
                .sorted(Comparator.comparing(Employee::getEmployeeId))
                .toList();

        List<MonthlyContributionGenerationResponse> results = new ArrayList<>();
        int created = 0, existing = 0, noDues = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (Employee employee : ordered) {
            MonthlyContributionGenerationResponse result = generateForEmployee(employee, normalizedMonth);
            results.add(result);
            if (result.created()) {
                created++;
                total = total.add(result.expectedAmount());
            } else if ("ALREADY_EXISTS".equals(result.status())) {
                existing++;
            } else {
                noDues++;
            }
        }
        return new BulkMonthlyGenerationResponse(normalizedMonth, ordered.size(),
                created, existing, noDues, total, List.copyOf(results));
    }

    private MonthlyContributionGenerationResponse generateForEmployee(Employee employee, LocalDate month) {
        // Check before computing: old records must not be silently overwritten.
        var existing = contributionRepository.findByEmployeeIdAndContributionMonth(employee.getId(), month);
        if (existing.isPresent()) {
            Contribution c = existing.get();
            return new MonthlyContributionGenerationResponse(c.getId(), employee.getEmployeeId(), month,
                    c.getContributionAmount(), "ALREADY_EXISTS", false,
                    "Existing contribution preserved (" + c.getContributionStatus() + ")");
        }

        BigDecimal expected = shareService
                .calculateMonthlyContribution(employee.getEmployeeId(), month)
                .expectedAmount();
        if (expected == null || expected.signum() <= 0) {
            return new MonthlyContributionGenerationResponse(null, employee.getEmployeeId(), month,
                    BigDecimal.ZERO, "NO_DUES", false,
                    "No eligible share contribution for this month");
        }
        // Contributions use DECIMAL(20,2). Do not silently round calculated financial amounts.
        if (expected.scale() > 2 || expected.precision() - expected.scale() > 18)
            throw new IllegalArgumentException("Calculated contribution exceeds DECIMAL(20,2) for "
                    + employee.getEmployeeId());

        Contribution contribution = new Contribution();
        contribution.setEmployee(employee);
        contribution.setContributionMonth(month);
        contribution.setContributionAmount(expected);
        contribution.setContributionStatus(Contribution.ContributionStatus.PENDING);
        contribution.setPaymentDate(null);
        Contribution saved = contributionRepository.saveAndFlush(contribution);
        return new MonthlyContributionGenerationResponse(saved.getId(), employee.getEmployeeId(), month,
                saved.getContributionAmount(), "CREATED", true, "Pending contribution generated");
    }

    private LocalDate normalize(LocalDate month) {
        if (month == null) throw new IllegalArgumentException("Contribution month is required");
        return month.withDayOfMonth(1);
    }
}
