package com.cocos.challenge.portfolio;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PortfolioController {

    private final PortfolioService portfolios;

    public PortfolioController(PortfolioService portfolios) {
        this.portfolios = portfolios;
    }

    @GetMapping("/api/v1/users/{userId}/portfolio")
    public ResponseEntity<PortfolioResponse> getPortfolio(@PathVariable("userId") Integer userId) {
        return ResponseEntity.of(portfolios.findPortfolio(userId));
    }
}
