package io.jsonfastlane.spring;

import io.jsonfastlane.FastJsonBufferWriter;
import io.jsonfastlane.Utf8JsonBuffer;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

import java.io.IOException;
import java.util.Objects;

public final class FastJsonHttpMessageConverter extends AbstractHttpMessageConverter<Object> {
    private final JsonConversionProfiler profiler;
    private final FastJsonWriterRegistry writerRegistry;
    private final JsonBufferFactory bufferFactory;
    private final EndpointResolver endpointResolver;
    private volatile Class<?> cachedWriterType;
    private volatile FastJsonBufferWriter<?> cachedWriter;

    public FastJsonHttpMessageConverter(
        JsonConversionProfiler profiler,
        FastJsonWriterRegistry writerRegistry
    ) {
        this(profiler, writerRegistry, JsonBufferFactory.defaultFactory());
    }

    public FastJsonHttpMessageConverter(
        JsonConversionProfiler profiler,
        FastJsonWriterRegistry writerRegistry,
        JsonBufferFactory bufferFactory
    ) {
        this(profiler, writerRegistry, bufferFactory, EndpointResolver.unknown());
    }

    public FastJsonHttpMessageConverter(
        JsonConversionProfiler profiler,
        FastJsonWriterRegistry writerRegistry,
        JsonBufferFactory bufferFactory,
        EndpointResolver endpointResolver
    ) {
        super(MediaType.APPLICATION_JSON);
        this.profiler = profiler;
        this.writerRegistry = writerRegistry;
        this.bufferFactory = Objects.requireNonNull(bufferFactory, "bufferFactory");
        this.endpointResolver = Objects.requireNonNull(endpointResolver, "endpointResolver");
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return writerFor(clazz) != null;
    }

    @Override
    protected Object readInternal(Class<?> clazz, HttpInputMessage inputMessage)
        throws IOException, HttpMessageNotReadableException {
        throw new HttpMessageNotReadableException("json-fastlane generated converter is write-only", inputMessage);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    protected void writeInternal(Object object, HttpOutputMessage outputMessage)
        throws IOException, HttpMessageNotWritableException {
        FastJsonBufferWriter writer = writerFor(object.getClass());
        if (writer == null) {
            throw new HttpMessageNotWritableException("No generated JSON writer registered for " + object.getClass());
        }

        Utf8JsonBuffer out = bufferFactory.create();
        long start = System.nanoTime();
        writer.write(object, out);
        long elapsed = System.nanoTime() - start;

        outputMessage.getHeaders().setContentLength(out.size());
        out.writeTo(outputMessage.getBody());

        profiler.recordConversion(new ConversionRoute(
            endpointResolver.resolveWrite(outputMessage),
            ConversionDirection.WRITE,
            object.getClass().getName()
        ), out.size(), elapsed);
    }

    private FastJsonBufferWriter<?> writerFor(Class<?> type) {
        if (type == cachedWriterType) {
            return cachedWriter;
        }

        FastJsonBufferWriter<?> writer = writerRegistry.find(type);
        if (writer != null) {
            cachedWriterType = type;
            cachedWriter = writer;
        }
        return writer;
    }
}
