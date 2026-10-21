package com.fundaxis.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "employees",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "employee_id"),
                @UniqueConstraint(columnNames = "email")
        }
)
public class Employee {

    // Database primary key
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    ///--------------------------------------------------------------------------------------
    // official NBL employee identifier, that issued by the National Bank/HR, entered manually by admin
    @NotBlank(message = "Employee ID is required")
    @Column(name = "employee_id", nullable = false, length = 50, unique = true)
    private String employeeId;
    ///---------------------------------------------------------------------------------------

    @NotBlank(message = "Full name is required")
    @Column(nullable = false, length = 100)
    private String fullName;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    @Column(nullable = false, length = 100)
    private String email;

    @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "Phone number must be valid")
    @Column(length = 20)
    private String phoneNumber;

    ///-----------------------------------------------------------
    @NotBlank(message = "Designation name is required")
    @Column(nullable = false, length = 100)
    private String designation;

    public enum Designation {
        FEO,
        AVP,
        EO,
        JO,
        SAVP,
        SPO,
        PO
    }
    ///-----------------------------------------------------------




    ///-----------------------------------------------------------
    public enum EmployeeStatus{
        ACTIVE,
        INACTIVE,
        SUSPENDED,
        PENDING
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private EmployeeStatus employeeStatus = EmployeeStatus.ACTIVE;
    ///-----------------------------------------------------------


    // ---------- Fund Membership Information ----------

    // Date the employee officially joined the fund
    @Column(name = "fund_join_date")
    @JsonFormat(pattern = "dd/MM/yyyy")
    private LocalDate fundJoinDate;

    // Date the employee officially exited the fund
    // Null means no fund exit date has been recorded
    @Column(name = "fund_exit_date")
    @JsonFormat(pattern = "dd/MM/yyyy")
    private LocalDate fundExitDate;

    ///---------- Local Time Setup / Audit Information ------------

    @CreationTimestamp
    @Column(updatable = false)
    @JsonFormat(pattern = "dd/MM/yyyy HH:mm:ss")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @JsonFormat(pattern = "dd/MM/yyyy HH:mm:ss")
    private LocalDateTime updatedAt;



    ///---------- Account Deactivations / Deactivation Information ----------

    @Column(length = 255)
    private String deactivationReason;

    @JsonFormat(pattern = "dd/MM/yyyy HH:mm:ss")
    private LocalDateTime deactivatedAt;


}