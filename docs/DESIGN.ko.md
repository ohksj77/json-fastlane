# 설계 노트

[English](DESIGN.md)

`json-fastlane`은 세 가지 loop를 중심으로 설계합니다.

## 모듈 경계

프로젝트는 optional runtime dependency 기준으로 나뉩니다.

- `json-fastlane-core`는 contract와 fast-path primitive를 소유합니다. Spring,
  Jackson, Netty와 독립적으로 유지해야 합니다.
- `json-fastlane-spring`은 core contract를 Spring MVC에 연결하고 Jackson fallback을
  유지합니다.
- `json-fastlane-netty`는 writer path를 pooled `ByteBuf` output에 연결합니다.
- `json-fastlane-processor`는 Java record writer를 생성하되 runtime module이
  annotation-processing 내부에 의존하지 않게 합니다.
- `json-fastlane-benchmarks`는 실행 가능한 실험을 소유해서 benchmark-only
  dependency가 production artifact로 새지 않게 합니다.

## 현재 경계

`json-fastlane`은 전체 JSON stack이 아니라 선택된 hot path를 대체합니다.

| Layer | 현재 구현 | 대체하는 것 | 계속 fallback하는 것 |
| --- | --- | --- | --- |
| Observation | `JsonFastlane`, `JsonConversionProfiler` | 임시 logging과 수동 payload 확인. | DTO binding은 애플리케이션 JSON stack에 남습니다. |
| Shape guard | `CompiledJsonShapeMatcher`, `JsonShapeHashMatcher` | routing 전에 매번 full parsing하는 비용. | correctness가 중요한 경로는 exact shape 검증 또는 fallback을 사용합니다. |
| Stable write | `JsonFastlaneGeneratedWriter<T>` | 선택된 record DTO의 reflection-heavy write. | unsupported DTO, annotation, uncommon shape. |
| Buffer output | `FastJsonBufferWriter<T>`, `Utf8JsonBuffer` | 서버가 buffer를 재사용할 수 있는데도 fresh `byte[]`를 만드는 경로. | caller가 owned array를 요구하면 copy가 남을 수 있습니다. |
| Sink output | `TransportJsonWriter<T>`, `JsonSink` | generated JSON을 하나의 output target에 고정하는 구조. | full scatter/gather network integration은 future work입니다. |
| Server adapter | Spring MVC, Netty prototype | generated writer 주변의 수동 glue code. | 기존 Jackson/WebFlux/Reactor path가 compatibility layer로 남습니다. |

이 경계는 의도적인 선택입니다. Jackson은 이미 넓은 호환성을 잘 해결합니다. 이
프로젝트는 production traffic에서 안정적이고 비싼 DTO shape가 보일 때, 그 좁은
영역을 generated code로 빠르게 만드는 데 집중합니다.

## Observe

profiler는 실제 JSON payload의 shape를 기록합니다. 초기 프로토타입은 의도적으로
작게 유지합니다. endpoint, payload size, root kind, top-level field order,
field value kind를 기록합니다.

이 정보만으로도 첫 번째로 중요한 질문에 답할 수 있습니다.

> 우리 payload가 generated fast path를 만들 만큼 안정적인가?

## Specialize

DTO와 endpoint가 hot path로 확인되면 generated code는 common shape를 먼저
가정할 수 있습니다. generated reader는 예상 UTF-8 field name을 직접 비교하고,
generated writer는 reflection 없이 static field name을 바로 출력할 수 있습니다.

generated path는 세상의 모든 JSON 문서가 아니라, 실제 서비스에서 자주 들어오는
payload에 최적화되어야 합니다.

JVM 친화적인 코드 형태도 알고리즘만큼 중요합니다.

- 가능하면 generated call site를 monomorphic하게 유지합니다.
- shape fingerprint는 cheap guard로 사용하되, correctness boundary에서는 유한한
  hash를 equality 증명으로 취급하지 않습니다.
- generated output은 `JsonSink`를 통해 최종 I/O target과 분리합니다.
- caller가 명시적으로 `byte[]`를 요구하지 않는 한 serialization output이 fresh
  `byte[]`로 escape하지 않도록 buffer를 재사용합니다.
- per-field metadata lookup, reflection, 임시 `String` allocation을 피합니다.
- HotSpot이 inline과 redundant bounds check 제거를 하기 쉽도록 byte-array loop를
  단순한 형태로 유지합니다.

