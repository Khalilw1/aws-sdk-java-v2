/*
 * Copyright 2010-2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.codegen.poet.model;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import com.squareup.javapoet.WildcardTypeName;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.codegen.docs.DocumentationBuilder;
import software.amazon.awssdk.codegen.internal.Utils;
import software.amazon.awssdk.codegen.model.intermediate.IntermediateModel;
import software.amazon.awssdk.codegen.model.intermediate.MapModel;
import software.amazon.awssdk.codegen.model.intermediate.MemberModel;
import software.amazon.awssdk.codegen.model.intermediate.ShapeModel;
import software.amazon.awssdk.codegen.model.intermediate.ShapeType;
import software.amazon.awssdk.codegen.model.intermediate.VariableModel;
import software.amazon.awssdk.codegen.poet.ClassSpec;
import software.amazon.awssdk.codegen.poet.PoetExtensions;
import software.amazon.awssdk.codegen.poet.PoetUtils;
import software.amazon.awssdk.codegen.poet.eventstream.EventStreamUtils;
import software.amazon.awssdk.core.protocol.SdkField;
import software.amazon.awssdk.core.protocol.SdkPojo;
import software.amazon.awssdk.core.runtime.TypeConverter;
import software.amazon.awssdk.utils.builder.ToCopyableBuilder;

/**
 * Provides the Poet specs for AWS Service models.
 */
public class AwsServiceModel implements ClassSpec {

    private final IntermediateModel intermediateModel;
    private final ShapeModel shapeModel;
    private final PoetExtensions poetExtensions;
    private final TypeProvider typeProvider;
    private final ShapeModelSpec shapeModelSpec;
    private final ModelMethodOverrides modelMethodOverrides;
    private final ModelBuilderSpecs modelBuilderSpecs;

    public AwsServiceModel(IntermediateModel intermediateModel, ShapeModel shapeModel) {
        this.intermediateModel = intermediateModel;
        this.shapeModel = shapeModel;
        this.poetExtensions = new PoetExtensions(intermediateModel);
        this.typeProvider = new TypeProvider(intermediateModel);
        this.shapeModelSpec = new ShapeModelSpec(this.shapeModel,
                                                 typeProvider,
                                                 poetExtensions,
                                                 intermediateModel.getNamingStrategy(),
                                                 intermediateModel.getCustomizationConfig());
        this.modelMethodOverrides = new ModelMethodOverrides(this.poetExtensions);
        this.modelBuilderSpecs = new ModelBuilderSpecs(intermediateModel, this.shapeModel, this.typeProvider);
    }

    @Override
    public TypeSpec poetSpec() {
        if (shapeModel.isEventStream()) {
            EventStreamUtils eventStreamUtils = EventStreamUtils.createFromEventStreamShape(poetExtensions,
                                                                                            intermediateModel,
                                                                                            shapeModel);

            ClassName modelClass = poetExtensions.getModelClass(shapeModel.getShapeName());
            ClassName responseHandlerClass = eventStreamUtils.responseHandlerType();
            return PoetUtils.createInterfaceBuilder(modelClass)
                            .addAnnotation(SdkPublicApi.class)
                            .addSuperinterface(ClassName.get(SdkPojo.class))
                            .addJavadoc("Base interface for all event types of the $L API.", eventStreamUtils.getApiName())
                            .addField(FieldSpec.builder(modelClass, "UNKNOWN")
                                               .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                                               .initializer(CodeBlock.builder()
                                                                     .add("new $T() {\n"
                                                                          + "        @Override\n"
                                                                          + "        public $T<$T<?>> sdkFields() {\n"
                                                                          + "            return $T.emptyList();\n"
                                                                          + "        }\n"
                                                                          + "        @Override\n"
                                                                          + "        public void accept($T.Visitor visitor) {\n"
                                                                          + "            visitor.visitDefault(this);\n"
                                                                          + "        }\n"
                                                                          + "    };\n",
                                                                          modelClass, List.class, SdkField.class,
                                                                          Collections.class, responseHandlerClass
                                                                     )
                                                                     .build())
                                               .addJavadoc("Special type of {@link $T} for unknown types of events that this "
                                                           + "version of the SDK does not know about", modelClass)
                                               .build())
                            .addMethod(acceptMethodSpec(modelClass, responseHandlerClass)
                                           .addModifiers(Modifier.ABSTRACT)
                                           .build())
                            .build();
        } else {
            List<FieldSpec> fields = shapeModelSpec.fields();
            TypeSpec.Builder specBuilder = TypeSpec.classBuilder(this.shapeModel.getShapeName())
                                                   .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                                                   .addAnnotation(PoetUtils.generatedAnnotation())
                                                   .addSuperinterfaces(modelSuperInterfaces())
                                                   .superclass(modelSuperClass())
                                                   .addMethods(modelClassMethods())
                                                   .addFields(fields)
                                                   .addFields(shapeModelSpec.staticFields())
                                                   .addMethod(sdkFieldsMethod())
                                                   .addTypes(nestedModelClassTypes());

            if (!fields.isEmpty()) {
                specBuilder
                    .addMethod(getterCreator())
                    .addMethod(setterCreator());
            }

            if (this.shapeModel.isEvent()) {
                EventStreamUtils eventStreamUtils = EventStreamUtils.createFromEventShape(poetExtensions,
                                                                                          intermediateModel,
                                                                                          shapeModel);
                ClassName modelClass = poetExtensions.getModelClass(shapeModel.getShapeName());
                ClassName responseHandlerClass = eventStreamUtils.responseHandlerType();
                specBuilder.addSuperinterface(eventStreamUtils.eventStreamBaseClass());
                specBuilder.addMethod(acceptMethodSpec(modelClass, responseHandlerClass)
                                          .addAnnotation(Override.class)
                                          .addCode(CodeBlock.builder()
                                                            .addStatement("visitor.visit(this)")
                                                            .build())
                                          .build());
            }

            if (this.shapeModel.getDocumentation() != null) {
                specBuilder.addJavadoc("$L", this.shapeModel.getDocumentation());
            }

            return specBuilder.build();
        }
    }

