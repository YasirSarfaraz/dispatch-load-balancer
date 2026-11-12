package com.freightfox.dispatch.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static com.freightfox.dispatch.controller.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the handler paths that the dispatch endpoints cannot reach on their own (request
 * parameter validation as used by the test-data endpoint, type mismatches, conflicts, 500s) through
 * small probe controllers.
 */
@WebMvcTest(controllers = {GlobalExceptionHandlerTest.ProbeController.class,
        GlobalExceptionHandlerTest.ValidatedProbeController.class})
@Import({GlobalExceptionHandlerTest.ProbeController.class, GlobalExceptionHandlerTest.ValidatedProbeController.class})
class GlobalExceptionHandlerTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @Autowired
    private MockMvc mockMvc;

    record Payload(@NotBlank String name, @Min(1) int count) {
    }

    /** Built-in MVC method validation (Spring 6.1+) of constrained @RequestParam values. */
    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        @GetMapping("/range")
        String range(@RequestParam("orders") @Min(1) @Max(50000) int orders,
                     @RequestParam(name = "vehicles", defaultValue = "1") @Min(1) @Max(2000) int vehicles) {
            return "ok";
        }

        @GetMapping("/typed")
        String typed(@RequestParam("seed") long seed) {
            return "ok";
        }

        @GetMapping("/header")
        String header(@RequestHeader("X-Required") String required) {
            return "ok";
        }

        @GetMapping("/bad-request")
        String badRequest() {
            throw new BadRequestException("orders must be at most 50000", List.of("orders: must be at most 50000"));
        }

        @GetMapping("/violation")
        String violation() {
            throw new ConstraintViolationException(validator.validate(new Payload(" ", 0)));
        }

        @GetMapping("/conflict")
        String conflict() {
            throw new DataIntegrityViolationException("duplicate key value violates unique constraint");
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("database password is hunter2");
        }
    }

    /** Class-level @Validated: validation through the AOP proxy (ConstraintViolationException). */
    @RestController
    @Validated
    @RequestMapping("/validated")
    static class ValidatedProbeController {

        @GetMapping
        String generate(@RequestParam("orders") @Max(50000) int orders) {
            return "ok";
        }
    }

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    private MvcResult get(String url) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)).andReturn();
    }

    @Test
    void requestParameterConstraintsAreValidationErrors() throws Exception {
        List<String> errors = assertError(get("/probe/range?orders=0&vehicles=5000"), 400, "Validation failed");

        assertThat(errors).containsExactly(
                "orders: must be greater than or equal to 1",
                "vehicles: must be less than or equal to 2000");
    }

    @Test
    void validatedControllerConstraintsAreValidationErrors() throws Exception {
        List<String> errors = assertError(get("/validated?orders=60000"), 400, "Validation failed");

        assertThat(errors).containsExactly("orders: must be less than or equal to 50000");
    }

    @Test
    void constraintViolationExceptionsAreValidationErrors() throws Exception {
        List<String> errors = assertError(get("/probe/violation"), 400, "Validation failed");

        assertThat(errors).containsExactly("count: must be greater than or equal to 1", "name: must not be blank");
    }

    @Test
    void typeMismatchOnAParameterIsABadRequest() throws Exception {
        List<String> errors = assertError(get("/probe/typed?seed=abc"), 400, "Invalid value 'abc' for parameter 'seed'");

        assertThat(errors).containsExactly("seed: must be of type integer");
    }

    @Test
    void missingParameterIsABadRequest() throws Exception {
        List<String> errors = assertError(get("/probe/range"), 400, "Missing required parameter 'orders'");

        assertThat(errors).containsExactly("orders: is required");
    }

    @Test
    void otherSpringClientErrorsKeepTheirStatus() throws Exception {
        assertError(get("/probe/header"), 400, "Required header 'X-Required' is not present.");
    }

    @Test
    void badRequestExceptionsUseTheirMessageAndErrors() throws Exception {
        assertThat(assertError(get("/probe/bad-request"), 400, "orders must be at most 50000"))
                .containsExactly("orders: must be at most 50000");
    }

    @Test
    void dataIntegrityViolationIsAConflict() throws Exception {
        MvcResult result = get("/probe/conflict");

        assertThat(assertError(result, 409,
                "The request conflicts with data that was changed concurrently; please retry")).isEmpty();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("duplicate key");
    }

    @Test
    void unexpectedErrorsAreGeneric500s() throws Exception {
        MvcResult result = get("/probe/boom");

        assertThat(assertError(result, 500, "An unexpected error occurred")).isEmpty();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("hunter2");
    }

    @Test
    void capKeepsShortListsAndTruncatesLongOnes() {
        List<String> few = List.of("a", "b");
        List<String> many = IntStream.range(0, 250).mapToObj(i -> "e" + i).toList();

        assertThat(GlobalExceptionHandler.cap(few)).isSameAs(few);
        assertThat(GlobalExceptionHandler.cap(many)).hasSize(101).startsWith("e0").endsWith("... and 150 more errors");
    }

    static class Service {
        @SuppressWarnings("unused")
        void generate(@Min(1) int orders, List<@NotBlank String> names) {
        }
    }

    @Test
    void violationPathDropsTheMethodNameAndKeepsIndexes() throws Exception {
        Method method = Service.class.getDeclaredMethod("generate", int.class, List.class);
        Set<ConstraintViolation<Service>> violations = validator.forExecutables()
                .validateParameters(new Service(), method, new Object[]{0, List.of("ok", " ")});

        assertThat(violations).extracting(GlobalExceptionHandler::path)
                .containsExactlyInAnyOrder("orders", "names[1]");
    }
}
