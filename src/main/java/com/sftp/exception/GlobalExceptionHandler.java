package com.sftp.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex,
                                              HttpServletRequest request,
                                              Model model) {
        logger.warn("File upload size exceeded: {}", ex.getMessage());
        return "redirect:/files?error=size";
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ModelAndView handleAccessDenied(AccessDeniedException ex,
                                           HttpServletRequest request,
                                           Principal principal) {
        logger.warn("Access denied for user '{}' to resource: {}",
                principal != null ? principal.getName() : "anonymous",
                request.getRequestURI());

        ModelAndView mav = new ModelAndView("error");
        mav.addObject("errorTitle", "Access Denied");
        mav.addObject("errorMessage", "You don't have permission to access this resource.");
        mav.addObject("statusCode", HttpStatus.FORBIDDEN.value());
        mav.addObject("timestamp", LocalDateTime.now());
        return mav;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ModelAndView handleNotFound(NoResourceFoundException ex,
                                       HttpServletRequest request) {
        logger.debug("Resource not found: {}", request.getRequestURI());

        ModelAndView mav = new ModelAndView("error");
        mav.addObject("errorTitle", "Page Not Found");
        mav.addObject("errorMessage", "The requested page could not be found.");
        mav.addObject("statusCode", HttpStatus.NOT_FOUND.value());
        mav.addObject("timestamp", LocalDateTime.now());
        return mav;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ModelAndView handleIllegalArgument(IllegalArgumentException ex,
                                              HttpServletRequest request) {
        logger.warn("Bad request to {}: {}", request.getRequestURI(), ex.getMessage());

        ModelAndView mav = new ModelAndView("error");
        mav.addObject("errorTitle", "Bad Request");
        mav.addObject("errorMessage", "The request could not be processed.");
        mav.addObject("statusCode", HttpStatus.BAD_REQUEST.value());
        mav.addObject("timestamp", LocalDateTime.now());
        return mav;
    }

    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ModelAndView handleSecurityException(SecurityException ex,
                                                HttpServletRequest request,
                                                Principal principal) {
        logger.warn("Security violation by user '{}' at {}: {}",
                principal != null ? principal.getName() : "anonymous",
                request.getRequestURI(),
                ex.getMessage());

        ModelAndView mav = new ModelAndView("error");
        mav.addObject("errorTitle", "Security Violation");
        mav.addObject("errorMessage", "Access to the requested resource is forbidden.");
        mav.addObject("statusCode", HttpStatus.FORBIDDEN.value());
        mav.addObject("timestamp", LocalDateTime.now());
        return mav;
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Object handleGenericException(Exception ex,
                                         HttpServletRequest request,
                                         Principal principal) {
        String requestId = java.util.UUID.randomUUID().toString().substring(0, 8);
        logger.error("Unhandled exception [requestId={}] for user '{}' at {}: {}",
                requestId,
                principal != null ? principal.getName() : "anonymous",
                request.getRequestURI(),
                ex.getMessage(),
                ex);

        // Check if this is an API request (expecting JSON response)
        String acceptHeader = request.getHeader("Accept");
        if (acceptHeader != null && acceptHeader.contains("application/json")) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Internal Server Error");
            errorResponse.put("message", "An unexpected error occurred. Please try again later.");
            errorResponse.put("requestId", requestId);
            errorResponse.put("timestamp", LocalDateTime.now().toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }

        // Return HTML error page
        ModelAndView mav = new ModelAndView("error");
        mav.addObject("errorTitle", "Internal Server Error");
        mav.addObject("errorMessage", "An unexpected error occurred. Please try again later.");
        mav.addObject("requestId", requestId);
        mav.addObject("statusCode", HttpStatus.INTERNAL_SERVER_ERROR.value());
        mav.addObject("timestamp", LocalDateTime.now());
        return mav;
    }
}
