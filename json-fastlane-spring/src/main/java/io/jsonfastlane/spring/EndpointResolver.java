package io.jsonfastlane.spring;

import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;

import java.util.Objects;

public interface EndpointResolver {
    EndpointResolver UNKNOWN = new EndpointResolver() {
    };

    default String resolveRead(HttpInputMessage inputMessage) {
        return "unknown";
    }

    default String resolveWrite(HttpOutputMessage outputMessage) {
        return "unknown";
    }

    static EndpointResolver unknown() {
        return UNKNOWN;
    }

    static EndpointResolver fixed(String endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        return new EndpointResolver() {
            @Override
            public String resolveRead(HttpInputMessage inputMessage) {
                return endpoint;
            }

            @Override
            public String resolveWrite(HttpOutputMessage outputMessage) {
                return endpoint;
            }
        };
    }

    static EndpointResolver fromHeader(String headerName) {
        Objects.requireNonNull(headerName, "headerName");
        return new EndpointResolver() {
            @Override
            public String resolveRead(HttpInputMessage inputMessage) {
                return firstHeader(inputMessage.getHeaders().getFirst(headerName));
            }

            @Override
            public String resolveWrite(HttpOutputMessage outputMessage) {
                return firstHeader(outputMessage.getHeaders().getFirst(headerName));
            }
        };
    }

    private static String firstHeader(String endpoint) {
        return endpoint == null || endpoint.isBlank() ? "unknown" : endpoint;
    }
}
