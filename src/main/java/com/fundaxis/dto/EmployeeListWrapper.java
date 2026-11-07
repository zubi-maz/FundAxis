package com.fundaxis.dto;

import com.fundaxis.entity.Employee;
import java.util.ArrayList;
import java.util.List;

public class EmployeeListWrapper {

    private List<Employee> employees = new ArrayList<>();

    // Constructor initializes the list with 1 empty row by default
    public EmployeeListWrapper() {
        employees.add(new Employee());
    }

    public List<Employee> getEmployees() {
        return employees;
    }

    public void setEmployees(List<Employee> employees) {
        this.employees = employees;
    }
}