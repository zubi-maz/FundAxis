package com.fundaxis.reporting;

import com.fundaxis.service.ContributionReportingService;
import com.fundaxis.service.MemberCreditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, read-only check using the configured real SQL Server. No application seeders are loaded. */
@EnabledIfSystemProperty(named="fundaxis.sqlserver.smoke", matches="true")
@SpringBootTest(classes=SqlServerReportingSmokeTest.DatabaseOnly.class,
        webEnvironment=SpringBootTest.WebEnvironment.NONE, properties={
        "spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=never",
        "spring.datasource.hikari.connection-timeout=8000", "spring.jpa.open-in-view=false"})
@Transactional(readOnly=true)
class SqlServerReportingSmokeTest {
    @org.springframework.boot.test.context.TestComponent
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration
    @AutoConfigurationPackage(basePackages="com.fundaxis")
    @Import({ContributionReportingService.class,MemberCreditService.class})
    static class DatabaseOnly {}

    @Autowired ContributionReportingService service;

    @Test void actualDatabaseQueriesReconcileWithoutChangingData() {
        int year=LocalDate.now(java.time.ZoneId.of("Asia/Dhaka")).getYear();
        var report=service.annual(year,"","","ALL",0,10,"employeeId","asc");
        BigDecimal monthlyExpected=report.months().stream().map(ContributionReportingService.ReportMonth::expected)
                .reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal monthlyReceived=report.months().stream().map(ContributionReportingService.ReportMonth::collected)
                .reduce(BigDecimal.ZERO,BigDecimal::add);
        assertEquals(0,monthlyExpected.compareTo(report.expected()));
        assertEquals(0,monthlyReceived.compareTo(report.collected()));
        for(var row:report.page().getContent()) {
            var statement=service.statement(row.employeeId(),"ANNUAL",year,null,null);
            assertEquals(0,row.expected().compareTo(statement.summary().expected()));
            assertEquals(0,row.collected().compareTo(statement.summary().collected()));
            assertEquals(0,row.outstanding().compareTo(statement.summary().outstanding()));
        }
        // Check all allowlisted native sort aliases against the real SQL Server dialect.
        for(String sort:java.util.List.of("fullName","expected","collected","outstanding"))
            assertEquals(report.page().getTotalElements(),service.annual(year,"","","ALL",0,10,sort,"desc").page().getTotalElements());
    }
}
