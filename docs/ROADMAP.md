# Roadmap

[한국어](ROADMAP.ko.md)

The roadmap is split by what is already executable, what is experimental, and
what is needed before the project can feel like a broad JSON stack replacement.

## Done

| Area | State |
| --- | --- |
| Module split | Core, Spring, Netty, processor, and benchmark modules are separated. |
| Core profiler | Endpoint payload size, root kind, field order, field value kind, and drop counters are tracked. |
| Bounded memory | Endpoint count, retained field orders, field scan width, and nesting depth are option-bounded. |
| Byte scanner | Shape profiling scans UTF-8 bytes directly instead of first creating full JSON strings. |
| Packed field order | Short field-order signatures use packed `long` keys, with array fallback for larger shapes. |
| Expected shapes | Users can register shapes from JSON samples or `ExpectedJsonShape` objects. |
| Exact shape matcher | `CompiledJsonShapeMatcher` routes stable payloads to hot paths. |
| Shape fingerprint | `JsonShapeHashMatcher` supports full key/depth/value-kind hashes and exact verification. |
| Checkpoints | `JsonShapeFingerprintPlan` stores geometric checkpoints for early mismatch rejection. |
| Writer generation | Java record writers are generated through `@JsonFastlaneGenerateWriter`. |
| Generated writer metadata | Generated writers expose `ExpectedJsonShape` for profiler priming. |
| Spring MVC prototype | Profiling converter, direct generated writer converter, endpoint resolver, and writer registry exist. |
| Netty prototype | `ByteBuf` writer registry, routing helper, buffer, and sink exist. |
| Transport scaffold | `JsonSink`, `JsonSegment`, `OptionalMask`, UTF-8 sink, Netty sink, and `OutputStream` sink are executable. |
| Spring transport path | `FastJsonTransportHttpMessageConverter` streams transport writers without an intermediate JSON buffer. |
| Visibility | Text report, dependency-free metrics sink, JFR event hook, smoke checks, load simulation, and JMH scaffold exist. |
| Virtual-thread fit | Profiler and Spring endpoint resolution avoid monitor locks and thread-local endpoint scope. |

## Experimental

| Area | Current shape | Main risk |
| --- | --- | --- |
| Generated readers | Contracts and hand-written/prototype paths exist. | Processor generation and broad DTO coverage are not complete. |
| Transport lane | Sink-based writes work for generated/manual paths and are compared in load/JMH runs. | It is not yet a full scatter/gather network runtime. |
| ObjectMapper replacement | Selected generated DTO paths can bypass Jackson. | Full annotation/module/polymorphism behavior still belongs to Jackson. |
| WebFlux | Netty writer pieces exist. | Dedicated codec and backpressure-aware integration are future work. |
| Homogeneous arrays | Strict fingerprints include repeated keys and array length. | A skeleton mode is needed for variable-length list shapes. |

## Next Deep Work

1. Generate `TryFastJsonReader` implementations from retained shapes.
2. Add processor coverage for more DTO features: boxed values, enums, nested records,
   nullable fields, collections, and explicit unsupported-feature diagnostics.
3. Add homogeneous-array skeleton fingerprints so variable-length lists can share
   one shape when element structure is stable.
4. Extend transport benchmarks to large arrays and mixed optional-field shapes.
5. Add a WebFlux codec on top of the Netty writer registry.
6. Design an optional facade that looks closer to `ObjectMapper` for generated
   DTOs while still falling back for unsupported types.
7. Add a compatibility matrix for Jackson features: naming strategy, null policy,
   enum policy, date/time, custom serializers, polymorphism, and unknown fields.

## Decision Rule

The project should only move a DTO into a generated path when all three are true:

1. production traffic shows a stable shape
2. benchmarks show a real win for that payload
3. fallback behavior is boring and observable

If any point is false, the correct answer is to keep using the existing JSON
stack and let `json-fastlane` only observe or report.
