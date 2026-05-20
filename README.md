# Stellnula Service

Stellnula Service is the client-facing server for the Stellnula configuration center.

Stellnula's Chinese name is 星云, and its English full name is Nebula. The service is responsible for direct interaction with configuration clients, including configuration lookup, delivery, synchronization, and runtime change propagation.

## Responsibilities

- Serve configuration clients through stable Java service APIs.
- Provide configuration publishing, lookup, and synchronization capabilities.
- Support environment-aware and namespace-aware configuration isolation.
- Track configuration versions, revisions, and change events.
- Provide health, metrics, and operational endpoints for production deployment.

## Recommended Stack

- Java 21+
- Spring Boot
- OpenAPI
- Persistent storage for configuration metadata and revisions
- Observability with metrics, logs, and traces

## Repository Role

This repository contains the Java service implementation for the Stellnula configuration center server.
