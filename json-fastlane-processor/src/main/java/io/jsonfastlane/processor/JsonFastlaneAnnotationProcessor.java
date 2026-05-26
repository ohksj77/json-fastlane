package io.jsonfastlane.processor;

import io.jsonfastlane.JsonFastlaneGenerateWriter;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class JsonFastlaneAnnotationProcessor extends AbstractProcessor {
    private Types types;
    private Elements elements;
    private Messager messager;
    private Filer filer;

    @Override
    public void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        types = processingEnv.getTypeUtils();
        elements = processingEnv.getElementUtils();
        messager = processingEnv.getMessager();
        filer = processingEnv.getFiler();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(JsonFastlaneGenerateWriter.class.getCanonicalName());
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.RELEASE_17;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(JsonFastlaneGenerateWriter.class)) {
            if (element.getKind() != ElementKind.RECORD) {
                messager.printMessage(Diagnostic.Kind.ERROR, "@JsonFastlaneGenerateWriter only supports records", element);
                continue;
            }
            generateWriter((TypeElement) element);
        }
        return false;
    }

    private void generateWriter(TypeElement recordType) {
        JsonFastlaneGenerateWriter annotation = recordType.getAnnotation(JsonFastlaneGenerateWriter.class);
        String packageName = elements.getPackageOf(recordType).getQualifiedName().toString();
        String recordName = recordType.getQualifiedName().toString();
        String writerName = annotation.className().isBlank()
            ? recordType.getSimpleName() + "JsonFastlaneWriter"
            : annotation.className();
        String generatedName = packageName.isBlank() ? writerName : packageName + "." + writerName;

        WriterSource source = new WriterSource(packageName, writerName, recordName);
        source.addWriteMethod("writeObject", recordType);

        try {
            JavaFileObject file = filer.createSourceFile(generatedName, recordType);
            try (Writer writer = file.openWriter()) {
                writer.write(source.render());
            }
        } catch (IOException exception) {
            messager.printMessage(
                Diagnostic.Kind.ERROR,
                "Failed to generate json-fastlane writer: " + exception.getMessage(),
                recordType
            );
        }
    }

    private final class WriterSource {
        private final String packageName;
        private final String writerName;
        private final String recordName;
        private final Map<String, HelperMethod> helpers = new LinkedHashMap<>();
        private final Set<String> usedHelperNames = new LinkedHashSet<>();

        private WriterSource(String packageName, String writerName, String recordName) {
            this.packageName = packageName;
            this.writerName = writerName;
            this.recordName = recordName;
        }

        private void addWriteMethod(String methodName, TypeElement type) {
            helpers.put(type.getQualifiedName().toString(), new HelperMethod(methodName, type));
            usedHelperNames.add(methodName);
            for (RecordComponentElement component : type.getRecordComponents()) {
                registerNestedHelpers(component.asType());
            }
        }

        private String render() {
            StringBuilder code = new StringBuilder();
            if (!packageName.isBlank()) {
                code.append("package ").append(packageName).append(";\n\n");
            }
            code.append("public final class ")
                .append(writerName)
                .append(" implements io.jsonfastlane.JsonFastlaneGeneratedWriter<")
                .append(recordName)
                .append("> {\n");
            code.append("    @Override\n");
            code.append("    public void write(").append(recordName).append(" value, io.jsonfastlane.Utf8JsonBuffer out) {\n");
            code.append("        writeObject(value, out);\n");
            code.append("    }\n\n");
            code.append("    @Override\n");
            code.append("    public void write(").append(recordName).append(" value, io.jsonfastlane.transport.JsonSink sink) {\n");
            code.append("        writeObject(value, sink);\n");
            code.append("    }\n\n");
            renderExpectedShape(code, helpers.values().iterator().next().type());

            for (HelperMethod helper : helpers.values()) {
                List<? extends RecordComponentElement> components = helper.type().getRecordComponents();
                for (int i = 0; i < components.size(); i++) {
                    String prefix = (i == 0 ? "\"" : ",\"") + components.get(i).getSimpleName() + "\":";
                    code.append("    private static final io.jsonfastlane.transport.JsonSegment ")
                        .append(segmentConstant(helper, i))
                        .append(" = io.jsonfastlane.transport.JsonSegment.ascii(\"")
                        .append(escapeJava(prefix))
                        .append("\");\n");
                }
            }
            code.append('\n');

            for (HelperMethod helper : helpers.values()) {
                renderHelper(code, helper);
                renderSinkHelper(code, helper);
            }

            code.append("}\n");
            return code.toString();
        }

        private void renderExpectedShape(StringBuilder code, TypeElement type) {
            code.append("    @Override\n");
            code.append("    public io.jsonfastlane.ExpectedJsonShape expectedShape() {\n");
            code.append("        return io.jsonfastlane.ExpectedJsonShape.object(\n");
            List<? extends RecordComponentElement> components = type.getRecordComponents();
            for (int i = 0; i < components.size(); i++) {
                RecordComponentElement component = components.get(i);
                code.append("            io.jsonfastlane.ExpectedJsonField.field(\"")
                    .append(escapeJava(component.getSimpleName().toString()))
                    .append("\", io.jsonfastlane.JsonValueKind.")
                    .append(valueKind(component.asType()))
                    .append(")");
                if (i + 1 < components.size()) {
                    code.append(',');
                }
                code.append('\n');
            }
            code.append("        );\n");
            code.append("    }\n\n");
        }

        private void renderSinkHelper(StringBuilder code, HelperMethod helper) {
            TypeElement type = helper.type();
            code.append("    private static void ")
                .append(helper.name())
                .append("(")
                .append(type.getQualifiedName())
                .append(" value, io.jsonfastlane.transport.JsonSink sink) {\n");
            code.append("        if (value == null) {\n");
            code.append("            sink.writeNull();\n");
            code.append("            return;\n");
            code.append("        }\n");
            code.append("        sink.writeByte('{');\n");

            List<? extends RecordComponentElement> components = type.getRecordComponents();
            for (int i = 0; i < components.size(); i++) {
                RecordComponentElement component = components.get(i);
                code.append("        sink.writeSegment(").append(segmentConstant(helper, i)).append(");\n");
                writeSinkValue(code, component.asType(), "value." + component.getSimpleName() + "()", "        ");
            }

            code.append("        sink.writeByte('}');\n");
            code.append("    }\n\n");
        }

        private void renderHelper(StringBuilder code, HelperMethod helper) {
            TypeElement type = helper.type();
            code.append("    private static void ")
                .append(helper.name())
                .append("(")
                .append(type.getQualifiedName())
                .append(" value, io.jsonfastlane.Utf8JsonBuffer out) {\n");
            code.append("        if (value == null) {\n");
            code.append("            out.writeNull();\n");
            code.append("            return;\n");
            code.append("        }\n");
            code.append("        out.writeByte('{');\n");

            List<? extends RecordComponentElement> components = type.getRecordComponents();
            for (int i = 0; i < components.size(); i++) {
                RecordComponentElement component = components.get(i);
                code.append("        out.writeSegment(").append(segmentConstant(helper, i)).append(");\n");
                writeValue(code, component.asType(), "value." + component.getSimpleName() + "()", "        ");
            }

            code.append("        out.writeByte('}');\n");
            code.append("    }\n\n");
        }

        private void registerNestedHelpers(TypeMirror type) {
            TypeMirror erased = types.erasure(type);
            if (isIterable(type)) {
                TypeMirror elementType = iterableElementType(type);
                if (elementType != null) {
                    registerNestedHelpers(elementType);
                }
                return;
            }
            Element element = types.asElement(erased);
            if (element instanceof TypeElement typeElement && typeElement.getKind() == ElementKind.RECORD) {
                String qualifiedName = typeElement.getQualifiedName().toString();
                if (helpers.containsKey(qualifiedName)) {
                    return;
                }
                addWriteMethod(helperName(typeElement), typeElement);
            }
        }

        private String helperName(TypeElement type) {
            String base = "write" + type.getSimpleName();
            if (usedHelperNames.add(base)) {
                return base;
            }
            int index = 2;
            while (!usedHelperNames.add(base + index)) {
                index++;
            }
            return base + index;
        }

        private String fieldConstant(HelperMethod helper, int index) {
            StringBuilder name = new StringBuilder();
            String helperName = helper.name();
            for (int i = 0; i < helperName.length(); i++) {
                char current = helperName.charAt(i);
                if (Character.isUpperCase(current) && i > 0) {
                    name.append('_');
                }
                if (Character.isLetterOrDigit(current)) {
                    name.append(Character.toUpperCase(current));
                } else {
                    name.append('_');
                }
            }
            return name.append("_SEGMENT_").append(index).toString();
        }

        private String segmentConstant(HelperMethod helper, int index) {
            return fieldConstant(helper, index);
        }

        private void writeValue(StringBuilder code, TypeMirror type, String expression, String indent) {
            TypeKind kind = type.getKind();
            switch (kind) {
                case BOOLEAN -> code.append(indent).append("out.writeBoolean(").append(expression).append(");\n");
                case BYTE, SHORT, INT -> code.append(indent).append("out.writeInt(").append(expression).append(");\n");
                case LONG -> code.append(indent).append("out.writeLong(").append(expression).append(");\n");
                default -> writeDeclaredValue(code, type, expression, indent);
            }
        }

        private void writeSinkValue(StringBuilder code, TypeMirror type, String expression, String indent) {
            TypeKind kind = type.getKind();
            switch (kind) {
                case BOOLEAN -> code.append(indent).append("sink.writeBoolean(").append(expression).append(");\n");
                case BYTE, SHORT, INT -> code.append(indent).append("sink.writeInt(").append(expression).append(");\n");
                case LONG -> code.append(indent).append("sink.writeLong(").append(expression).append(");\n");
                default -> writeSinkDeclaredValue(code, type, expression, indent);
            }
        }

        private String valueKind(TypeMirror type) {
            TypeKind kind = type.getKind();
            return switch (kind) {
                case BOOLEAN -> "BOOLEAN";
                case BYTE, SHORT, INT, LONG, FLOAT, DOUBLE -> "NUMBER";
                default -> declaredValueKind(type);
            };
        }

        private String declaredValueKind(TypeMirror type) {
            String typeName = types.erasure(type).toString();
            return switch (typeName) {
                case "java.lang.String" -> "STRING";
                case "java.lang.Boolean" -> "BOOLEAN";
                case "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
                    "java.lang.Float", "java.lang.Double" -> "NUMBER";
                default -> structuredValueKind(type);
            };
        }

        private String structuredValueKind(TypeMirror type) {
            if (isIterable(type)) {
                return "ARRAY";
            }
            if (isEnum(type)) {
                return "STRING";
            }

            Element element = types.asElement(types.erasure(type));
            if (element instanceof TypeElement typeElement && typeElement.getKind() == ElementKind.RECORD) {
                return "OBJECT";
            }
            fail("Unsupported JSON writer component type: " + type, null);
            return "UNKNOWN";
        }

        private void writeDeclaredValue(StringBuilder code, TypeMirror type, String expression, String indent) {
            String typeName = types.erasure(type).toString();
            switch (typeName) {
                case "java.lang.String" -> code.append(indent).append("out.writeString(").append(expression).append(");\n");
                case "java.lang.Boolean" -> writeNullableBoxed(code, expression, indent, "writeBoolean");
                case "java.lang.Byte", "java.lang.Short", "java.lang.Integer" -> writeNullableBoxed(code, expression, indent, "writeInt");
                case "java.lang.Long" -> writeNullableBoxed(code, expression, indent, "writeLong");
                default -> writeStructuredValue(code, type, expression, indent);
            }
        }

        private void writeSinkDeclaredValue(StringBuilder code, TypeMirror type, String expression, String indent) {
            String typeName = types.erasure(type).toString();
            switch (typeName) {
                case "java.lang.String" -> code.append(indent).append("sink.writeString(").append(expression).append(");\n");
                case "java.lang.Boolean" -> writeNullableBoxedSink(code, expression, indent, "writeBoolean");
                case "java.lang.Byte", "java.lang.Short", "java.lang.Integer" ->
                    writeNullableBoxedSink(code, expression, indent, "writeInt");
                case "java.lang.Long" -> writeNullableBoxedSink(code, expression, indent, "writeLong");
                default -> writeSinkStructuredValue(code, type, expression, indent);
            }
        }

        private void writeNullableBoxed(StringBuilder code, String expression, String indent, String method) {
            String local = nextLocal(expression);
            code.append(indent).append("var ").append(local).append(" = ").append(expression).append(";\n");
            code.append(indent).append("if (").append(local).append(" == null) {\n");
            code.append(indent).append("    out.writeNull();\n");
            code.append(indent).append("} else {\n");
            code.append(indent).append("    out.").append(method).append("(").append(local).append(");\n");
            code.append(indent).append("}\n");
        }

        private void writeNullableBoxedSink(StringBuilder code, String expression, String indent, String method) {
            String local = nextLocal(expression);
            code.append(indent).append("var ").append(local).append(" = ").append(expression).append(";\n");
            code.append(indent).append("if (").append(local).append(" == null) {\n");
            code.append(indent).append("    sink.writeNull();\n");
            code.append(indent).append("} else {\n");
            code.append(indent).append("    sink.").append(method).append("(").append(local).append(");\n");
            code.append(indent).append("}\n");
        }

        private void writeStructuredValue(StringBuilder code, TypeMirror type, String expression, String indent) {
            if (isIterable(type)) {
                writeIterable(code, type, expression, indent);
                return;
            }
            if (isEnum(type)) {
                String local = nextLocal(expression);
                code.append(indent).append("var ").append(local).append(" = ").append(expression).append(";\n");
                code.append(indent).append("out.writeString(").append(local).append(" == null ? null : ").append(local).append(".name());\n");
                return;
            }

            Element element = types.asElement(types.erasure(type));
            if (element instanceof TypeElement typeElement && typeElement.getKind() == ElementKind.RECORD) {
                HelperMethod helper = helpers.get(typeElement.getQualifiedName().toString());
                code.append(indent).append(helper.name()).append("(").append(expression).append(", out);\n");
                return;
            }

            fail("Unsupported JSON writer component type: " + type, null);
        }

        private void writeSinkStructuredValue(StringBuilder code, TypeMirror type, String expression, String indent) {
            if (isIterable(type)) {
                writeSinkIterable(code, type, expression, indent);
                return;
            }
            if (isEnum(type)) {
                String local = nextLocal(expression);
                code.append(indent).append("var ").append(local).append(" = ").append(expression).append(";\n");
                code.append(indent).append("sink.writeString(").append(local).append(" == null ? null : ")
                    .append(local).append(".name());\n");
                return;
            }

            Element element = types.asElement(types.erasure(type));
            if (element instanceof TypeElement typeElement && typeElement.getKind() == ElementKind.RECORD) {
                HelperMethod helper = helpers.get(typeElement.getQualifiedName().toString());
                code.append(indent).append(helper.name()).append("(").append(expression).append(", sink);\n");
                return;
            }

            fail("Unsupported JSON writer component type: " + type, null);
        }

        private void writeIterable(StringBuilder code, TypeMirror type, String expression, String indent) {
            TypeMirror elementType = iterableElementType(type);
            if (elementType == null) {
                fail("Iterable JSON writer components must declare an element type: " + type, null);
                return;
            }

            String local = nextLocal(expression);
            code.append(indent).append("var ").append(local).append(" = ").append(expression).append(";\n");
            code.append(indent).append("if (").append(local).append(" == null) {\n");
            code.append(indent).append("    out.writeNull();\n");
            code.append(indent).append("} else {\n");
            code.append(indent).append("    out.writeByte('[');\n");
            code.append(indent).append("    boolean first = true;\n");
            code.append(indent).append("    for (var item : ").append(local).append(") {\n");
            code.append(indent).append("        if (first) {\n");
            code.append(indent).append("            first = false;\n");
            code.append(indent).append("        } else {\n");
            code.append(indent).append("            out.writeByte(',');\n");
            code.append(indent).append("        }\n");
            writeValue(code, elementType, "item", indent + "        ");
            code.append(indent).append("    }\n");
            code.append(indent).append("    out.writeByte(']');\n");
            code.append(indent).append("}\n");
        }

        private void writeSinkIterable(StringBuilder code, TypeMirror type, String expression, String indent) {
            TypeMirror elementType = iterableElementType(type);
            if (elementType == null) {
                fail("Iterable JSON writer components must declare an element type: " + type, null);
                return;
            }

            String local = nextLocal(expression);
            code.append(indent).append("var ").append(local).append(" = ").append(expression).append(";\n");
            code.append(indent).append("if (").append(local).append(" == null) {\n");
            code.append(indent).append("    sink.writeNull();\n");
            code.append(indent).append("} else {\n");
            code.append(indent).append("    sink.writeByte('[');\n");
            code.append(indent).append("    boolean first = true;\n");
            code.append(indent).append("    for (var item : ").append(local).append(") {\n");
            code.append(indent).append("        if (first) {\n");
            code.append(indent).append("            first = false;\n");
            code.append(indent).append("        } else {\n");
            code.append(indent).append("            sink.writeByte(',');\n");
            code.append(indent).append("        }\n");
            writeSinkValue(code, elementType, "item", indent + "        ");
            code.append(indent).append("    }\n");
            code.append(indent).append("    sink.writeByte(']');\n");
            code.append(indent).append("}\n");
        }

        private boolean isIterable(TypeMirror type) {
            TypeElement iterable = elements.getTypeElement("java.lang.Iterable");
            return iterable != null && types.isAssignable(types.erasure(type), types.erasure(iterable.asType()));
        }

        private TypeMirror iterableElementType(TypeMirror type) {
            if (type instanceof DeclaredType declaredType && !declaredType.getTypeArguments().isEmpty()) {
                return declaredType.getTypeArguments().get(0);
            }
            return null;
        }

        private boolean isEnum(TypeMirror type) {
            Element element = types.asElement(types.erasure(type));
            return element instanceof TypeElement typeElement && typeElement.getKind() == ElementKind.ENUM;
        }

        private String nextLocal(String expression) {
            int hash = Math.abs(expression.hashCode());
            return "value" + Integer.toString(hash, 36).toLowerCase(Locale.ROOT);
        }

        private String escapeJava(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private void fail(String message, Element element) {
            messager.printMessage(Diagnostic.Kind.ERROR, message, element);
        }
    }

    private record HelperMethod(String name, TypeElement type) {
    }
}
