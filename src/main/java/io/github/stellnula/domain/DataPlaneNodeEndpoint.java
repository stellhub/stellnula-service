package io.github.stellnula.domain;

public record DataPlaneNodeEndpoint(String serverId, String httpAddress, String grpcAddress) {}