    private MethodSpec sdkFieldsMethod() {
        ParameterizedTypeName sdkFieldType = ParameterizedTypeName.get(ClassName.get(SdkField.class),
                                                                       WildcardTypeName.subtypeOf(ClassName.get(Object.class)));
        return MethodSpec.methodBuilder("sdkFields")
                         .addModifiers(Modifier.PUBLIC)
                         .addAnnotation(Override.class)
                         .returns(ParameterizedTypeName.get(ClassName.get(List.class), sdkFieldType))
                         .addCode("return SDK_FIELDS;")
                         .build();
    }

    private MethodSpec getterCreator() {
        TypeVariableName t = TypeVariableName.get("T");
        return MethodSpec.methodBuilder("getter")
                         .addTypeVariable(t)
                         .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                         .addParameter(ParameterizedTypeName.get(ClassName.get(Function.class),
                                                                 className(), t),
                                       "g")
                         .returns(ParameterizedTypeName.get(ClassName.get(Function.class),
                                                            ClassName.get(Object.class), t))
                         .addStatement("return obj -> g.apply(($T) obj)", className())
                         .build();
    }

    private MethodSpec setterCreator() {
        TypeVariableName t = TypeVariableName.get("T");
        return MethodSpec.methodBuilder("setter")
                         .addTypeVariable(t)
                         .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                         .addParameter(ParameterizedTypeName.get(ClassName.get(BiConsumer.class),
                                                                 builderClassName(),
                                                                 t),
                                       "s")
                         .returns(ParameterizedTypeName.get(ClassName.get(BiConsumer.class),
                                                            ClassName.get(Object.class), t))
                         .addStatement("return (obj, val) -> s.accept(($T) obj, val)", builderClassName())
                         .build();
    }

    private MethodSpec.Builder acceptMethodSpec(ClassName modelClass, ClassName responseHandlerClass) {
        return MethodSpec.methodBuilder("accept")
                         .addModifiers(Modifier.PUBLIC)
                         .addJavadoc(new DocumentationBuilder()
                                             .description("Calls the appropriate visit method depending on "
                                                          + "the subtype of {@link $T}.")
                                             .param("visitor", "Visitor to invoke.")
                                             .build(), modelClass)
                         .addParameter(responseHandlerClass
                                               .nestedClass("Visitor"), "visitor");
    }

    @Override
    public ClassName className() {
        return shapeModelSpec.className();
    }

    private ClassName builderClassName() {
        return className().nestedClass("Builder");
    }

    private List<TypeName> modelSuperInterfaces() {
        List<TypeName> interfaces = new ArrayList<>();

        interfaces.add(ClassName.get(SdkPojo.class));

        switch (shapeModel.getShapeType()) {
            case Exception:
            case Model:
            case Request:
            case Response:
                interfaces.add(toCopyableBuilderInterface());
                break;
            default:
                break;
        }

        return interfaces;
    }

    private TypeName modelSuperClass() {
        switch (shapeModel.getShapeType()) {
            case Request:
                return requestBaseClass();
            case Response:
                return responseBaseClass();
            case Exception:
                return exceptionBaseClass();
            default:
                return ClassName.OBJECT;
        }
    }

    private TypeName requestBaseClass() {
        return new AwsServiceBaseRequestSpec(intermediateModel).className();
    }

    private TypeName responseBaseClass() {
        return new AwsServiceBaseResponseSpec(intermediateModel).className();
    }

