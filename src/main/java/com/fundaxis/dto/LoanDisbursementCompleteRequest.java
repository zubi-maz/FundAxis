package com.fundaxis.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoanDisbursementCompleteRequest {

    // Deprecated compatibility input. The API ignores this value and takes the
    // processor identity from the authenticated server-side principal.
    private String processedBy;


    // Unique reference for the completed transaction
    @NotBlank(message = "Reference number is required")
    private String referenceNumber;

}
