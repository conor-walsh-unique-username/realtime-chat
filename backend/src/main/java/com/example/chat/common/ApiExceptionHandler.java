package com.example.chat.common;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.example.chat.auth.UsernameTakenException;
import com.example.chat.room.RoomMembershipRequiredException;
import com.example.chat.room.RoomNameConflictException;
import com.example.chat.room.RoomNotFoundException;

// Shared controller error handling keeps the same JSON shape across the API.
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(UsernameTakenException.class)
    public ProblemDetail handleUsernameTaken(UsernameTakenException exception) {
        return problem(HttpStatus.CONFLICT, "USERNAME_TAKEN", exception.getMessage());
    }

    @ExceptionHandler(RoomNameConflictException.class)
    public ProblemDetail handleRoomNameConflict(RoomNameConflictException exception) {
        return problem(HttpStatus.CONFLICT, "ROOM_NAME_TAKEN", exception.getMessage());
    }

    @ExceptionHandler(RoomMembershipRequiredException.class)
    public ProblemDetail handleRoomMembershipRequired(RoomMembershipRequiredException exception) {
        return problem(HttpStatus.FORBIDDEN, "ROOM_MEMBERSHIP_REQUIRED", exception.getMessage());
    }

    @ExceptionHandler(RoomNotFoundException.class)
    public ProblemDetail handleRoomNotFound(RoomNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", exception.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Check the request fields.");
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (var error : exception.getBindingResult().getFieldErrors()) {
            // A field can fail several checks; one message per field is enough for the form.
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        problem.setProperty("fieldErrors", fieldErrors);
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        // The UI can branch on a stable code without depending on the human-readable wording.
        problem.setProperty("code", code);
        return problem;
    }
}