    private ClassName exceptionBaseClass() {
        final String customExceptionBase = intermediateModel.getCustomizationConfig()
                .getSdkModeledExceptionBaseClassName();
        if (customExceptionBase != null) {
            return poetExtensions.getModelClass(customExceptionBase);
        }
        return poetExtensions.getModelClass(intermediateModel.getSdkModeledExceptionBaseClassName());
    }

    private TypeName toCopyableBuilderInterface() {
        return ParameterizedTypeName.get(ClassName.get(ToCopyableBuilder.class),
                className().nestedClass("Builder"),
                className());
    }

    private List<MethodSpec> modelClassMethods() {
        List<MethodSpec> methodSpecs = new ArrayList<>();

        switch (shapeModel.getShapeType()) {
            case Exception:
                methodSpecs.add(exceptionConstructor());
                methodSpecs.add(toBuilderMethod());
                methodSpecs.add(builderMethod());
                methodSpecs.add(serializableBuilderClass());
                methodSpecs.addAll(memberGetters());
                break;
            default:
                methodSpecs.addAll(memberGetters());
                methodSpecs.add(constructor());
                methodSpecs.add(toBuilderMethod());
                methodSpecs.add(builderMethod());
                methodSpecs.add(serializableBuilderClass());
                methodSpecs.add(modelMethodOverrides.hashCodeMethod(shapeModel));
                methodSpecs.add(modelMethodOverrides.equalsMethod(shapeModel));
                methodSpecs.add(modelMethodOverrides.toStringMethod(shapeModel));
                methodSpecs.add(getValueForField());
                break;
        }

        return methodSpecs;
    }

    private MethodSpec getValueForField() {
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("getValueForField")
                                                     .addModifiers(Modifier.PUBLIC)
                                                     .addTypeVariable(TypeVariableName.get("T"))
                                                     .returns(ParameterizedTypeName.get(ClassName.get(Optional.class),
                                                                                        TypeVariableName.get("T")))
                                                     .addParameter(String.class, "fieldName")
                                                     .addParameter(ParameterizedTypeName.get(ClassName.get(Class.class),
                                                                                             TypeVariableName.get("T")),
                                                                   "clazz");

        if (shapeModel.getNonStreamingMembers().isEmpty()) {
            methodBuilder.addStatement("return $T.empty()", Optional.class);
            return methodBuilder.build();
        }


        methodBuilder.beginControlFlow("switch ($L)", "fieldName");

        shapeModel.getNonStreamingMembers().forEach(m -> methodBuilder.addCode("case $S:", m.getC2jName())
                                                                      .addStatement("return $T.ofNullable(clazz.cast($L()))",
                                                                                    Optional.class,
                                                                                    m.getFluentGetterMethodName()));

        methodBuilder.addCode("default:");
        methodBuilder.addStatement("return $T.empty()", Optional.class);
        methodBuilder.endControlFlow();

        return methodBuilder.build();
    }


    private List<MethodSpec> memberGetters() {
        return shapeModel.getNonStreamingMembers().stream()
                         .filter(m -> !m.getHttp().getIsStreaming())
                         .flatMap(this::memberGetters)
                         .collect(Collectors.toList());
    }

    private Stream<MethodSpec> memberGetters(MemberModel member) {
        List<MethodSpec> result = new ArrayList<>();

        if (shouldGenerateEnumGetter(member)) {
            result.add(enumMemberGetter(member));
        }

        result.add(memberGetter(member));

        return result.stream();
    }

    private boolean shouldGenerateEnumGetter(MemberModel member) {
        return Utils.isOrContainsEnum(member);
    }

    private MethodSpec enumMemberGetter(MemberModel member) {
        return MethodSpec.methodBuilder(member.getFluentEnumGetterMethodName())
                         .addJavadoc("$L", member.getGetterDocumentation())
                         .addModifiers(Modifier.PUBLIC)
                         .returns(typeProvider.enumReturnType(member))
                         .addCode(enumGetterStatement(member))
                         .build();
    }

    private MethodSpec memberGetter(MemberModel member) {
        return MethodSpec.methodBuilder(member.getFluentGetterMethodName())
                         .addJavadoc("$L", member.getGetterDocumentation())
                         .addModifiers(Modifier.PUBLIC)
                         .returns(typeProvider.returnType(member))
                         .addCode(getterStatement(member))
                         .build();
    }

