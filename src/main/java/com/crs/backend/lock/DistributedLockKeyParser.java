package com.crs.backend.lock;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;

final class DistributedLockKeyParser {

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    private DistributedLockKeyParser() {
    }

    static String parse(ProceedingJoinPoint joinPoint, String keyPrefix, String keyExpression) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        StandardEvaluationContext context = new MethodBasedEvaluationContext(
                joinPoint.getTarget(),
                method,
                joinPoint.getArgs(),
                NAME_DISCOVERER
        );

        Object value = PARSER.parseExpression(keyExpression).getValue(context);
        if (value == null) {
            throw new IllegalArgumentException("Distributed lock key evaluated to null: " + keyExpression);
        }
        return keyPrefix + value;
    }
}