## Transport Lane

transport package는 `static segment + value island` 아이디어의 첫 실행 가능한
형태입니다.

```text
static JSON bytes -> JsonSegment
dynamic scalar values -> JsonSink.writeInt/writeString/writeBoolean/...
target output -> Utf8JsonSink, NettyJsonSink, OutputStreamJsonSink, or another sink
```

이 설계의 목표는 두 가지입니다.

- generated JSON code가 하나의 최종 buffer type에 묶이지 않게 하기
- generated DTO logic을 바꾸지 않고 scatter/gather와 pooled-buffer integration으로
  확장할 수 있는 자리 만들기
- Spring이 transport-capable generated DTO를 전체 `Utf8JsonBuffer`로 먼저 만들지 않고
  쓸 수 있게 하기

현재 모든 byte가 zero-copy라고 주장하는 것은 아닙니다. dynamic string, number
formatting, TLS, compression, kernel buffer에서는 copy가 생길 수 있습니다. 지금의
보장은 더 작고 정직합니다. generated code가 너무 일찍 `byte[]`에 고정되지 않고,
integration이 고른 target으로 직접 쓸 수 있게 만드는 것입니다.

현재 구현된 sink 계열은 세 가지입니다.

| Sink | Target | 잘 맞는 사용처 |
| --- | --- | --- |
| `Utf8JsonSink` | `Utf8JsonBuffer` | 재사용 가능한 in-memory response buffer. |
| `NettyJsonSink` | Netty `ByteBuf` | pooled Netty output. |
| `OutputStreamJsonSink` | `OutputStream` | 중간 JSON buffer 없는 Spring MVC streaming. |

## Shape Hashing

shape fingerprint는 low-level guard이지 schema가 아닙니다. 모든 object key의 순서,
key depth, value kind, object/array boundary를 해싱하고 scalar 값은 무시합니다.
그래서 generated path를 탈 수 없는 payload를 아주 싸게 걸러내는 데 유용합니다.

hash-only routing은 작은 collision risk를 애플리케이션이 받아들일 수 있을 때만
사용하는 것이 좋습니다. correctness가 중요한 입력에서는 아래 형태가 기본입니다.

```text
JsonShapeHashMatcher.matchesHash(bytes) -> cheap reject
JsonShapeMatcher.matches(bytes) -> exact verification
generated codec -> fast path
fallback -> compatibility path
```

이렇게 하면 흔한 miss path는 싸게 유지하면서, hash가 할 수 없는 correctness 보장을
무리하게 주장하지 않을 수 있습니다.

`JsonShapeFingerprintPlan`은 key event 뒤에 기하급수 checkpoint를 추가합니다.
1번째, 2번째, 4번째, 8번째 key checkpoint부터 어긋나면 matcher가 일찍 탈락시킬 수
있습니다. top-level field order가 초반부터 다른 큰 payload에서 특히 유용합니다.

strict fingerprint는 key occurrence에 대해 exact합니다. array 길이가 다르면 array
안에서 반복되는 object key event도 달라지므로 strict fingerprint가 달라집니다.
`skeletonFingerprint`는 array 길이를 shape의 일부로 보지 않고 element shape를 hashing해서
homogeneous array를 정규화합니다. list가 많은 endpoint의 candidate discovery에 유용하고,
정확한 routing boundary에서는 strict fingerprint가 더 안전한 기본값으로 남습니다.

## Fallback

호환성은 지루할 만큼 안전해야 합니다. 관측된 shape와 맞지 않거나 아직 구현되지
않은 feature를 만나면 integration은 애플리케이션의 기존 JSON stack으로 fallback해야
합니다.

fast path는 최적화입니다. generated codec이 안전하게 처리할 수 있음을 증명하기
전까지 correctness는 fallback의 책임입니다.

예상 가능한 shape drift에 대해 fallback이 exception을 요구해서는 안 됩니다.
generated reader는 다음 형태를 우선해야 합니다.

```text
TryFastJsonReader.tryRead(bytes) -> DTO or null
FallbackAwareJsonReader -> null이면 fallback
```

malformed JSON에는 exception이 유용하지만, 유효하지만 드문 JSON shape는 정상 분기로
처리되어야 합니다.
