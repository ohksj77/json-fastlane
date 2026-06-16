# Design Notes

[한국어](DESIGN.ko.md)

`json-fastlane` is built around three loops.

## Module Boundaries

The project is split by optional runtime dependency:

- `json-fastlane-core` owns the contracts and fast-path primitives. It should
  stay independent from Spring, Jackson, and Netty.
- `json-fastlane-spring` adapts the core contracts to Spring MVC and keeps
  Jackson as fallback.
- `json-fastlane-netty` adapts the writer path to pooled `ByteBuf` output.
- `json-fastlane-processor` generates Java record writers without making the
  runtime modules depend on annotation-processing internals.
- `json-fastlane-benchmarks` owns executable experiments so benchmark-only
  dependencies do not leak into production artifacts.

## Current Boundary

`json-fastlane` replaces selected hot paths, not the whole JSON stack.

| Layer | Current implementation | What it replaces | What still falls back |
| --- | --- | --- | --- |
| Observation | `JsonFastlane`, `JsonConversionProfiler` | Ad hoc logging and manual payload inspection. | DTO binding remains in the app JSON stack. |
| Shape guard | `CompiledJsonShapeMatcher`, `JsonShapeHashMatcher` | Expensive full parsing before every routing decision. | Correctness-sensitive paths still verify exact shape or fallback. |
| Stable write | `JsonFastlaneGeneratedWriter<T>` | Reflection-heavy writes for selected record DTOs. | Unsupported DTOs, annotations, and uncommon shapes. |
| Buffer output | `FastJsonBufferWriter<T>`, `Utf8JsonBuffer` | Fresh `byte[]` output when the server can reuse a buffer. | Callers that require an owned array may still copy. |
| Sink output | `TransportJsonWriter<T>`, `JsonSink` | Hard-coding generated JSON to one output target. | Full scatter/gather network integration is future work. |
| Server adapters | Spring MVC and Netty prototypes | Manual glue around generated writers. | Existing Jackson/WebFlux/Reactor paths remain the compatibility layer. |

This boundary is intentional. Jackson already solves broad compatibility. The
project focuses on the narrower case where production traffic shows a stable,
expensive DTO shape that can justify generated code.

## Observe

The profiler records the shape of real JSON payloads. The initial prototype
keeps this intentionally small: endpoint, payload size, root kind, top-level
field order, and field value kinds.

This is enough to answer the first useful question:

> Are our payloads stable enough to deserve a generated fast path?

## Specialize

Once a DTO and endpoint are hot, generated code can assume the common shape
first. For example, a generated reader can compare expected UTF-8 field names
directly and a generated writer can emit static field names without reflection.

The generated path should optimize for the common payload, not for every JSON
document in the world.

The JVM-friendly form matters as much as the algorithm:

- Keep generated call sites monomorphic where possible.
- Use shape fingerprints as cheap guards, but do not treat a finite hash as a
  proof of equality when a correctness boundary is involved.
- Keep generated output independent from the final I/O target through `JsonSink`.
- Reuse buffers so serialization output does not escape as a fresh `byte[]`
  unless the caller explicitly asks for one.
- Avoid per-field metadata lookup, reflection, and temporary `String`
  allocation.
- Shape byte-array loops so HotSpot can inline and remove redundant bounds
  checks.

## Transport Lane

The transport package is the first executable form of the `static segment +
value island` idea:

```text
static JSON bytes -> JsonSegment
dynamic scalar values -> JsonSink.writeInt/writeString/writeBoolean/...
target output -> Utf8JsonSink, NettyJsonSink, OutputStreamJsonSink, or another sink
```

This design has two goals:

- keep generated JSON code independent from one final buffer type
- make future scatter/gather and pooled-buffer integrations possible without
  changing generated DTO logic
- let Spring write transport-capable generated DTOs without first materializing
  the whole response in `Utf8JsonBuffer`

It is not a claim that every byte is zero-copy today. Dynamic strings, number
formatting, TLS, compression, and kernel buffers can still introduce copies. The
useful guarantee is weaker and more honest: generated code can avoid committing
early to `byte[]` and can write into the target chosen by the integration.

The current implementation has three sink families:

| Sink | Target | Best fit |
| --- | --- | --- |
| `Utf8JsonSink` | `Utf8JsonBuffer` | Reusable in-memory response buffers. |
| `NettyJsonSink` | Netty `ByteBuf` | Pooled Netty output. |
| `OutputStreamJsonSink` | `OutputStream` | Spring MVC streaming without an intermediate JSON buffer. |

## Shape Hashing

A shape fingerprint is a low-level guard, not a schema. It hashes the order of
all object keys, key depth, value kind, and object/array boundaries while
ignoring scalar values. This makes it useful for quickly rejecting payloads that
cannot use a generated path.

Hash-only routing is acceptable only when the application is willing to accept
the small collision risk. For correctness-sensitive input, the preferred shape
is:

```text
JsonShapeHashMatcher.matchesHash(bytes) -> cheap reject
JsonShapeMatcher.matches(bytes) -> exact verification
generated codec -> fast path
fallback -> compatibility path
```

This keeps the common miss path cheap while avoiding correctness claims that a
hash cannot make.

`JsonShapeFingerprintPlan` adds geometric checkpoints after key events. The
matcher can reject early if the 1st, 2nd, 4th, or 8th key checkpoint already
differs. This is especially useful for large payloads whose top-level field
order diverges early.

The strict fingerprint is exact about key occurrences. Arrays with different
lengths produce different strict fingerprints because repeated object keys inside
the array are part of the event stream. `skeletonFingerprint` normalizes
homogeneous arrays by hashing the element shape without treating array length as
part of the shape. This is useful for list-heavy candidate discovery, while
strict fingerprints remain the safer default for exact routing boundaries.

## Fallback

The project should keep compatibility boring. If the observed shape does not
match, or if a feature is not implemented, the integration should fall back to
the application JSON stack.

The fast path is an optimization. Correctness belongs to the fallback until a
generated codec proves it can cover the case safely.

Fallback should not require exceptions for expected shape drift. Generated
readers should prefer this shape:

```text
TryFastJsonReader.tryRead(bytes) -> DTO or null
FallbackAwareJsonReader -> fallback when null
```

Exceptions remain useful for malformed JSON, but uncommon valid JSON should be
a normal branch.
