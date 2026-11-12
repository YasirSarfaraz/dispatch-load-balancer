package com.freightfox.dispatch.service;

import com.freightfox.dispatch.dto.response.DispatchPlanResponse;

/** Builds the dispatch plan on demand from the stored orders and vehicles. */
public interface DispatchService {

    /**
     * @param strategyName strategy name (case-insensitive); null or blank means the default
     * @throws com.freightfox.dispatch.exception.BadRequestException for an unknown strategy or
     *         when no vehicles are stored
     */
    DispatchPlanResponse generatePlan(String strategyName);
}
