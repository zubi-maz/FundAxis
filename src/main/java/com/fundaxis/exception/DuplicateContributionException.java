package com.fundaxis.exception;

public class DuplicateContributionException extends RuntimeException{

    // Create a duplicate contribution error with a custom message
    public DuplicateContributionException(String message){
        super(message);

    }

    public DuplicateContributionException(String message, Throwable cause) {
        super(message, cause);
    }

}