    private CodeBlock enumGetterStatement(MemberModel member) {
        String fieldName = member.getVariable().getVariableName();

        if (member.isList()) {
            ClassName valueEnumClass = poetExtensions.getModelClass(member.getListModel().getListMemberModel().getEnumType());
            return CodeBlock.of("return $T.convert($N, $T::fromValue);", TypeConverter.class, fieldName, valueEnumClass);
        } else if (member.isMap()) {
            MapModel mapModel = member.getMapModel();
            String keyEnumType = mapModel.getKeyModel().getEnumType();
            String valueEnumType = mapModel.getValueModel().getEnumType();

            CodeBlock keyConverter = keyEnumType != null ? enumConverterFunction(poetExtensions.getModelClass(keyEnumType))
                                                         : identityFunction();
            CodeBlock valueConverter = valueEnumType != null ? enumConverterFunction(poetExtensions.getModelClass(valueEnumType))
                                                             : identityFunction();

            CodeBlock entryPredicate = mapEntryFilter(keyEnumType);

            return CodeBlock.builder()
                            .add("return $T.convert($N, ", TypeConverter.class, fieldName)
                            .add(keyConverter).add(", ")
                            .add(valueConverter).add(", ")
                            .add(entryPredicate).add(");")
                            .build();
        } else {
            ClassName enumClass = poetExtensions.getModelClass(member.getEnumType());
            return CodeBlock.of("return $T.fromValue($N);", enumClass, fieldName);
        }
    }

    private CodeBlock mapEntryFilter(String keyEnumType) {
        // Don't include UNKNOWN_TO_SDK_VERSION keys in the enum map. Customers should use the string version to get at that data.
        return keyEnumType != null ? CodeBlock.of("(k, v) -> !$T.equals(k, $T.UNKNOWN_TO_SDK_VERSION)",
                                                  Objects.class, poetExtensions.getModelClass(keyEnumType))
                                   : CodeBlock.of("(k, v) -> true");
    }

    private CodeBlock enumConverterFunction(ClassName enumClass) {
        return CodeBlock.of("$T::fromValue", enumClass);
    }

    private CodeBlock identityFunction() {
        return CodeBlock.of("$T.identity()", Function.class);
    }

    private CodeBlock getterStatement(MemberModel model) {
        VariableModel modelVariable = model.getVariable();
        return CodeBlock.of("return $N;", modelVariable.getVariableName());
    }

    private List<TypeSpec> nestedModelClassTypes() {
        List<TypeSpec> nestedClasses = new ArrayList<>();
        switch (shapeModel.getShapeType()) {
            case Model:
            case Request:
            case Response:
            case Exception:
                nestedClasses.add(modelBuilderSpecs.builderInterface());
                nestedClasses.add(modelBuilderSpecs.beanStyleBuilder());
                break;
            default:
                break;
        }
        return nestedClasses;
    }

    private MethodSpec constructor() {
        MethodSpec.Builder ctorBuilder = MethodSpec.constructorBuilder()
                                                   .addModifiers(Modifier.PRIVATE)
                                                   .addParameter(modelBuilderSpecs.builderImplName(), "builder");

        if (isRequest() || isResponse()) {
            ctorBuilder.addStatement("super(builder)");
        }

        shapeModelSpec.fields().forEach(f -> ctorBuilder.addStatement("this.$N = builder.$N", f, f));

        return ctorBuilder.build();
    }

    private MethodSpec exceptionConstructor() {
        MethodSpec.Builder ctorBuilder = MethodSpec.constructorBuilder()
                                                   .addModifiers(Modifier.PRIVATE)
                                                   .addParameter(modelBuilderSpecs.builderImplName(), "builder");

        ctorBuilder.addStatement("super(builder)");

        shapeModelSpec.fields().forEach(f -> ctorBuilder.addStatement("this.$N = builder.$N", f, f));

        return ctorBuilder.build();
    }

    private MethodSpec builderMethod() {
        return MethodSpec.methodBuilder("builder")
                         .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                         .returns(modelBuilderSpecs.builderInterfaceName())
                         .addStatement("return new $T()", modelBuilderSpecs.builderImplName())
                         .build();
    }

    private MethodSpec toBuilderMethod() {
        return MethodSpec.methodBuilder("toBuilder")
                         .addModifiers(Modifier.PUBLIC)
                         .addAnnotation(Override.class)
                         .returns(modelBuilderSpecs.builderInterfaceName())
                         .addStatement("return new $T(this)", modelBuilderSpecs.builderImplName())
                         .build();
    }

    private MethodSpec serializableBuilderClass() {
        return MethodSpec.methodBuilder("serializableBuilderClass")
                         .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                         .returns(ParameterizedTypeName.get(ClassName.get(Class.class),
                                                            WildcardTypeName.subtypeOf(modelBuilderSpecs.builderInterfaceName())))
                         .addStatement("return $T.class", modelBuilderSpecs.builderImplName())
                         .build();
    }

    private boolean isResponse() {
        return shapeModel.getShapeType() == ShapeType.Response;
    }

    private boolean isRequest() {
        return shapeModel.getShapeType() == ShapeType.Request;
    }
}
