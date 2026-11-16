package com.bugtracker.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Lets notification emails be sent in the background so they never slow down or fail a request. */
@Configuration
@EnableAsync
public class AsyncConfig {
}
