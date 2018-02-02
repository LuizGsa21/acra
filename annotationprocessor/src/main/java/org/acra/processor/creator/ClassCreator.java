/*
 * Copyright (c) 2018 the ACRA team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.acra.processor.creator;

import android.support.annotation.NonNull;

import com.google.auto.common.MoreTypes;
import com.google.auto.service.AutoService;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import org.acra.annotation.Configuration;
import org.acra.config.ConfigurationBuilder;
import org.acra.processor.element.BuilderElement;
import org.acra.processor.element.ConfigElement;
import org.acra.processor.element.Element;
import org.acra.processor.element.ElementFactory;
import org.acra.processor.element.ValidatedElement;
import org.acra.processor.util.Strings;
import org.acra.processor.util.Types;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.MirroredTypeException;

import static org.acra.processor.util.Strings.PACKAGE;
import static org.acra.processor.util.Strings.PARAM_0;
import static org.acra.processor.util.Strings.VAR_ANNOTATION;

/**
 * @author F43nd1r
 * @since 04.06.2017
 */

public class ClassCreator {
    private final TypeElement baseAnnotation;
    private final Configuration configuration;
    private final ProcessingEnvironment processingEnv;
    private final String factoryName;
    private final ClassName config;
    private final ClassName builder;

    public ClassCreator(@NonNull TypeElement baseAnnotation, Configuration configuration, @NonNull ProcessingEnvironment processingEnv) {
        this.baseAnnotation = baseAnnotation;
        this.configuration = configuration;
        this.processingEnv = processingEnv;
        final String configName = baseAnnotation.getSimpleName().toString().replace("Acra", "") + "Configuration";
        final String builderName = configName + "Builder";
        factoryName = builderName + "Factory";
        config = ClassName.get(PACKAGE, configName);
        builder = ClassName.get(PACKAGE, builderName);

    }

    public void createClasses() throws IOException {
        TypeElement baseBuilder;
        try {
            baseBuilder = processingEnv.getElementUtils().getTypeElement(configuration.baseBuilderClass().getName());
        } catch (MirroredTypeException e) {
            baseBuilder = MoreTypes.asTypeElement(e.getTypeMirror());
        }
        final List<Element> elements = new ModelBuilder(baseAnnotation, new ElementFactory(processingEnv.getElementUtils()), baseBuilder, processingEnv.getMessager()).build();
        createBuilderClass(elements);
        createConfigClass(elements);
        if (configuration.createBuilderFactory()) {
            createFactoryClass();
        }
    }

    private void createBuilderClass(@NonNull List<Element> elements) throws IOException {
        final TypeSpec.Builder classBuilder = TypeSpec.classBuilder(builder.simpleName())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ConfigurationBuilder.class);
        final TypeName baseAnnotation = TypeName.get(this.baseAnnotation.asType());
        Strings.addClassJavadoc(classBuilder, baseAnnotation);
        final MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ParameterSpec.builder(Types.CONTEXT, PARAM_0).addAnnotation(Types.NON_NULL).build())
                .addJavadoc("@param $L object annotated with {@link $T}\n", PARAM_0, baseAnnotation);
        constructor.addStatement("final $1T $2L = $3L.getClass().getAnnotation($1T.class)", baseAnnotation, VAR_ANNOTATION, PARAM_0);
        final CodeBlock.Builder always = CodeBlock.builder();
        final CodeBlock.Builder whenAnnotationPresent = CodeBlock.builder();
        elements.stream().filter(BuilderElement.class::isInstance).map(BuilderElement.class::cast).forEach(m -> m.addToBuilder(classBuilder, builder, always, whenAnnotationPresent));
        constructor.addCode(always.build())
                .beginControlFlow("if ($L)", Strings.FIELD_ENABLED)
                .addCode(whenAnnotationPresent.build())
                .endControlFlow();
        classBuilder.addMethod(constructor.build());
        final BuildMethodCreator build = new BuildMethodCreator(Types.getOnlyMethod(processingEnv, ConfigurationBuilder.class.getName()), config);
        elements.stream().filter(ValidatedElement.class::isInstance).map(ValidatedElement.class::cast).forEach(element -> element.addToBuildMethod(build));
        classBuilder.addMethod(build.build());
        Strings.writeClass(processingEnv.getFiler(), classBuilder.build());
    }


    private void createConfigClass(@NonNull List<Element> elements) throws IOException {
        final TypeSpec.Builder classBuilder = TypeSpec.classBuilder(config.simpleName())
                .addSuperinterface(Serializable.class)
                .addSuperinterface(org.acra.config.Configuration.class)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL);
        Strings.addClassJavadoc(classBuilder, ClassName.get(baseAnnotation.asType()));
        final MethodSpec.Builder constructor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC)
                .addParameter(ParameterSpec.builder(builder, PARAM_0).addAnnotation(Types.NON_NULL).build());
        elements.stream().filter(ConfigElement.class::isInstance).map(ConfigElement.class::cast).forEach(element -> element.addToConfig(classBuilder, constructor));
        classBuilder.addMethod(constructor.build());
        Strings.writeClass(processingEnv.getFiler(), classBuilder.build());
    }

    private void createFactoryClass() throws IOException {
        final TypeName configurationBuilderFactory = Types.CONFIGURATION_BUILDER_FACTORY;
        Strings.writeClass(processingEnv.getFiler(), TypeSpec.classBuilder(factoryName)
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(configurationBuilderFactory)
                .addAnnotation(AnnotationSpec.builder(AutoService.class).addMember("value", "$T.class", configurationBuilderFactory).build())
                .addMethod(Types.overriding(Types.getOnlyMethod(processingEnv, Strings.CONFIGURATION_BUILDER_FACTORY))
                        .addAnnotation(Types.NON_NULL)
                        .addStatement("return new $T($L)", builder, PARAM_0)
                        .build())
                .build());
    }
}
