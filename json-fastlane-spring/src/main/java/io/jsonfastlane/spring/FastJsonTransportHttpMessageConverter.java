package io.jsonfastlane.spring;

import io.jsonfastlane.FastJsonBufferWriter;
import io.jsonfastlane.transport.OutputStreamJsonSink;
import io.jsonfastlane.transport.TransportJsonWriter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

public final class FastJsonTransportHttpMessageConverter extends AbstractHttpMessageConverter<Object> {
    private final JsonConversionProfiler profiler;
    private final FastJsonWriterRegistry writerRegistry;
    private final EndpointResolver endpointResolver;
    private volatile Class<?> cachedWriterType;
    private volatile TransportJsonWriter<?> cachedWriter;

    public FastJsonTransportHttpMessageConverter(
        JsonConversionProfiler profiler,
        FastJsonWriterRegistry writerRegistry
    ) {
        this(profiler, writerRegistry, EndpointResolver.unknown());
    }

    public FastJsonTransportHttpMessageConverter(
        JsonConversionProfiler profiler,
        FastJsonWriterRegistry writerRegistry,
        EndpointResolver endpointResolver
    ) {
        super(MediaType.APPLICATION_JSON);
        this.profiler = profiler;
        this.writerRegistry = writerRegistry;
        this.endpointResolver = Objects.requireNonNull(endpointResolver, "endpointResolver");
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return transportWriterFor(clazz) != null;
    }

    @Override
    protected Object readInternal(Class<?> clazz, HttpInputMessage inputMessage)
        throws IOException, HttpMessageNotReadableException {
        throw new HttpMessageNotReadableException("json-fastlane transport converter is write-only", inputMessage);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    protected void writeInternal(Object object, HttpOutputMessage outputMessage)
        throws IOException, HttpMessageNotWritableException {
        TransportJsonWriter writer = transportWriterFor(object.getClass());
        if (writer == null) {
            throw new HttpMessageNotWritableException("No transport JSON writer registered for " + object.getClass());
        }

        if (outputMessage.getHeaders().getContentType() == null) {
            outputMessage.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        }

        OutputStreamJsonSink sink = new OutputStreamJsonSink(outputMessage.getBody());
        long start = System.nanoTime();
        try {
            writer.write(object, sink);
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
        long elapsed = System.nanoTime() - start;

        profiler.recordConversion(new ConversionRoute(
            endpointResolver.resolveWrite(outputMessage),
            ConversionDirection.WRITE,
            object.getClass().getName()
        ), sink.bytesWritten(), elapsed);
    }

    private TransportJsonWriter<?> transportWriterFor(Class<?> type) {
        if (type == cachedWriterType) {
            return cachedWriter;
        }

        FastJsonBufferWriter<?> writer = writerRegistry.find(type);
        if (writer instanceof TransportJsonWriter<?> transportWriter) {
            cachedWriterType = type;
            cachedWriter = transportWriter;
            return transportWriter;
        }
        return null;
    }
}
