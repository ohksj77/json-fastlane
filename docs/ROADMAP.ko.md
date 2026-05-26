# 로드맵

[English](ROADMAP.md)

로드맵은 이미 실행 가능한 것, 아직 실험 단계인 것, 넓은 JSON stack 대체재에 가까워지기
위해 필요한 것을 나눠서 봅니다.

## 완료

| 영역 | 상태 |
| --- | --- |
| Module split | core, Spring, Netty, processor, benchmark module이 분리되어 있습니다. |
| Core profiler | endpoint payload size, root kind, field order, field value kind, drop counter를 추적합니다. |
| Bounded memory | endpoint 수, retained field order, field scan width, nesting depth를 option으로 제한합니다. |
| Byte scanner | shape profiling이 전체 JSON string을 만들기 전에 UTF-8 byte를 직접 스캔합니다. |
| Packed field order | 짧은 field-order signature는 packed `long` key를 쓰고, 큰 shape는 array fallback을 씁니다. |
| Expected shapes | JSON sample 또는 `ExpectedJsonShape` object로 shape를 등록할 수 있습니다. |
| Exact shape matcher | `CompiledJsonShapeMatcher`가 stable payload를 hot path로 routing합니다. |
| Shape fingerprint | `JsonShapeHashMatcher`가 key/depth/value-kind hash와 exact verification을 지원합니다. |
| Checkpoints | `JsonShapeFingerprintPlan`이 early mismatch rejection용 geometric checkpoint를 저장합니다. |
| Writer generation | `@JsonFastlaneGenerateWriter`로 Java record writer를 생성합니다. |
| Generated writer metadata | generated writer가 profiler priming용 `ExpectedJsonShape`를 노출합니다. |
| Spring MVC prototype | profiling converter, direct generated writer converter, endpoint resolver, writer registry가 있습니다. |
| Netty prototype | `ByteBuf` writer registry, routing helper, buffer, sink가 있습니다. |
| Transport scaffold | `JsonSink`, `JsonSegment`, `OptionalMask`, UTF-8 sink, Netty sink, `OutputStream` sink가 실행됩니다. |
| Spring transport path | `FastJsonTransportHttpMessageConverter`가 중간 JSON buffer 없이 transport writer를 streaming합니다. |
| Visibility | text report, dependency-free metrics sink, JFR hook, smoke check, load simulation, JMH scaffold가 있습니다. |
| Virtual-thread fit | profiler와 Spring endpoint resolution은 monitor lock과 thread-local endpoint scope를 피합니다. |

## 실험 단계

| 영역 | 현재 형태 | 주요 리스크 |
| --- | --- | --- |
| Generated readers | contract와 hand-written/prototype path가 있습니다. | processor generation과 넓은 DTO coverage는 아직 부족합니다. |
| Transport lane | generated/manual path에서 sink-based write가 동작하고 load/JMH에서 비교됩니다. | 아직 full scatter/gather network runtime은 아닙니다. |
| ObjectMapper replacement | 선택된 generated DTO path는 Jackson을 우회할 수 있습니다. | annotation/module/polymorphism 전체 동작은 아직 Jackson 책임입니다. |
| WebFlux | Netty writer 조각이 있습니다. | 전용 codec과 backpressure-aware integration은 future work입니다. |
| Homogeneous arrays | strict fingerprint는 반복 key와 array length를 포함합니다. | variable-length list shape에는 skeleton mode가 필요합니다. |

## 다음 깊은 작업

1. retained shape에서 `TryFastJsonReader` 구현을 생성합니다.
2. boxed value, enum, nested record, nullable field, collection, 명확한 unsupported-feature
   diagnostics까지 processor coverage를 넓힙니다.
3. element 구조가 안정적인 variable-length list를 같은 shape로 볼 수 있도록
   homogeneous-array skeleton fingerprint를 추가합니다.
4. transport benchmark를 large array와 optional-field shape가 섞인 조건까지 확장합니다.
5. Netty writer registry 위에 WebFlux codec을 추가합니다.
6. generated DTO에는 `ObjectMapper`에 가까운 사용감을 주고 unsupported type은 fallback하는
   optional facade를 설계합니다.
7. naming strategy, null policy, enum policy, date/time, custom serializer,
   polymorphism, unknown field에 대한 Jackson feature compatibility matrix를 추가합니다.

## 결정 기준

DTO를 generated path로 옮기는 기준은 세 가지입니다.

1. production traffic에서 stable shape가 보여야 합니다.
2. benchmark에서 해당 payload의 실제 이득이 보여야 합니다.
3. fallback 동작이 단순하고 관찰 가능해야 합니다.

셋 중 하나라도 아니면 기존 JSON stack을 계속 쓰고, `json-fastlane`은 observe/report
역할만 맡는 것이 맞습니다.
