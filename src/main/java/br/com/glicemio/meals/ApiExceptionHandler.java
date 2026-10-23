package br.com.glicemio.meals;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(DomainException.class)
    ProblemDetail domain(DomainException error) { return ProblemDetail.forStatusAndDetail(error.status(), error.getMessage()); }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            HandlerMethodValidationException.class, ConstraintViolationException.class})
    ProblemDetail invalid(Exception error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request fields or timestamps.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Resource state conflicts with this request.");
    }
}
