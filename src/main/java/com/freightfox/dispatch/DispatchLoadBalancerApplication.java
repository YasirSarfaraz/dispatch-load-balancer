package com.freightfox.dispatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Dispatch Load Balancer: assigns delivery orders to a vehicle fleet (a Capacitated Vehicle
 * Routing Problem) while respecting capacity, priority and minimising Haversine travel distance.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DispatchLoadBalancerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DispatchLoadBalancerApplication.class, args);
    }
}
