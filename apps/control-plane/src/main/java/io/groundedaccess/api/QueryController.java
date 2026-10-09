package io.groundedaccess.api;

import io.groundedaccess.answering.AnsweringService;
import io.groundedaccess.identity.Principal;
import io.groundedaccess.retrieval.RetrievalService;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers a question with statements that cite authorized evidence, refuses when the evidence does not answer it, or returns the evidence
 * alone when no chat model is available.
 */
@RestController
@RequestMapping("/api/v1/query")
public class QueryController {

    private final AnsweringService answering;

    private final RetrievalService retrieval;

    public QueryController(AnsweringService answering, RetrievalService retrieval) {
        this.answering = answering;
        this.retrieval = retrieval;
    }

    @PostMapping
    public QueryResponse query(@AuthenticationPrincipal Jwt token, @Valid @RequestBody QueryRequest request) {
        var principal = Principal.from(token);
        return QueryResponse.from(answering.answer(principal, request.query(), request.strategy(), retrieval.scope(principal, request.asOf(), request.region())));
    }
}
