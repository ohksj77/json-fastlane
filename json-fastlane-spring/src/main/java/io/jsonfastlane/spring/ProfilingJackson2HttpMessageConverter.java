package io.jsonfastlane.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonfastlane.FastJsonBufferWriter;
import io.jsonfastlane.Utf8JsonBuffer;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public final class ProfilingJackson2HttpMessageConverter extends MappingJackson2HttpMessageConverter {
    private final JsonConversionProfiler profiler;
    private final FastJsonWriterRegistry writerRegistry;
    private final JsonBufferFactory bufferFactory;
    private final EndpointResolver endpointResolver;
    private volatile Class<?> cachedWriterType;
    private volatile FastJsonBufferWriter<?> cachedWriter;
    private volatile Class<?> cachedMissingWriterType;

    public ProfilingJackson2HttpMessageConverter(JsonConversionProfiler profiler) {
        this(profiler, new FastJsonWriterRegistry());
    }

    public ProfilingJackson2HttpMessageConverter(
        JsonConversionProfiler profiler,
        FastJsonWriterRegistry writerRegistry
    ) {
        this(profiler, writerRegistry, JsonBufferFactory.defaultFactory());
    }

    public ProfilingJackson2HttpMessageConverter(
        JsonConversionProfiler profiler,
        FastJsonWriterRegistry writerRegistry,
        JsonBufferFactory bufferFactory
    ) {
        this(profiler, writerRegistry, bufferFactory, EndpointResolver.unknown());
    }

    public ProfilingJackson2HttpMessageConverter(
        JsonConversionProfiler profiler,
        FastJsonWriterRegistry writerRegistry,
        JsonBufferFactory bufferFactory,
        EndpointResolver endpointResolver
    ) {
        this.profiler = profiler;
        this.writerRegistry = writerRegistry;
        this.bufferFactory = Objects.requireNonNull(bufferFactory, "bufferFactory");
        this.endpointResolver = Objects.requireNonNull(endpointResolver, "endpointResolver");
    }

    public ProfilingJackson2HttpMessageConverter(ObjectMapper objectMapper, JsonConversionProfiler profiler) {
        this(objectMapper, profiler, new FastJsonWriterRegistry());
    }

    public ProfilingJackson2HttpMessageConverter(
        ObjectMapper objectMapper,
        JsonConversionProfiler profiler,
        FastJsonWriterRegistry writerRegistry
    ) {
        this(objectMapper, profiler, writerRegistry, JsonBufferFactory.defaultFactory());
    }

    public ProfilingJackson2HttpMessageConverter(
        ObjectMapper objectMapper,
        JsonConversionProfiler profiler,
        FastJsonWriterRegistry writerRegistry,
        JsonBufferFactory bufferFactory
    ) {
        this(objectMapper, profiler, writerRegistry, bufferFactory, EndpointResolver.unknown());
    }

    public ProfilingJackson2HttpMessageConverter(
        ObjectMapper objectMapper,
        JsonConversionProfiler profiler,
        FastJsonWriterRegistry writerRegistry,
        JsonBufferFactory bufferFactory,
        EndpointResolver endpointResolver
    ) {
        super(objectMapper);
        this.profiler = profiler;
        this.writerRegistry = writerRegistry;
        this.bufferFactory = Objects.requireNonNull(bufferFactory, "bufferFactory");
        this.endpointResolver = Objects.requireNonNull(endpointResolver, "endpointResolver");
    }

    @Override
    protected Object readInternal(Class<?> clazz, HttpInputMessage inputMessage)
        throws IOException, HttpMessageNotReadableException {
        byte[] body = StreamUtils.copyToByteArray(inputMessage.getBody());
        BufferedHttpInputMessage buffered = new BufferedHttpInputMessage(body, inputMessage.getHeaders());

        long start = System.nanoTime();
        Object value = super.readInternal(clazz, buffered);
        long elapsed = System.nanoTime() - start;

        profiler.record(new ConversionRoute(
            endpointResolver.resolveRead(inputMessage),
            ConversionDirection.READ,
            clazz.getName()
        ), body, elapsed);

        return value;
    }

    @Override
    protected void writeInternal(Object object, HttpOutputMessage outputMessage)
        throws IOException, HttpMessageNotWritableException {
        if (tryWriteFast(object, outputMessage)) {
            return;
        }

        BufferedHttpOutputMessage buffered = new BufferedHttpOutputMessage();
        long start = System.nanoTime();
        super.writeInternal(object, buffered);
        long elapsed = System.nanoTime() - start;

        byte[] body = buffered.bodyBytes();
        outputMessage.getHeaders().putAll(buffered.getHeaders());
        outputMessage.getBody().write(body);

        profiler.record(new ConversionRoute(
            endpointResolver.resolveWrite(outputMessage),
            ConversionDirection.WRITE,
            object == null ? "null" : object.getClass().getName()
        ), body, elapsed);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean tryWriteFast(Object object, HttpOutputMessage outputMessage) throws IOException {
        if (object == null) {
            return false;
        }

        Class<?> objectType = object.getClass();
        if (objectType == cachedMissingWriterType) {
            return false;
        }

        FastJsonBufferWriter writer = cachedWriterFor(objectType);
        if (writer == null) {
            cachedMissingWriterType = objectType;
            return false;
        }

        Utf8JsonBuffer out = bufferFactory.create();
        long start = System.nanoTime();
        writer.write(object, out);
        long elapsed = System.nanoTime() - start;

        if (outputMessage.getHeaders().getContentType() == null) {
            outputMessage.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        }
        outputMessage.getHeaders().setContentLength(out.size());
        out.writeTo(outputMessage.getBody());

        profiler.recordConversion(new ConversionRoute(
            endpointResolver.resolveWrite(outputMessage),
            ConversionDirection.WRITE,
            objectType.getName()
        ), out.size(), elapsed);
        return true;
    }

    private FastJsonBufferWriter<?> cachedWriterFor(Class<?> type) {
        if (type == cachedWriterType) {
            return cachedWriter;
        }

        FastJsonBufferWriter<?> writer = writerRegistry.find(type);
        if (writer != null) {
            cachedWriterType = type;
            cachedWriter = writer;
            if (type == cachedMissingWriterType) {
                cachedMissingWriterType = null;
            }
        }
        return writer;
    }

    @Override
    public List<MediaType> getSupportedMediaTypes(Class<?> clazz) {
        return super.getSupportedMediaTypes(clazz);
    }

    public JsonConversionProfiler profiler() {
        return profiler;
    }

    public FastJsonWriterRegistry writerRegistry() {
        return writerRegistry;
    }
}
